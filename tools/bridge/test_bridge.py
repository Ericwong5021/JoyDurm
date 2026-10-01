import contextlib
import io
import json
import math
from pathlib import Path
import socket
import struct
import tempfile
import threading
import time
import unittest
import uuid
from unittest.mock import patch
from joydurm_bridge import (BridgeChannel, ClockDiscontinuity, MAX_PAYLOAD,
                            PacketRecorder, ReportClock, Scale, device_identity,
                            enumerate_devices, main, motion_packet, parse_report,
                            run_controller, simulate, stream_reports, subcommand,
                            send_command)


def imu_report(timer=0):
    report = bytearray(49)
    report[0:2] = bytes([0x30, timer])
    for index in range(3):
        struct.pack_into("<6h", report, 13 + index * 12, 0, 0, 4096, 0, 0, 0)
    return bytes(report)


def hid_info(serial="a0:b1:c2:d3:e4:f5", path=b"endpoint-1", product=0x2006, **extra):
    info = {"product_id": product, "path": path, "serial_number": serial, **extra}
    identifier, stable, source = device_identity(info)
    info.update(id=identifier, identityStable=stable, identitySource=source)
    return info


class ProtocolTests(unittest.TestCase):
    def test_decodes_three_samples_in_si_units(self):
        report = bytearray(49)
        report[0] = 0x30
        for n in range(3):
            struct.pack_into("<6h", report, 13+n*12, 0, 0, 4096, 13371, 0, 0)
        data = parse_report(report)
        self.assertEqual(len(data), 3)
        self.assertAlmostEqual(data[0]["a"][2], 9.80665)
        self.assertAlmostEqual(data[0]["g"][0], math.radians(936))
        self.assertEqual(parse_report(b"\xa1"+report), data)

    def test_rejects_truncated_and_ack_reports(self):
        self.assertEqual(parse_report(b"\x30"*48), [])
        self.assertEqual(parse_report(b"\x21"*49), [])

    def test_acc_origin_is_coefficient_not_zero_g(self):
        report = bytearray(49)
        report[0] = 0x30
        for n in range(3):
            struct.pack_into("<6h", report, 13+n*12, 0, 0, 4096, 0, 0, 0)
        scale = Scale((100, 100, 100), (16484, 16484, 16484))
        self.assertAlmostEqual(parse_report(report, scale)[0]["a"][2], 9.80665)

    def test_subcommand_structure(self):
        p = subcommand(17, 0x40, b"\x01")
        self.assertEqual(p[0:2], b"\x01\x01")
        self.assertEqual(p[10:], b"\x40\x01")

    def test_invalid_factory_calibration(self):
        with self.assertRaises(ValueError):
            Scale.from_spi(b"\0"*24)


class AckTests(unittest.TestCase):
    class Hid:
        def __init__(self, reports):
            self.reports = iter(reports)
        def write(self, packet):
            return len(packet)
        def read(self, *_):
            return next(self.reports, b"")

    @staticmethod
    def ack(cmd, positive=True, payload=b""):
        report=bytearray(15)
        report[0]=0x21; report[13]=0x80 if positive else 0; report[14]=cmd
        return bytes(report)+payload

    def test_ignores_unrelated_ack_and_accepts_prefix(self):
        expected=self.ack(0x40)
        device=self.Hid([self.ack(0x30), b"\xa1"+expected])
        self.assertEqual(send_command(device, 0, 0x40), expected)

    def test_negative_ack_fails(self):
        with self.assertRaises(OSError):
            send_command(self.Hid([self.ack(0x40, False)]), 0, 0x40)

    def test_spi_requires_matching_address_length_and_complete_payload(self):
        request=struct.pack("<IB", 0x6020, 24)
        good=self.ack(0x10, payload=request+bytes(24))
        wrong=self.ack(0x10, payload=struct.pack("<IB", 0x6030, 24)+bytes(24))
        short=self.ack(0x10, payload=request+bytes(5))
        self.assertEqual(send_command(self.Hid([wrong, short, good]), 0, 0x10, request),good)

    def test_short_write_fails(self):
        device=self.Hid([])
        device.write=lambda _: 1
        with self.assertRaises(OSError):
            send_command(device, 0, 0x40)

    def test_timeout_retries_exactly_three_times(self):
        device = self.Hid([])
        writes = []
        device.write = lambda packet: writes.append(packet) or len(packet)
        # Exercise timeout/retry without waiting three real seconds.
        with patch("joydurm_bridge.time.monotonic", side_effect=range(20)):
            with self.assertRaises(TimeoutError):
                send_command(device, 0, 0x40)
        self.assertEqual(len(writes), 3)


