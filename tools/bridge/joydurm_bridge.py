#!/usr/bin/env python3
"""Original Switch Joy-Con HID -> JoyDurm Android UDP bridge. SI units; no cloud."""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import math
import re
import signal
import socket
import struct
import sys
import threading
import time
import uuid
from dataclasses import dataclass

ROLES = ("LEFT_HAND", "RIGHT_HAND", "LEFT_FOOT", "RIGHT_FOOT")
PRODUCTS = {0x2006: "Joy-Con L", 0x2007: "Joy-Con R"}
STOP = threading.Event()
SAMPLE_NS = 5_000_000
MAX_PAYLOAD = 8192
WATCHDOG_NS = 3_000_000_000
# Half the 8-bit timer cycle is 640 ms. Longer read gaps cannot be unwrapped
# unambiguously, so never guess a sample time across them.
MAX_TIMER_GAP_NS = 600_000_000


@dataclass
class Scale:
    acc_origin: tuple = (0, 0, 0)
    acc_sensitivity: tuple = (16384, 16384, 16384)
    gyro_origin: tuple = (0, 0, 0)
    gyro_sensitivity: tuple = (13371, 13371, 13371)

    @classmethod
    def from_spi(cls, payload: bytes):
        if len(payload) != 24:
            raise ValueError("Factory IMU calibration must be 24 bytes")
        groups = struct.unpack("<12h", payload)
        result = cls(*(groups[n:n + 3] for n in (0, 3, 6, 9)))
        if any(abs(result.acc_sensitivity[k] - result.acc_origin[k]) < 100 for k in range(3)):
            raise ValueError("Invalid accelerometer coefficient")
        if any(abs(result.gyro_sensitivity[k] - result.gyro_origin[k]) < 100 for k in range(3)):
            raise ValueError("Invalid gyroscope coefficient")
        return result


def parse_report(report: bytes, scale: Scale = Scale()):
    if report and report[0] == 0xA1:
        report = report[1:]
    if len(report) < 49 or report[0] != 0x30:
        return []
    result = []
    for index in range(3):
        v = struct.unpack_from("<6h", report, 13 + index * 12)
        # Acc origin calibrates SCALE; it is not the raw zero-g offset.
        a = [v[k] * 4 * 9.80665 / (scale.acc_sensitivity[k] - scale.acc_origin[k]) for k in range(3)]
        g = [(v[3+k] - scale.gyro_origin[k]) * math.radians(936) / (scale.gyro_sensitivity[k] - scale.gyro_origin[k]) for k in range(3)]
        if not all(math.isfinite(x) for x in a + g) or math.hypot(*a) > 200 or math.hypot(*g) > 100:
            return []
        result.append({"a": a, "g": g})
    return result


class ClockDiscontinuity(ValueError):
    """A new session is required; old motion must not be made fresh."""


class ReportClock:
    """Estimate sample times from the report timer, never from UDP delivery.

    During startup the least delayed HID read anchors the timer to the source
    monotonic clock. No motion is published during this 100 ms warmup. Once
    published, the anchor is fixed for the session. Unknown Bluetooth/HID
    latency remains a hardware measurement boundary, not a claimed error bound.
    """

    def __init__(self, warmup_ns=100_000_000):
        self.warmup_ns = warmup_ns
        self.first_read_ns = None
        self.last_valid_read_ns = None
        self.last_timer = None
        self.elapsed_ns = 0
        self.anchor_ns = None
        self.ready = False
        self.last_emitted_ns = None
        self.duplicates = 0
        self.out_of_order = 0

    def sample_times(self, timer, source_read_ns):
        if not isinstance(timer, int) or not 0 <= timer <= 255 or source_read_ns < 0:
            raise ClockDiscontinuity("Invalid report timer/source clock")
        if self.last_valid_read_ns is not None:
            gap = source_read_ns - self.last_valid_read_ns
            if gap < 0 or gap > MAX_TIMER_GAP_NS:
                raise ClockDiscontinuity("Ambiguous report timer after HID read gap")
            delta = (timer - self.last_timer) & 255
            if delta == 0:
                self.duplicates += 1
                return []
            if delta > 127:
                self.out_of_order += 1
                return []
            self.elapsed_ns += delta * SAMPLE_NS
        else:
            self.first_read_ns = source_read_ns
        self.last_timer = timer
        self.last_valid_read_ns = source_read_ns
        if not self.ready:
            candidate = source_read_ns - self.elapsed_ns
            self.anchor_ns = candidate if self.anchor_ns is None else min(self.anchor_ns, candidate)
            self.ready = source_read_ns - self.first_read_ns >= self.warmup_ns
            if not self.ready:
                return []
        latest_ns = self.anchor_ns + self.elapsed_ns
        if latest_ns - source_read_ns > SAMPLE_NS:
            raise ClockDiscontinuity("Report timer is ahead of the source clock")
        times = [latest_ns - (2 - index) * SAMPLE_NS for index in range(3)]
        # Timers can advance by less than three ticks: retain only genuinely new
        # samples instead of emitting overlapping timestamps or using last + 1.
        fresh = [(index, stamp) for index, stamp in enumerate(times)
                 if 0 <= stamp <= source_read_ns and (self.last_emitted_ns is None or stamp > self.last_emitted_ns)]
        if fresh:
            self.last_emitted_ns = fresh[-1][1]
        return fresh


