#!/usr/bin/env python3
"""am instrument does not reliably exit nonzero on test failure. Check its JUnit XML."""
import argparse
import xml.etree.ElementTree as ET

EXPECTED = {
    ('ai.joydurm.StageFlowIntegrationTest', 'setupBackNavigationAndMissingImuRemainHonestBeforeTouchPlaying'),
    ('ai.joydurm.StageFlowIntegrationTest', 'welcomeCanSkipToSoundCheckAndReturnToTouchPlaying'),
    ('ai.joydurm.StageFlowIntegrationTest', 'denyingCameraKeepsPlayPageAndNativeTouchPadsAvailable'),
    ('ai.joydurm.StageFlowIntegrationTest', 'devicesKitAndSettingsPreserveAudioControlsAcrossRecreation'),
    ('ai.joydurm.ControllerHubSocketIntegrationTest', 'twoBurstedBatchesDeliverAllSixSourceFramesAndDelayedMotionIsRejected'),
    ('ai.joydurm.MainActivitySmokeTest', 'launchWithoutCameraPermissionAndTouchPadRemainsPlayable'),
    ('ai.joydurm.MainActivitySmokeTest', 'ordinary3DKitIsVisibleInCompositedScreen'),
    ('ai.joydurm.MainActivitySmokeTest', 'deniedArCameraPermissionKeepsOrdinary3DAndTouchPadsAvailable'),
    ('ai.joydurm.MainActivitySmokeTest', 'pauseResumeAndRecreationRetainSettingsAndInputUi'),
    ('ai.joydurm.SettingsNeutralMigrationTest', 'legacyNonzeroNeutralIsDiscardedAndNewSaveHasNoSessionOrigin'),
    ('ai.joydurm.ControllerHubLifecycleIntegrationTest', 'restartCyclesAndRealUdpClockExchangeDoNotDeadlockOrClaimHardware'),
}


def verify(path, run_id):
    suite = ET.parse(path).getroot()
    if not run_id or suite.get('runId') != run_id:
        raise ValueError('Stale or unidentified instrumentation result: run ID differs')
    cases = suite.findall('testcase')
    if {(case.get('classname'), case.get('name')) for case in cases} != EXPECTED:
        raise ValueError('Incomplete smoke/migration instrumentation result')
    if int(suite.get('tests', '0')) != len(EXPECTED) or any(int(suite.get(key, '0')) for key in ('failures', 'errors', 'skipped')):
        raise ValueError('Instrumentation did not pass every required test')
    if any(case.find(element) is not None for case in cases for element in ('failure', 'error', 'skipped')):
        raise ValueError('Instrumentation contains a failed or skipped case')
    print(f'PASS: {len(cases)} Android smoke/migration tests')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('xml')
    parser.add_argument('--run-id', required=True, help='Unique ID passed to the runner as joydurmRunId')
    args = parser.parse_args()
    verify(args.xml, args.run_id)
