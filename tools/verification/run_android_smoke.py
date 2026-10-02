#!/usr/bin/env python3
"""Bound the real Android runner and retain diagnostics even when it stalls."""
import argparse
import json
from pathlib import Path
import subprocess
import shutil

from verify_instrumentation import EXPECTED, verify
from streamed_evidence import extract_streamed_evidence


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
               # ActivityScenario scopes own lifecycle, including the class-scoped
               # picker fixture. The default finisher kills that fixture at testStarted.
               # SmokeXmlListener rejects any Activity left alive at suite completion.
               '-e', 'waitForActivitiesToComplete', 'false',
               '-e', 'listener', 'ai.joydurm.SmokeXmlListener',
               'ai.joydurm.test/androidx.test.runner.AndroidJUnitRunner']
    status = dict(runId=run_id, timeoutSeconds=timeout_seconds, timedOut=False,
                  runnerExitCode=None, captures={}, diagnosticCaptures={})
    # Stream onto the host before the Activity starts. A disconnected/crashed emulator
    # cannot answer a post-run logcat request, but bytes already received survive.
    with (output / 'instrumentation.txt').open('wb') as stream, (output / 'logcat-live.txt').open('wb') as live:
        collector = subprocess.Popen(['adb', 'logcat', '-v', 'threadtime'], stdout=live,
                                     stderr=subprocess.STDOUT)
        try:
            try:
                result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT,
                                        timeout=timeout_seconds)
                status['runnerExitCode'] = result.returncode
            except subprocess.TimeoutExpired:
                status['timedOut'] = True
        finally:
            # This is our local logcat client only; do not stop the adb server or VM.
            if collector.poll() is None:
                collector.terminate()
            try:
                status['liveLogcatExitCode'] = collector.wait(timeout=5)
            except subprocess.TimeoutExpired:
                collector.kill()
                status['liveLogcatExitCode'] = collector.wait(timeout=5)
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
    recovered = set()
    try:
        recovered = extract_streamed_evidence(output / 'instrumentation.txt', output, run_id)
    except (ValueError, KeyError, TypeError) as error:
        status['streamEvidenceError'] = str(error)
    # Complete evidence uses the existing runner channel and retained live Logcat.
    # No post-run adb connection is required to recover a file already hashed there.
    captures = []
    if recovered and (output / 'logcat-live.txt').stat().st_size:
        shutil.copyfile(output / 'logcat-live.txt', output / 'logcat.txt')
        status['captures']['logcat.txt'] = dict(exitCode=0, bytes=(output / 'logcat.txt').stat().st_size,
                                               source='live-logcat')
    else:
        captures.append(('logcat.txt', ['adb', 'logcat', '-d']))
    for name in names:
        if name in recovered:
            status['captures'][name] = dict(exitCode=0, bytes=(output / name).stat().st_size,
                                           source='instrumentation-stream-sha256')
        else:
            captures.append((name, ['adb', 'exec-out', 'run-as', 'ai.joydurm', 'cat', 'files/test-reports/' + name]))
    # Each fallback diagnostic command is bounded too; one missing file cannot hide Logcat.
    for name, command in captures:
        try:
            with (output / name).open('wb') as stream:
                result = subprocess.run(command, stdout=stream, stderr=subprocess.PIPE, timeout=15)
            status['captures'][name] = dict(exitCode=result.returncode,
                                            bytes=(output / name).stat().st_size)
            (output / (name + '.stderr')).write_bytes(result.stderr or b'')
        except subprocess.TimeoutExpired:
            status['captures'][name] = dict(timedOut=True)
    # Optional diagnostics never turn an incomplete run into a passing run. The test-only
    # heartbeat writes before Android kills an unresponsive instrumentation process.
    diagnostics = [
        ('thread-stalls.txt', ['adb', 'exec-out', 'run-as', 'ai.joydurm', 'cat',
                              'files/test-reports/thread-stalls.txt']),
        ('last-anr.txt', ['adb', 'shell', 'dumpsys', 'activity', 'lastanr']),
        ('anr-traces.txt', ['adb', 'shell', "su 0 sh -c 'cat /data/anr/*'"]),
    ]
    # A timeout precedes testRunFinished, so its evidence stream is absent.
    # Preserve the already-written live-surface probes via bounded fallback;
    # optional diagnostics cannot satisfy any required acceptance gate.
    for name in ('ordinary-3d-render-probe.json', 'ui-play-render-probe.json',
                 'ui-welcome-render-probe.json', 'ui-failed-play.png', 'ui-failed-welcome.png'):
        diagnostics.append((name, ['adb', 'exec-out', 'run-as', 'ai.joydurm', 'cat',
                                  'files/test-reports/' + name]))
    if Path('/proc/meminfo').is_file():
        (output / 'host-memory.txt').write_text(Path('/proc/meminfo').read_text())
        # Hosted Linux CI permits this read-only command without a password. Failure
        # remains optional; retain VM/OOM evidence independently of adb availability.
        diagnostics.append(('host-kernel.txt', ['sudo', '-n', 'dmesg', '--time-format=iso']))
    for name, command in diagnostics:
        if name in recovered:
            status['diagnosticCaptures'][name] = dict(exitCode=0, bytes=(output / name).stat().st_size,
                                                     source='instrumentation-stream-sha256')
            continue
        try:
            with (output / name).open('wb') as stream:
                result = subprocess.run(command, stdout=stream, stderr=subprocess.PIPE, timeout=15)
            status['diagnosticCaptures'][name] = dict(exitCode=result.returncode,
                                                     bytes=(output / name).stat().st_size)
            (output / (name + '.stderr')).write_bytes(result.stderr or b'')
        except subprocess.TimeoutExpired:
            status['diagnosticCaptures'][name] = dict(timedOut=True)
    if status['timedOut']:
        try:
            # Killing the local adb client does not stop a remote instrumentation process.
            subprocess.run(['adb', 'shell', 'am', 'force-stop', 'ai.joydurm'], timeout=15)
        except subprocess.TimeoutExpired:
            pass
    (output / 'runner-status.json').write_text(json.dumps(status, indent=2) + '\n')
    if status['timedOut'] or status['runnerExitCode'] != 0:
        raise ValueError('Android runner timed out or failed; diagnostics were retained')
    if status.get('streamEvidenceError'):
        raise ValueError('Invalid streamed Android evidence: ' + status['streamEvidenceError'])
    if f'OK ({len(EXPECTED)} tests)' not in (output / 'instrumentation.txt').read_text():
        raise ValueError('Android runner did not finish all required tests')
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