def motion_packet(info, role, token, session_id, seq, source_read_ns, timer, data, times):
    samples = [{"sourceTimeNs": stamp, "ax": data[index]["a"][0],
                "ay": data[index]["a"][1], "az": data[index]["a"][2],
                "gx": data[index]["g"][0], "gy": data[index]["g"][1],
                "gz": data[index]["g"][2]} for index, stamp in times]
    return {"v": 2, "token": token, "device": info["id"],
            "name": PRODUCTS[info["product_id"]], "role": role,
            "identityStable": info["identityStable"], "identitySource": info["identitySource"],
            "vendorId": 0x057E, "productId": info["product_id"],
            "sessionId": session_id, "seq": seq, "sourceReadNs": source_read_ns,
            "timer": timer, "samples": samples}


class PacketRecorder:
    """Thread-safe source trace. Tokens are excluded from files."""

    def __init__(self, path):
        self.file = open(path, "x", encoding="utf-8")
        self.lock = threading.Lock()

    def record(self, packet):
        record = {key: value for key, value in packet.items() if key != "token"}
        with self.lock:
            self.file.write(json.dumps(record, separators=(",", ":"), allow_nan=False) + "\n")
            self.file.flush()

    def close(self):
        with self.lock:
            self.file.close()


class BridgeChannel:
    """Motion and clock replies share an ephemeral UDP endpoint per session.

    Sync is served by its own bounded-time thread, so a blocking HID read cannot
    add 250 ms to the receiver's RTT measurement. A connected UDP socket only
    accepts datagrams from the configured Android endpoint.
    """

    def __init__(self, target, token, recorder=None, clock=time.monotonic_ns):
        self.token = token
        self.recorder = recorder
        self.clock = clock
        self.stopped = threading.Event()
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.socket.bind(("0.0.0.0", 0))
        self.socket.connect(target)
        self.socket.settimeout(0.1)
        self.thread = threading.Thread(target=self._sync_loop, name="joydurm-clock-sync", daemon=True)
        self.thread.start()

    def send(self, packet):
        payload = json.dumps(packet, separators=(",", ":"), allow_nan=False).encode()
        if len(payload) > MAX_PAYLOAD:
            raise ValueError("Bridge packet exceeds maximum payload")
        self.socket.send(payload)
        if self.recorder:
            self.recorder.record(packet)

    def _sync_loop(self):
        while not self.stopped.is_set():
            try:
                payload = self.socket.recv(MAX_PAYLOAD + 1)
                received_ns = self.clock()
                if len(payload) > MAX_PAYLOAD:
                    continue
                request = json.loads(payload)
                if not isinstance(request, dict) or type(request.get("v")) is not int or request.get("v") != 2 or request.get("type") != "sync":
                    continue
                token = request.get("token")
                nonce = request.get("nonce")
                sent_ns = request.get("clientSendNs")
                if not isinstance(token, str) or not hmac.compare_digest(token.encode(), self.token.encode()):
                    continue
                if not isinstance(nonce, str) or not 1 <= len(nonce) <= 128:
                    continue
                if type(sent_ns) is not int or not 0 <= sent_ns <= 2**63 - 1:
                    continue
                reply = {"v": 2, "type": "sync_reply", "token": self.token,
                         "nonce": nonce, "clientSendNs": sent_ns,
                         "sourceReceiveNs": received_ns, "sourceSendNs": self.clock()}
                self.send(reply)
            except socket.timeout:
                continue
            except (ValueError, UnicodeDecodeError, RecursionError):
                continue
            except OSError:
                # A target may not yet be listening. UDP ICMP errors do not
                # change the HID session or turn old source data into fresh data.
                if self.stopped.wait(0.05):
                    return

    def close(self):
        self.stopped.set()
        self.thread.join(timeout=0.5)
        self.socket.close()


