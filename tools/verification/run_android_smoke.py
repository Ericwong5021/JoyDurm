#!/usr/bin/env python3
"""Bound the real Android runner and retain diagnostics even when it stalls."""
import argparse
import json
from pathlib import Path
import subprocess

from verify_instrumentation import verify


def run_smoke(output, run_id, timeout_seconds=240):
    if not run_id or timeout_seconds <= 0:
        raise ValueError('A unique run ID and positive timeout are required')
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    names = ('smoke-tests.xml', 'ordinary-3d-screen.png', 'ordinary-3d-visibility.json')
    for name in names:
        (output / name).unlink(missing_ok=True)
    subprocess.run(['adb', 'shell', 'run-as', 'ai.joydurm', 'rm', '-f',
                    'files/test-reports/smoke-tests.xml'], check=True, timeout=15)
    subprocess.run(['adb', 'logcat', '-c'], check=True, timeout=15)
    command = ['adb', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'joydurmRunId', run_id,
               '-e', 'listener', 'ai.joydurm.SmokeXmlListener',
               'ai.joydurm.test/androidx.test.runner.AndroidJUnitRunner']
    status = dict(runId=run_id, timeoutSeconds=timeout_seconds, timedOut=False,
                  runnerExitCode=None, captures={})
    with (output / 'instrumentation.txt').open('wb') as stream:
        try:
            result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT,
                                    timeout=timeout_seconds)
            status['runnerExitCode'] = result.returncode
        except subprocess.TimeoutExpired:
            status['timedOut'] = True
    if status['timedOut']:
        try:
            process = subprocess.run(['adb', 'shell', 'pidof', 'ai.joydurm'],
                                     capture_output=True, text=True, timeout=15)
            pid = (process.stdout or '').strip()
            if pid.isdecimal():
                # Request a Java thread dump from this app only, before collecting Logcat.
                dump = subprocess.run(['adb', 'shell', 'run-as', 'ai.joydurm',
                                       'kill', '-3', pid], timeout=15)
                status['threadDumpRequested'] = dump.returncode == 0
        except subprocess.TimeoutExpired:
            status['threadDumpRequested'] = False
    # Each diagnostic command is bounded too; one missing file cannot hide Logcat.
    captures = [('logcat.txt', ['adb', 'logcat', '-d'])] + [
        (name, ['adb', 'exec-out', 'run-as', 'ai.joydurm', 'cat', 'files/test-reports/' + name])
        for name in names]
    for name, command in captures:
        try:
            with (output / name).open('wb') as stream:
                result = subprocess.run(command, stdout=stream, stderr=subprocess.PIPE, timeout=15)
            status['captures'][name] = dict(exitCode=result.returncode,
                                            bytes=(output / name).stat().st_size)
        except subprocess.TimeoutExpired:
            status['captures'][name] = dict(timedOut=True)
    if status['timedOut']:
        try:
            # Killing the local adb client does not stop a remote instrumentation process.
            subprocess.run(['adb', 'shell', 'am', 'force-stop', 'ai.joydurm'], timeout=15)
        except subprocess.TimeoutExpired:
            pass
    (output / 'runner-status.json').write_text(json.dumps(status, indent=2) + '\n')
    if status['timedOut'] or status['runnerExitCode'] != 0:
        raise ValueError('Android runner timed out or failed; diagnostics were retained')
    if 'OK (7 tests)' not in (output / 'instrumentation.txt').read_text():
        raise ValueError('Android runner did not finish all seven tests')
    if any(item.get('exitCode') != 0 or not item.get('bytes')
           for item in status['captures'].values()):
        raise ValueError('Required Android evidence could not be collected')
    verify(output / 'smoke-tests.xml', run_id)
    metrics = json.loads((output / 'ordinary-3d-visibility.json').read_text())
    if metrics.get('runId') != run_id or metrics.get('visible') is not True:
        raise ValueError('Displayed-screen evidence does not identify this passing run')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--output', type=Path, default=Path('installation'))
    parser.add_argument('--timeout-seconds', type=float, default=240)
    args = parser.parse_args()
    run_smoke(args.output, args.run_id, args.timeout_seconds)
