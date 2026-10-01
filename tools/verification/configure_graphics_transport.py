#!/usr/bin/env python3
"""Configure only the dedicated CI AVD's graphics transport for a controlled comparison."""
import os
from pathlib import Path


def configure():
    avd = Path(os.environ['ANDROID_AVD_HOME']) / 'test.avd' / 'config.ini'
    before = avd.read_text()
    evidence = Path('installation')
    evidence.mkdir(parents=True, exist_ok=True)
    (evidence / 'avd-transport-before.ini').write_text(before)
    lines = [line for line in before.splitlines()
             if line.split('=', 1)[0].strip() != 'hw.gltransport']
    avd.write_text('\n'.join(lines + ['hw.gltransport=asg']) + '\n')
    (evidence / 'avd-transport-after.ini').write_text(avd.read_text())


if __name__ == '__main__':
    configure()