def subcommand(counter, command, data=b""):
    return bytes([1, counter & 15, 0, 1, 0x40, 0x40, 0, 1, 0x40, 0x40, command]) + data


def send_command(controller, counter, cmd, data=b"", stop=STOP):
    """Only a matching positive ACK succeeds; unrelated IMU/ACK reports are ignored."""
    packet = subcommand(counter, cmd, data).ljust(49, b"\0")
    for _ in range(3):
        if stop.is_set():
            raise InterruptedError("Bridge stopped")
        written = controller.write(packet)
        if written != len(packet):
            raise OSError("Incomplete Joy-Con HID write")
        until = time.monotonic() + 1.0
        while time.monotonic() < until and not stop.is_set():
            report = bytes(controller.read(128, 100))
            if report[:1] == b"\xa1":
                report = report[1:]
            if len(report) < 15 or report[0] != 0x21 or report[14] != cmd:
                continue
            if not report[13] & 0x80:
                raise OSError(f"Joy-Con rejected subcommand 0x{cmd:02x}")
            if cmd == 0x10:
                # SPI ACK echoes the requested address and length. A stale read ACK
                # must not supply coefficients for a different flash region.
                if len(data) != 5 or len(report) < 20 or report[15:20] != data:
                    continue
                if len(report) < 20 + data[4]:
                    continue
            return report
    raise TimeoutError(f"No acknowledgment for subcommand 0x{cmd:02x}")


def _identity_value(value):
    if isinstance(value, bytes):
        try:
            value = value.decode("utf-8")
        except UnicodeDecodeError:
            return None
    if not isinstance(value, str):
        return None
    value = value.strip()
    if not value or value.casefold() in ("unknown", "none", "n/a", "nintendo", "joy-con l", "joy-con r"):
        return None
    compact = re.sub(r"[:-]", "", value).casefold()
    if re.fullmatch(r"[0-9a-f]{12}", compact):
        if compact in ("0" * 12, "f" * 12):
            return None
        return "mac:" + compact
    if not value.strip("0 -:"):
        return None
    return "serial:" + value


def device_identity(info):
    """Return a physical identity only when HID supplies serial/MAC evidence."""
    for key in ("serial_number", "mac_address"):
        value = _identity_value(info.get(key))
        if key == "mac_address" and value and not value.startswith("mac:"):
            continue
        if value:
            physical = f"057e:{info['product_id']:04x}:{value}".encode()
            return (f"joycon-{info['product_id']:04x}-" + hashlib.sha256(physical).hexdigest()[:16],
                    True, "mac" if value.startswith("mac:") else "serial")
    path = info["path"]
    endpoint = path if isinstance(path, bytes) else path.encode()
    # Explicitly UNVERIFIED: a path identifies an endpoint, never a controller.
    return "UNVERIFIED-" + hashlib.sha256(endpoint).hexdigest()[:16], False, "endpoint-only"


def _endpoint_rank(info):
    standard = info.get("usage_page", 0) == 1 and info.get("usage", 0) in (4, 5)
    interface = info.get("interface_number", -1)
    interface = interface if isinstance(interface, int) else -1
    return (not standard, interface != 0, interface if interface >= 0 else 999, repr(info["path"]))


