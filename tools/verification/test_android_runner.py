import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from unittest.mock import Mock

from run_android_smoke import run_smoke


class AndroidRunnerFailureTest(unittest.TestCase):
    def collector(self, command, **kwargs):
        kwargs['stdout'].write(b'live native main-thread stack before adb disconnect\n')
        return Mock(poll=Mock(return_value=None), wait=Mock(return_value=-15))

    def test_stalled_runner_still_collects_logs_and_cannot_pass(self):
        calls = []
        def adb(command, **kwargs):
            calls.append(command)
            if 'instrument' in command:
                raise subprocess.TimeoutExpired(command, 240)
            if 'stdout' in kwargs:
                kwargs['stdout'].write(b'graphics driver stalled\n')
            return subprocess.CompletedProcess(command, 0)
        with tempfile.TemporaryDirectory() as directory, patch('run_android_smoke.subprocess.run', side_effect=adb), patch('run_android_smoke.subprocess.Popen', side_effect=self.collector):
            with self.assertRaisesRegex(ValueError, 'timed out'):
                run_smoke(directory, 'new-run')
            status = json.loads((Path(directory) / 'runner-status.json').read_text())
            self.assertTrue(status['timedOut'])
            self.assertIn('graphics driver stalled', (Path(directory) / 'logcat.txt').read_text())
            self.assertTrue(any('force-stop' in command for command in calls))
            self.assertEqual(4, len(status['captures']))
            self.assertIn('thread-stalls.txt', status['diagnosticCaptures'])
            self.assertIn('ordinary-3d-render-probe.json', status['diagnosticCaptures'])
            self.assertIn('ui-play-render-probe.json', status['diagnosticCaptures'])

    def test_zero_exit_without_finished_runner_is_rejected_and_evidence_saved(self):
        def adb(command, **kwargs):
            if 'stdout' in kwargs:
                kwargs['stdout'].write(b'Process crashed before testRunFinished\n')
            return subprocess.CompletedProcess(command, 0)
        with tempfile.TemporaryDirectory() as directory, patch('run_android_smoke.subprocess.run', side_effect=adb), patch('run_android_smoke.subprocess.Popen', side_effect=self.collector):
            with self.assertRaisesRegex(ValueError, 'did not finish'):
                run_smoke(directory, 'new-run')
            status = json.loads((Path(directory) / 'runner-status.json').read_text())
            self.assertEqual(0, status['runnerExitCode'])
            self.assertEqual(4, len(status['captures']))
            self.assertTrue((Path(directory) / 'logcat.txt').stat().st_size)
            self.assertTrue((Path(directory) / 'thread-stalls.txt').stat().st_size)
            self.assertIn('last-anr.txt', status['diagnosticCaptures'])

    def test_emulator_disconnect_retains_live_stack_and_is_rejected(self):
        def adb(command, **kwargs):
            if 'logcat' in command and '-d' in command:
                raise subprocess.TimeoutExpired(command, 15)
            return subprocess.CompletedProcess(command, 255 if 'instrument' in command else 0)
        with tempfile.TemporaryDirectory() as directory, patch('run_android_smoke.subprocess.run', side_effect=adb), patch('run_android_smoke.subprocess.Popen', side_effect=self.collector):
            with self.assertRaisesRegex(ValueError, 'failed'):
                run_smoke(directory, 'disconnected-run')
            self.assertIn('native main-thread stack', (Path(directory) / 'logcat-live.txt').read_text())
            status = json.loads((Path(directory) / 'runner-status.json').read_text())
            self.assertEqual(255, status['runnerExitCode'])
            self.assertTrue(status['captures']['logcat.txt']['timedOut'])
