import math
import struct
import unittest
from joydurm_bridge import Scale, parse_report, subcommand, send_command


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


if __name__ == "__main__":
    unittest.main()
