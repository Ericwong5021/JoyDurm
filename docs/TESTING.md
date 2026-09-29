# Validation and real-device acceptance

Build checks:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
python3 -m unittest discover -s tools/bridge -v
```

CI publishes an installable debug APK plus test/Lint reports. It does not claim to
test Bluetooth hardware or AR tracking. A successful APK build is not hardware
certification.

Pushing a `v*` tag creates a GitHub pre-release after the checks pass. Without
release secrets it contains the debug APK. Configure `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` to also publish a production
signed APK. Signing runs in a separate job that does not execute repository
code; pull requests and manual workflow runs cannot sign or publish a release.
The tag must point to reviewed code. Protect release tags and restrict who can
push them. A debug APK uses an ephemeral CI debug key, so later CI debug APKs
may require uninstalling the previous installation (which deletes its data).

## Phone acceptance

| Test | Required result |
|---|---|
| Offline start | 3D kit visible; touch pads play sound without network |
| Role binding | Four unique device IDs assigned, no duplicate role ownership |
| Direct IMU detection | Separate status for pairing vs live motion availability |
| Stationary calibration | Stable 3s accepted; movement rejected; bias persists |
| Direction mapping | Point/bind each target; intended downstroke selects it |
| Rebound rejection | One event per swing; upward rebound does not trigger |
| Rapid alternating hits | Left and right hand strokes retain independence |
| Kick | One event per stomp, quiet foot produces no events |
| Hi-hat | Flat closed, toe lifted open; closure produces chick and chokes open sample |
| Disconnection | Input becomes inactive; no old stroke fires on reconnect |
| AR plane placement | Kit remains fixed as camera moves; re-placement detaches old anchor |
| AR unsupported | Clear message and continued ordinary 3D playing |
| GLB import | Eight named roots required; per-piece animation still works |
| Custom WAV | Valid PCM WAV loads; invalid/oversized files fail visibly |
| Lifecycle | Pausing closes inputs; resuming reopens enabled bridge |
| Latency | Measure controller motion → audible output, not only audio buffer delay |

For first tests use phone speaker or wired headphones. Bluetooth headphones can
dominate end-to-end latency. Record high-frame-rate video with the controller and
phone audio together; compare physical strike to audible onset. Repeat with all
four controllers active and AR running.

## Explicit hardware limitations

Original Joy-Con has no absolute position measurement and no magnetometer.
Yaw drifts; periodically recenter. The current complementary orientation filter
is intended for calibrated gesture sectors, not optical-quality six-DoF tracking.
Large compound rotations may need re-centering or per-target re-binding.

Direct Android motion works only if the phone's controller driver exposes IMU
sensors (Android 12+) or if the OS grants this application raw HID access.
Most stock phones may expose buttons only. The supplied desktop HIDAPI bridge is
the supported alternative; this repository does not claim universal no-root
direct Joy-Con IMU access. Device models tested with real Joy-Cons: **none yet**.

ARCore's runtime is proprietary even though the application and rendering stack
are open source. Phones need the appropriate Google Play Services for AR runtime.
The current release uses ARCore camera mode; a separate CameraX overlay mode is
not implemented. An unsupported phone uses 3D mode.
