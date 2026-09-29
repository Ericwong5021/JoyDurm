#!/usr/bin/env python3
"""Original Switch Joy-Con HID -> JoyDurm Android UDP bridge. SI units; no cloud."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import signal
import socket
import struct
import sys
import threading
import time
from dataclasses import dataclass

ROLES = ("LEFT_HAND", "RIGHT_HAND", "LEFT_FOOT", "RIGHT_FOOT")
PRODUCTS = {0x2006: "Joy-Con L", 0x2007: "Joy-Con R"}
STOP = threading.Event()


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
        result.append({"a": a, "g": g})
    return result


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


def device_id(path):
    return hashlib.sha256(path if isinstance(path, bytes) else path.encode()).hexdigest()[:12]


def enumerate_devices():
    import hid
    # Windows exposes multiple interfaces. Keep only the standard Joy-Con HID interface.
    result = {}
    for d in hid.enumerate(0x057E):
        if d["product_id"] in PRODUCTS:
            identifier = device_id(d["path"])
            result[identifier] = d
    return result


def run_controller(identifier, info, role, target, token):
    import hid
    seq = 0
    while not STOP.is_set():
        controller = hid.device()
        channel = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            controller.open_path(info["path"])
            counter = 0

            def command(cmd, data=b""):
                nonlocal counter
                result = send_command(controller, counter, cmd, data)
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
            print(f"{role}: {PRODUCTS[info['product_id']]} connected ({identifier})", flush=True)
            last = time.monotonic()
            last_timer = None
            while not STOP.is_set():
                report = bytes(controller.read(128, 250))
                if report[:1] == b"\xa1":
                    report = report[1:]
                data = parse_report(report, scale)
                if not data:
                    if time.monotonic() - last > 3:
                        raise TimeoutError("No IMU report for 3 seconds")
                    continue
                now = time.monotonic()
                timer = report[1]
                if last_timer is not None and now-last < 0.25:
                    delta = (timer-last_timer) & 255
                    if delta == 0 or delta > 127:
                        continue
                last_timer = timer
                last = now
                seq += 1
                packet = {"v": 1, "token": token, "device": identifier, "name": PRODUCTS[info["product_id"]], "role": role, "seq": seq, "samples": data}
                channel.sendto(json.dumps(packet, separators=(",", ":"), allow_nan=False).encode(), target)
        except Exception as e:
            print(f"{role}: {e}; retrying in 2 seconds", file=sys.stderr)
        finally:
            controller.close()
            channel.close()
        STOP.wait(2)


def simulate(target, token):
    """Explicit diagnostic input; never presented as connected hardware."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    seq = 0
    try:
        while not STOP.is_set():
            seq += 1
            t = time.monotonic()
            for index, role in enumerate(ROLES):
                pitch = 0.2 * (1 + math.sin(t * 1.5)) if role == "LEFT_FOOT" else 0
                a = [0, -9.80665 * math.sin(pitch), 9.80665 * math.cos(pitch)]
                g = [max(0, math.sin(t * 7 + index)) * 7, 0, 0] if "HAND" in role else [0, 0, 0]
                if role == "RIGHT_FOOT":
                    a[2] += max(0, math.sin(t * 7)) * 8
                packet = {"v": 1, "token": token, "device": f"SIMULATOR-{role}", "name": "Diagnostic simulator", "role": role, "seq": seq, "samples": [{"a": a, "g": g}]}
                s.sendto(json.dumps(packet).encode(), target)
            STOP.wait(0.015)
    finally:
        s.close()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--list", action="store_true", help="List paired original Switch Joy-Cons and stable IDs")
    p.add_argument("--host", help="Android phone LAN IPv4 address")
    p.add_argument("--port", type=int, default=18185)
    p.add_argument("--token", help="Token copied from Android App, at least 16 characters")
    p.add_argument("--bind", action="append", default=[], metavar="ROLE=ID")
    p.add_argument("--simulate", action="store_true", help="Send diagnostic motion without hardware")
    args = p.parse_args()
    if args.list:
        for identifier, d in enumerate_devices().items():
            print(f"{identifier}  {PRODUCTS[d['product_id']]}  {d.get('serial_number', '')}")
        return
    if not args.host or not args.token or len(args.token) < 16 or not 1024 <= args.port <= 65535:
        p.error("Provide --host, --token (16+ characters), and a port in 1024..65535")
    target = (args.host, args.port)
    signal.signal(signal.SIGINT, lambda *_: STOP.set())
    signal.signal(signal.SIGTERM, lambda *_: STOP.set())
    if args.simulate:
        simulate(target)
        return
    available = enumerate_devices()
    bindings = {}
    for item in args.bind:
        role, separator, identifier = item.partition("=")
        if not separator or role not in ROLES or identifier not in available:
            p.error(f"Invalid --bind {item!r}; use --list to obtain IDs")
        if role in bindings or identifier in bindings.values():
            p.error("Each role and each Joy-Con may only be bound once")
        bindings[role] = identifier
    if not bindings:
        p.error("Provide at least one --bind ROLE=ID (use all four for full kit)")
    workers = [threading.Thread(target=run_controller, args=(identifier, available[identifier], role, target, args.token), daemon=True) for role, identifier in bindings.items()]
    for worker in workers:
        worker.start()
    try:
        STOP.wait()
    finally:
        for worker in workers:
            worker.join(timeout=2)


if __name__ == "__main__":
    main()
