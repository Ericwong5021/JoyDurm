import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from android_preflight import inspect_state, preflight


class AndroidPreflightTest(unittest.TestCase):
    clean = 'ACTIVITY MANAGER LAST ANR\n  <no ANR has occurred since boot>'
    windows = 'mCurrentFocus=Window{home/com.test.Home}\nmFocusedApp=ActivityRecord{home/com.test.Home}'

    def test_prior_anr_fails_even_when_home_is_focused(self):
        with self.assertRaisesRegex(ValueError, 'ANR preceded'):
            inspect_state('ANR time: Oct 2\nReason: Input dispatching timed out', self.windows, '1', 'home')

    def test_unknown_history_and_system_overlay_cannot_pass(self):
        with self.assertRaisesRegex(ValueError, 'clean ANR history'):
            inspect_state('', self.windows, '1', 'home')
        self.assertFalse(inspect_state(self.clean, 'mCurrentFocus=Window{system dialog}\nmFocusedApp=null', '1', 'home'))
        self.assertFalse(inspect_state(self.clean, self.windows, '0', 'home'))

    def test_anr_failure_retains_evidence_without_touching_device(self):
        calls = []
        def adb(command, **kwargs):
            calls.append(command)
            data = b'home/com.test.Home\n' if 'resolve-activity' in command else b'ANR time: Oct 2\n'
            return subprocess.CompletedProcess(command, 0, data, b'')
        with tempfile.TemporaryDirectory() as directory, patch('android_preflight.subprocess.run', side_effect=adb):
            with self.assertRaisesRegex(ValueError, 'ANR preceded'):
                preflight(directory)
            status = json.loads((Path(directory) / 'preflight-status.json').read_text())
            self.assertFalse(status['passed'])
            self.assertTrue((Path(directory) / 'preflight-screen.png').exists())
            self.assertFalse(any('input' in call or 'force-stop' in call or 'instrument' in call for call in calls))

    def test_clean_home_requires_sustained_readiness(self):
        def adb(command, **kwargs):
            data = ('home/com.test.Home' if 'resolve-activity' in command else
                    self.clean if 'lastanr' in command else
                    self.windows if 'displays' in command else '1')
            return subprocess.CompletedProcess(command, 0, data.encode(), b'')
        with tempfile.TemporaryDirectory() as directory, patch('android_preflight.subprocess.run', side_effect=adb), patch('android_preflight.time.sleep') as sleep:
            preflight(directory)
            status = json.loads((Path(directory) / 'preflight-status.json').read_text())
            self.assertTrue(status['passed'])
            self.assertEqual(6, len(status['samples']))
            self.assertEqual(5, sleep.call_count)