class ReportClockTests(unittest.TestCase):
    def test_warmup_anchors_least_delayed_read_before_any_publication(self):
        clock = ReportClock()
        self.assertEqual(clock.sample_times(0, 1_050_000_000), [])
        self.assertEqual(clock.sample_times(3, 1_060_000_000), [])
        times = clock.sample_times(30, 1_150_000_000)
        self.assertEqual([stamp for _, stamp in times],
                         [1_140_000_000, 1_145_000_000, 1_150_000_000])

    def test_delayed_hid_read_does_not_retime_source_samples(self):
        clock = ReportClock(warmup_ns=0)
        first = clock.sample_times(10, 1_000_000_000)
        second = clock.sample_times(13, 1_500_000_000)
        self.assertEqual([stamp for _, stamp in first + second],
                         [990_000_000, 995_000_000, 1_000_000_000,
                          1_005_000_000, 1_010_000_000, 1_015_000_000])
        self.assertEqual(clock.anchor_ns, 1_000_000_000)

    def test_timer_wrap_is_continuous_and_duplicates_rejected(self):
        clock = ReportClock(warmup_ns=0)
        first = clock.sample_times(254, 1_000_000_000)
        self.assertEqual(clock.sample_times(254, 1_005_000_000), [])
        self.assertEqual(clock.sample_times(253, 1_010_000_000), [])
        second = clock.sample_times(1, 1_015_000_000)
        self.assertEqual(second[0][1] - first[-1][1], 5_000_000)
        self.assertEqual(clock.duplicates, 1)
        self.assertEqual(clock.out_of_order, 1)

    def test_overlapping_reports_emit_only_new_sample(self):
        clock = ReportClock(warmup_ns=0)
        clock.sample_times(1, 1_000_000_000)
        self.assertEqual(clock.sample_times(2, 1_005_000_000), [(2, 1_005_000_000)])

    def test_long_gap_and_clock_jump_require_new_session(self):
        for stamp in (999_999_999, 1_601_000_000):
            clock = ReportClock(warmup_ns=0)
            clock.sample_times(1, 1_000_000_000)
            with self.assertRaises(ClockDiscontinuity):
                clock.sample_times(4, stamp)

    def test_future_timer_requires_new_session_not_last_plus_one(self):
        clock = ReportClock(warmup_ns=0)
        clock.sample_times(1, 1_000_000_000)
        with self.assertRaises(ClockDiscontinuity):
            clock.sample_times(4, 1_001_000_000)

    def test_small_future_estimate_is_never_published(self):
        clock = ReportClock(warmup_ns=0)
        clock.sample_times(1, 1_000_000_000)
        times = clock.sample_times(4, 1_012_000_000)
        self.assertEqual(times, [(0, 1_005_000_000), (1, 1_010_000_000)])
        self.assertTrue(all(stamp <= 1_012_000_000 for _, stamp in times))


class IdentityTests(unittest.TestCase):
    def test_serial_case_is_preserved_and_invalid_mac_is_not_identity_evidence(self):
        upper, lower = hid_info(serial="Serial-ABC"), hid_info(serial="Serial-abc")
        self.assertNotEqual(upper["id"], lower["id"])
        self.assertEqual(upper["identitySource"], "serial")
        malformed = hid_info(serial=None, mac_address="not-a-MAC")
        self.assertFalse(malformed["identityStable"])

    def test_path_change_preserves_physical_identity(self):
        before = hid_info(path=b"hidraw1")
        after = hid_info(path=b"hidraw99", serial="A0-B1-C2-D3-E4-F5")
        self.assertEqual(before["id"], after["id"])
        self.assertTrue(before["identityStable"])
        self.assertEqual(before["identitySource"], "mac")

    def test_four_same_name_units_remain_four_physical_devices(self):
        devices = [hid_info(serial=f"00:11:22:33:44:{index:02x}",
                            path=f"hid{index}".encode()) for index in range(4)]
        result = enumerate_devices(lambda _: devices)
        self.assertEqual(len(result), 4)
        self.assertTrue(all(info["identityStable"] for info in result.values()))

    def test_non_controller_interfaces_filtered_and_physical_duplicates_deduped(self):
        devices = [hid_info(path=b"vendor", usage_page=0xff00, usage=1),
                   hid_info(path=b"secondary", interface_number=1, usage_page=1, usage=5),
                   hid_info(path=b"primary", interface_number=0, usage_page=1, usage=5)]
        result = enumerate_devices(lambda _: devices)
        self.assertEqual(len(result), 1)
        self.assertEqual(next(iter(result.values()))["path"], b"primary")

    def test_no_serial_is_explicitly_unverified_and_never_grouped_by_name(self):
        for serial in (None, "", "00:00:00:00:00:00", "unknown"):
            devices = [hid_info(serial=serial, path=b"one"), hid_info(serial=serial, path=b"two")]
            result = enumerate_devices(lambda _: devices)
            self.assertEqual(len(result), 2)
            self.assertTrue(all(identifier.startswith("UNVERIFIED-") for identifier in result))
            self.assertTrue(all(not info["identityStable"] for info in result.values()))

    def test_unverified_identity_requires_explicit_cli_opt_in(self):
        info = hid_info(serial=None)
        args = ["bridge", "--host", "127.0.0.1", "--token", "0123456789abcdef",
                "--bind", "LEFT_HAND=" + info["id"]]
        with patch("sys.argv", args), patch("joydurm_bridge.enumerate_devices", return_value={info["id"]: info}):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                main()
        self.assertEqual(error.exception.code, 2)


