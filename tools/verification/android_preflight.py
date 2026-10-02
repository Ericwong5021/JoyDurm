#!/usr/bin/env python3
"""Reject a broken emulator before interpreting app tests or composited pixels."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time


def inspect_state(anr, windows, boot, home):
    # lastanr survives dismissal. A pre-existing ANR must fail this VM, even if
    # its dialog disappeared; never retry or dismiss it to manufacture a pass.
    if re.search(r'ANR time:|Application Not Responding|isn.t responding', anr + windows, re.I):
        raise ValueError('Preflight failed: system ANR preceded app instrumentation')
    if not re.search(r'no ANR has occurred', anr, re.I):
        raise ValueError('Preflight failed: Android did not confirm a clean ANR history')
    focus = re.search(r'mCurrentFocus=(.*)', windows)
    focused_app = re.search(r'mFocusedApp=(.*)', windows)
    return (boot.strip() == '1' and focus is not None and focused_app is not None
            and home in focus.group(1) and home in focused_app.group(1))


def preflight(output, checks=30, interval=2, stable_checks=6):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    status = {'passed': False, 'stableSecondsRequired': (stable_checks - 1) * interval,
              'maximumChecks': checks, 'samples': []}

    def capture(name, command, binary=False):
        result = subprocess.run(['adb'] + command, capture_output=True, timeout=15)
        (output / name).write_bytes(result.stdout)
        (output / (name + '.stderr')).write_bytes(result.stderr)
        if result.returncode != 0:
            raise ValueError('Preflight adb capture failed: ' + name)
        return result.stdout if binary else result.stdout.decode('utf-8', errors='replace')

    try:
        resolved = capture('preflight-home.txt', ['shell', 'cmd', 'package', 'resolve-activity',
                           '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME'])
        components = [line.strip() for line in resolved.splitlines() if '/' in line]
        if len(components) != 1:
            raise ValueError('Preflight cannot resolve one launcher component')
        home = components[0].split('/')[0]
        status['homePackage'] = home
        consecutive = 0
        for index in range(checks):
            anr = capture('preflight-last-anr.txt', ['shell', 'dumpsys', 'activity', 'lastanr'])
            windows = capture('preflight-windows.txt', ['shell', 'dumpsys', 'window', 'windows'])
            boot = capture('preflight-boot.txt', ['shell', 'getprop', 'sys.boot_completed'])
            ready = inspect_state(anr, windows, boot, home)
            consecutive = consecutive + 1 if ready else 0
            status['samples'].append({'index': index, 'homeFocused': ready, 'consecutive': consecutive})
            if consecutive >= stable_checks:
                status['passed'] = True
                break
            if index + 1 < checks:
                time.sleep(interval)
        if not status['passed']:
            raise ValueError('Preflight failed: launcher never remained focused and ANR-free for 10 seconds')
    except (ValueError, subprocess.TimeoutExpired) as error:
        status['error'] = str(error)
        raise
    finally:
        # Read-only evidence; no force-stop, taps, overlay dismissal or app warm-up.
        for name, command, binary in (
            ('preflight-logcat.txt', ['logcat', '-d', '-v', 'threadtime'], False),
            ('preflight-screen.png', ['exec-out', 'screencap', '-p'], True),
        ):
            try:
                capture(name, command, binary)
            except (ValueError, subprocess.TimeoutExpired) as error:
                status.setdefault('captureErrors', []).append(str(error))
                status['passed'] = False
        (output / 'preflight-status.json').write_text(json.dumps(status, indent=2) + '\n')
    if not status['passed']:
        raise ValueError('Preflight evidence capture failed')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path('installation'))
    preflight(parser.parse_args().output)
