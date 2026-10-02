#!/usr/bin/env python3
"""Record the exact installed APK and emulator/device, without implying hardware acceptance."""
import argparse
import json
from pathlib import Path
import re
import subprocess


def adb(*args):
    return subprocess.check_output(['adb', *args], text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--artifact-report', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    artifact = json.loads(args.artifact_report.read_text())
    package = adb('shell', 'dumpsys', 'package', 'ai.joydurm')
    code = re.search(r'\bversionCode=(\d+)', package)
    name = re.search(r'\bversionName=([^\s]+)', package)
    if not code or not name or int(code[1]) != artifact['versionCode'] or name[1] != artifact['versionName']:
        raise ValueError('Installed version does not match the exact artifact')
    evidence = dict(artifact=artifact, serial=adb('get-serialno'), model=adb('shell', 'getprop', 'ro.product.model'),
        api=adb('shell', 'getprop', 'ro.build.version.sdk'), abi=adb('shell', 'getprop', 'ro.product.cpu.abi'),
        fingerprint=adb('shell', 'getprop', 'ro.build.fingerprint'), installation='adb install succeeded before evidence capture',
        sameVersionReinstall='adb install -r of the same artifact; this does not prove version upgrade',
        versionUpgrade='PENDING: needs previous version and stable signing identity',
        hardwareAcceptance='HARDWARE_PENDING: real four Joy-Con, AR tracking and acoustic latency not tested by emulator')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, indent=2) + '\n')


if __name__ == '__main__':
    main()