def enumerate_devices(enumerate_hid=None):
    if enumerate_hid is None:
        import hid
        enumerate_hid = hid.enumerate
    result = {}
    for device in enumerate_hid(0x057E):
        if device.get("product_id") not in PRODUCTS or not device.get("path"):
            continue
        usage_page, usage = device.get("usage_page", 0), device.get("usage", 0)
        # Exclude known non-controller interfaces; some platforms omit usage.
        if usage_page not in (0, None, 1) or (usage_page == 1 and usage not in (0, None, 4, 5)):
            continue
        info = dict(device)
        identifier, stable, source = device_identity(info)
        info.update(id=identifier, identityStable=stable, identitySource=source)
        previous = result.get(identifier)
        if previous is None or _endpoint_rank(info) < _endpoint_rank(previous):
            result[identifier] = info
    return result


def stream_reports(controller, channel, info, role, token, session_id, scale,
                   stop=STOP, now_ns=time.monotonic_ns, report_clock=None):
    report_clock = report_clock or ReportClock()
    last_valid_ns = now_ns()
    seq = 0
    while not stop.is_set():
        report = bytes(controller.read(128, 250))
        source_read_ns = now_ns()
        if report[:1] == b"\xa1":
            report = report[1:]
        data = parse_report(report, scale)
        if data:
            before = report_clock.last_valid_read_ns
            times = report_clock.sample_times(report[1], source_read_ns)
            if report_clock.last_valid_read_ns != before:
                # Only advancing valid IMU data feeds the watchdog.
                last_valid_ns = source_read_ns
            if times:
                seq += 1
                channel.send(motion_packet(info, role, token, session_id, seq,
                                           source_read_ns, report[1], data, times))
        if source_read_ns - last_valid_ns >= WATCHDOG_NS:
            raise TimeoutError("No valid advancing IMU data for 3 seconds")


def run_controller(identifier, info, role, target, token, recorder=None,
                   stop=STOP, enumerate_current=None, hid_factory=None,
                   channel_factory=BridgeChannel, retry_seconds=2.0,
                   now_ns=time.monotonic_ns):
    if enumerate_current is None:
        enumerate_current = enumerate_devices
    if hid_factory is None:
        import hid
        hid_factory = hid.device
    while not stop.is_set():
        controller = None
        channel = None
        try:
            # HID paths change after disconnect. Re-enumerate by physical ID.
            # A missing controller is never replaced by another same-name unit.
            current = enumerate_current().get(identifier)
            if current is None:
                raise FileNotFoundError(f"Controller {identifier} is not currently enumerated")
            if current["product_id"] != info["product_id"]:
                raise ValueError("Controller identity/product mismatch")
            controller = hid_factory()
            controller.open_path(current["path"])
            session_id = str(uuid.uuid4())
            channel = channel_factory(target, token, recorder=recorder)
            counter = 0

            def command(cmd, data=b""):
                nonlocal counter
                result = send_command(controller, counter, cmd, data, stop=stop)
                counter = (counter + 1) & 15
                return result

            command(0x40, b"\x01")
            command(0x03, b"\x30")
            command(0x30, bytes([1 << ROLES.index(role)]))
            scale = Scale()
            try:
                ack = command(0x10, struct.pack("<IB", 0x6020, 24))
                scale = Scale.from_spi(ack[20:44])
            except (ValueError, TimeoutError, OSError) as e:
                print(f"{role}: factory calibration unavailable, using default scale ({e})", file=sys.stderr)
            print(f"{role}: {PRODUCTS[current['product_id']]} connected ({identifier}), session {session_id}", flush=True)
            stream_reports(controller, channel, current, role, token, session_id, scale,
                           stop=stop, now_ns=now_ns)
        except Exception as e:
            if not stop.is_set():
                print(f"{role}: {e}; retrying in {retry_seconds:g} seconds", file=sys.stderr)
        finally:
            if channel is not None:
                channel.close()
            if controller is not None:
                try:
                    controller.close()
                except OSError:
                    pass
        stop.wait(retry_seconds)