class StreamTests(unittest.TestCase):
    def test_duplicate_reports_cannot_feed_valid_data_watchdog(self):
        stop = threading.Event()
        device = AckTests.Hid([imu_report(5)] * 20)
        channel = type("Channel", (), {"send": lambda *_: None})()
        clock = iter(1_000_000_000 + i * 250_000_000 for i in range(20))
        with self.assertRaises(ClockDiscontinuity):
            stream_reports(device, channel, hid_info(), "LEFT_HAND", "token", str(uuid.uuid4()),
                           Scale(), stop=stop, now_ns=lambda: next(clock),
                           report_clock=ReportClock(warmup_ns=0))

    def test_only_invalid_reports_expire_after_three_seconds(self):
        stop = threading.Event()
        device = AckTests.Hid([b"\x21"] * 20)
        channel = type("Channel", (), {"send": lambda *_: self.fail("Invalid input was published")})()
        clock = iter(1_000_000_000 + i * 250_000_000 for i in range(20))
        with self.assertRaises(TimeoutError):
            stream_reports(device, channel, hid_info(), "LEFT_HAND", "token", str(uuid.uuid4()),
                           Scale(), stop=stop, now_ns=lambda: next(clock))

    def test_reconnect_reenumerates_new_path_new_session_and_sequence(self):
        stop = threading.Event()
        before, after = hid_info(path=b"before"), hid_info(path=b"after")
        enumeration = iter([{before["id"]: before}, {after["id"]: after}])
        opened, packets = [], []
        source_time = [1_000_000_000]
        instances = [0]

        class Hid:
            def __init__(self):
                instances[0] += 1
                self.number = instances[0]
                self.reply = None
                self.reads = 0
            def open_path(self, path):
                opened.append(path)
            def write(self, packet):
                command = packet[10]
                payload = packet[11:16] + struct.pack("<12h", *(Scale().acc_origin + Scale().acc_sensitivity + Scale().gyro_origin + Scale().gyro_sensitivity)) if command == 0x10 else b""
                self.reply = AckTests.ack(command, payload=payload)
                return len(packet)
            def read(self, *_):
                if self.reply is not None:
                    reply, self.reply = self.reply, None
                    return reply
                self.reads += 1
                source_time[0] += 15_000_000
                if self.reads > 9:
                    if self.number == 2:
                        stop.set()
                    raise OSError("Fixture disconnect")
                return imu_report((self.reads * 3) & 255)
            def close(self):
                pass

        class Channel:
            def __init__(self, *_args, **_kwargs):
                pass
            def send(self, packet):
                packets.append(packet)
            def close(self):
                pass

        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            run_controller(before["id"], before, "LEFT_HAND", ("127.0.0.1", 0), "token",
                           stop=stop, enumerate_current=lambda: next(enumeration),
                           hid_factory=Hid, channel_factory=Channel, retry_seconds=0,
                           now_ns=lambda: source_time[0])
        self.assertEqual(opened, [b"before", b"after"])
        sessions = {packet["sessionId"] for packet in packets}
        self.assertEqual(len(sessions), 2)
        self.assertEqual({packet["device"] for packet in packets}, {before["id"]})
        for session in sessions:
            self.assertEqual([packet["seq"] for packet in packets if packet["sessionId"] == session], [1, 2])

    def test_missing_device_is_not_replaced_by_same_name_controller(self):
        stop = threading.Event()
        expected, other = hid_info(), hid_info(serial="different")
        def enumerate_missing():
            stop.set()
            return {other["id"]: other}
        with contextlib.redirect_stderr(io.StringIO()):
            run_controller(expected["id"], expected, "LEFT_HAND", ("127.0.0.1", 0), "token",
                           stop=stop, enumerate_current=enumerate_missing,
                           hid_factory=lambda: self.fail("Other device was opened"), retry_seconds=0)


class BridgeSocketIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.receiver = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.receiver.bind(("127.0.0.1", 0))
        self.receiver.settimeout(1)
        self.token = "0123456789abcdef"
        self.channel = BridgeChannel(self.receiver.getsockname(), self.token)
        self.info = hid_info()
        self.session = str(uuid.uuid4())

    def tearDown(self):
        self.channel.close()
        self.receiver.close()
        self.assertFalse(self.channel.thread.is_alive())

    def packet(self, seq, source_ns, timer):
        data = parse_report(imu_report(timer))
        times = [(index, source_ns - (2 - index) * 5_000_000) for index in range(3)]
        return motion_packet(self.info, "LEFT_HAND", self.token, self.session,
                             seq, source_ns, timer, data, times)

    def test_two_batches_delivered_together_keep_all_six_source_samples(self):
        source_ns = time.monotonic_ns() - 50_000_000
        packets = [self.packet(1, source_ns, 254), self.packet(2, source_ns + 15_000_000, 1)]
        for packet in packets:
            self.channel.send(packet)
        received = [json.loads(self.receiver.recvfrom(MAX_PAYLOAD)[0]) for _ in packets]
        timestamps = [sample["sourceTimeNs"] for packet in received for sample in packet["samples"]]
        self.assertEqual(len(timestamps), 6)
        self.assertEqual(timestamps, [source_ns - 10_000_000 + i * 5_000_000 for i in range(6)])
        self.assertEqual(received, packets)

    def test_delayed_packet_keeps_old_source_time_and_read_time(self):
        old_source_ns = time.monotonic_ns() - 500_000_000
        packet = self.packet(1, old_source_ns, 10)
        self.channel.send(packet)
        received = json.loads(self.receiver.recvfrom(MAX_PAYLOAD)[0])
        self.assertEqual(received["sourceReadNs"], old_source_ns)
        self.assertEqual(received["samples"][-1]["sourceTimeNs"], old_source_ns)
        self.assertGreater(time.monotonic_ns() - received["samples"][-1]["sourceTimeNs"], 500_000_000)

    def test_sync_round_trip_uses_same_udp_source_endpoint(self):
        self.channel.send(self.packet(1, time.monotonic_ns(), 0))
        _, endpoint = self.receiver.recvfrom(MAX_PAYLOAD)
        sent_ns = time.monotonic_ns()
        nonce = str(uuid.uuid4())
        request = {"v": 2, "type": "sync", "token": self.token, "nonce": nonce, "clientSendNs": sent_ns}
        self.receiver.sendto(json.dumps(request).encode(), endpoint)
        payload, reply_endpoint = self.receiver.recvfrom(MAX_PAYLOAD)
        reply, received_ns = json.loads(payload), time.monotonic_ns()
        self.assertEqual(reply_endpoint, endpoint)
        self.assertEqual(reply["type"], "sync_reply")
        self.assertEqual(reply["nonce"], nonce)
        self.assertEqual(reply["clientSendNs"], sent_ns)
        self.assertLessEqual(sent_ns, reply["sourceReceiveNs"])
        self.assertLessEqual(reply["sourceReceiveNs"], reply["sourceSendNs"])
        self.assertLessEqual(reply["sourceSendNs"], received_ns)

    def test_invalid_sync_token_schema_and_oversize_do_not_get_reply(self):
        self.channel.send(self.packet(1, time.monotonic_ns(), 0))
        _, endpoint = self.receiver.recvfrom(MAX_PAYLOAD)
        requests = [b"{}", b"not-json", b"x" * (MAX_PAYLOAD + 1),
                    json.dumps({"v": 2, "type": "sync", "token": "wrong", "nonce": "n", "clientSendNs": 1}).encode(),
                    json.dumps({"v": 2, "type": "sync", "token": "非ASCII错误token", "nonce": "n", "clientSendNs": 1}).encode(),
                    json.dumps({"v": 2, "type": "sync", "token": self.token, "nonce": "n", "clientSendNs": True}).encode()]
        for request in requests:
            self.receiver.sendto(request, endpoint)
        self.receiver.settimeout(0.2)
        with self.assertRaises(socket.timeout):
            self.receiver.recvfrom(MAX_PAYLOAD)
        self.assertTrue(self.channel.thread.is_alive())

    def test_four_identity_channels_one_disconnect_keeps_other_three_live(self):
        channels = [self.channel] + [BridgeChannel(self.receiver.getsockname(), self.token) for _ in range(3)]
        roles = ("LEFT_HAND", "RIGHT_HAND", "LEFT_FOOT", "RIGHT_FOOT")
        identities = [hid_info(serial=f"00:11:22:33:44:{index:02x}") for index in range(4)]
        sessions = [str(uuid.uuid4()) for _ in range(4)]
        def send(channel, index, seq):
            stamp = time.monotonic_ns()
            packet = motion_packet(identities[index], roles[index], self.token,
                                   sessions[index], seq, stamp, seq * 3, parse_report(imu_report()),
                                   [(n, stamp - (2 - n) * 5_000_000) for n in range(3)])
            channel.send(packet)
        try:
            for index, channel in enumerate(channels):
                send(channel, index, 1)
            first = [self.receiver.recvfrom(MAX_PAYLOAD) for _ in range(4)]
            self.assertEqual(len({json.loads(data)["device"] for data, _ in first}), 4)
            self.assertEqual(len({endpoint for _, endpoint in first}), 4)
            channels[0].close()
            for index, channel in enumerate(channels[1:], start=1):
                send(channel, index, 2)
            remaining = [json.loads(self.receiver.recvfrom(MAX_PAYLOAD)[0]) for _ in range(3)]
            self.assertEqual({packet["device"] for packet in remaining}, {info["id"] for info in identities[1:]})
            self.assertEqual({packet["seq"] for packet in remaining}, {2})
        finally:
            for channel in channels[1:]:
                channel.close()

    def test_real_simulator_sends_four_explicit_session_local_roles(self):
        stop = threading.Event()
        errors = []
        def run():
            try:
                simulate(self.receiver.getsockname(), self.token)
            except Exception as error:
                errors.append(error)
        with patch("joydurm_bridge.STOP", stop):
            worker = threading.Thread(target=run)
            worker.start()
            try:
                packets = [json.loads(self.receiver.recvfrom(MAX_PAYLOAD)[0]) for _ in range(4)]
                self.assertEqual({packet["role"] for packet in packets},
                                 {"LEFT_HAND", "RIGHT_HAND", "LEFT_FOOT", "RIGHT_FOOT"})
                self.assertEqual(len({packet["sessionId"] for packet in packets}), 4)
                self.assertTrue(all(not packet["identityStable"] for packet in packets))
                self.assertTrue(all(packet["identitySource"] == "simulator" for packet in packets))
                self.assertTrue(all(packet["samples"][0]["sourceTimeNs"] <= packet["sourceReadNs"] for packet in packets))
                self.assertTrue(all(packet["token"] == self.token for packet in packets))
            finally:
                stop.set()
                worker.join(timeout=1)
            self.assertFalse(worker.is_alive())
        self.assertEqual(errors, [])


class RecorderTests(unittest.TestCase):
    def test_records_jsonl_without_token_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trace.jsonl"
            recorder = PacketRecorder(path)
            packet = motion_packet(hid_info(), "LEFT_HAND", "secret", str(uuid.uuid4()),
                                   1, 1_000_000_000, 1, parse_report(imu_report(1)),
                                   [(index, 990_000_000 + index * 5_000_000) for index in range(3)])
            recorder.record(packet)
            recorder.close()
            recorded = json.loads(path.read_text())
            self.assertNotIn("token", recorded)
            self.assertEqual(recorded["samples"], packet["samples"])
            with self.assertRaises(FileExistsError):
                PacketRecorder(path)

    def test_simulator_cli_passes_token_and_remains_explicit(self):
        args = ["bridge", "--host", "127.0.0.1", "--token", "0123456789abcdef", "--simulate"]
        with patch("sys.argv", args), patch("joydurm_bridge.simulate") as diagnostic:
            main()
        diagnostic.assert_called_once_with(("127.0.0.1", 18185), "0123456789abcdef", recorder=None)


if __name__ == "__main__":
    unittest.main()
