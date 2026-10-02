# Android CI readiness and suite budget

The emulator must report no ANR since boot and keep its resolved HOME package
focused for at least ten seconds before instrumentation. A prior ANR fails the
job immediately, even when its dialog is no longer visible. Preflight retains
the system ANR history, windows, logcat and screenshot. It does not dismiss
system windows, restart the launcher or warm up JoyDrum.

Use the two-core guest on the existing free Ubuntu runner, API35 Google APIs,
Mesa llvmpipe and ASG. The four-core experiment (run36972198190) had a Launcher
ANR before instrumentation and is not an accepted configuration.

The complete 15-test runner receives a 360-second infrastructure budget. This
does not change any product assertion or individual 8-second UI/render wait,
pixel threshold, clock freshness, motion delay, or the exact 15-test XML gate.
Timeout, runner failure, missing evidence, failures and leaked Activities all
remain failures. No automatic retry hides a failed attempt.

Measured evidence for this budget:

* Original 11-test rendering baseline run36925448649 completed in 111.451s.
* Two-core run36971236291 completed 13 passing tests before the 240s cutoff.
  Four newly added Bluetooth tests consumed 24.517 + 9.922 + 40.844 + 47.543 =
  122.826s, including UI navigation, selection, public pairing boundaries and
  actual UDP association. The remaining original tests and evidence streaming
  no longer fit reliably in the original budget.
* The new 360s budget covers the observed 122.826s + original 111.451s plus
  startup, recreation and evidence transfer headroom. It remains within the
  existing 25-minute free CI job limit. The exact source and merge SHA must
  still finish every required check before Linux acceptance can be claimed.