def simulate(target, token, recorder=None):
    """Explicit diagnostic input; never presented as connected hardware."""
    channel = BridgeChannel(target, token, recorder=recorder)
    sessions = {role: str(uuid.uuid4()) for role in ROLES}
    seq = 0
    try:
        while not STOP.is_set():
            seq += 1
            source_ns = time.monotonic_ns()
            t = source_ns / 1_000_000_000
            for index, role in enumerate(ROLES):
                pitch = 0.2 * (1 + math.sin(t * 1.5)) if role == "LEFT_FOOT" else 0
                a = [0, -9.80665 * math.sin(pitch), 9.80665 * math.cos(pitch)]
                g = [max(0, math.sin(t * 7 + index)) * 7, 0, 0] if "HAND" in role else [0, 0, 0]
                if role == "RIGHT_FOOT":
                    a[2] += max(0, math.sin(t * 7)) * 8
                packet = {"v": 2, "token": token, "device": f"SIMULATOR-{role}",
                          "name": "Diagnostic simulator", "role": role,
                          "identityStable": False, "identitySource": "simulator",
                          "sessionId": sessions[role], "seq": seq, "sourceReadNs": source_ns,
                          "timer": (source_ns // SAMPLE_NS) & 255,
                          "samples": [{"sourceTimeNs": source_ns, "ax": a[0], "ay": a[1], "az": a[2],
                                       "gx": g[0], "gy": g[1], "gz": g[2]}]}
                channel.send(packet)
            STOP.wait(0.015)
    finally:
        channel.close()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--list", action="store_true", help="List paired original Switch Joy-Cons and identity evidence")
    p.add_argument("--host", help="Android phone LAN IPv4 address")
    p.add_argument("--port", type=int, default=18185)
    p.add_argument("--token", help="Token copied from Android App, at least 16 characters")
    p.add_argument("--bind", action="append", default=[], metavar="ROLE=ID")
    p.add_argument("--simulate", action="store_true", help="Send diagnostic motion without hardware")
    p.add_argument("--allow-unstable-id", action="store_true", help="Allow unverified endpoint IDs; assign roles again each session")
    p.add_argument("--record", metavar="FILE", help="Create a JSONL source trace with token omitted (never overwrite)")
    args = p.parse_args()
    if args.list:
        for identifier, d in enumerate_devices().items():
            stability = "PHYSICAL" if d["identityStable"] else "UNVERIFIED (explicit opt-in required)"
            print(f"{identifier}  {PRODUCTS[d['product_id']]}  {stability}  {d['identitySource']}")
        return
    if not args.host or not args.token or len(args.token) < 16 or not 1024 <= args.port <= 65535:
        p.error("Provide --host, --token (16+ characters), and a port in 1024..65535")
    target = (args.host, args.port)
    signal.signal(signal.SIGINT, lambda *_: STOP.set())
    signal.signal(signal.SIGTERM, lambda *_: STOP.set())
    if args.simulate:
        recorder = PacketRecorder(args.record) if args.record else None
        try:
            simulate(target, args.token, recorder=recorder)
        finally:
            if recorder:
                recorder.close()
        return
    available = enumerate_devices()
    bindings = {}
    for item in args.bind:
        role, separator, identifier = item.partition("=")
        if not separator or role not in ROLES or identifier not in available:
            p.error(f"Invalid --bind {item!r}; use --list to obtain IDs")
        if role in bindings or identifier in bindings.values():
            p.error("Each role and each Joy-Con may only be bound once")
        if not available[identifier]["identityStable"] and not args.allow_unstable_id:
            p.error(f"{identifier} has no verified serial/MAC; explicit --allow-unstable-id is diagnostic only")
        bindings[role] = identifier
    if not bindings:
        p.error("Provide at least one --bind ROLE=ID (use all four for full kit)")
    recorder = PacketRecorder(args.record) if args.record else None
    workers = [threading.Thread(target=run_controller, args=(identifier, available[identifier], role, target, args.token, recorder), daemon=True) for role, identifier in bindings.items()]
    for worker in workers:
        worker.start()
    try:
        STOP.wait()
    finally:
        for worker in workers:
            worker.join(timeout=2)
        if recorder:
            recorder.close()


if __name__ == "__main__":
    main()
