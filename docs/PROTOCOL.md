# JoyDurm input protocol v2

Android receives UDP on port `18185` (configurable). Enable the listener and copy
the generated token to the desktop bridge. No discovery, internet service or
cloud account is required. UDP is not encrypted; use a trusted local network.
The token prevents accidental injection and is checked for motion and sync.

v2 replaces v1. Upgrade the Android app and bridge together. v1 has no source
clock or connection session and is rejected; it cannot be made safe by assigning
arrival time to its samples. Old path-hash bindings need explicit reassignment.

## Motion packet

```json
{
  "v": 2,
  "token": "token-copied-from-app",
  "device": "joycon-2006-physical-identity-hash",
  "name": "Joy-Con L",
  "role": "LEFT_HAND",
  "identityStable": true,
  "identitySource": "mac",
  "vendorId": 1406,
  "productId": 8198,
  "sessionId": "4183c590-7e7d-4f4b-9d9b-0171a2995534",
  "seq": 1,
  "sourceReadNs": 1000000000,
  "timer": 254,
  "samples": [
    {"sourceTimeNs": 990000000, "ax": 0.0, "ay": 0.0, "az": 9.80665, "gx": 0.0, "gy": 0.0, "gz": 0.0},
    {"sourceTimeNs": 995000000, "ax": 0.0, "ay": 0.0, "az": 9.80665, "gx": 0.0, "gy": 0.0, "gz": 0.0},
    {"sourceTimeNs": 1000000000, "ax": 0.0, "ay": 0.0, "az": 9.80665, "gx": 0.0, "gy": 0.0, "gz": 0.0}
  ]
}
```

- Roles are `LEFT_HAND`, `RIGHT_HAND`, `LEFT_FOOT`, `RIGHT_FOOT`. One controller
  has one role. Explicit app assignment takes precedence over a role hint.
- `device` identifies a physical controller only when HID supplies serial/MAC
  evidence. `identitySource` is `mac` or `serial`; the value is hashed with VID/PID
  rather than transmitting the raw serial/MAC. MAC punctuation/case is normalized.
  A different transport exposing a different serial may need reassignment;
  there is no guessed identity merge. `vendorId`/`productId` expose the actual
  Nintendo VID/PID for capability diagnostics.
- Without serial/MAC, `--list` displays an `UNVERIFIED-...` endpoint identifier
  with `identityStable=false`, `identitySource=endpoint-only`. The bridge refuses
  to bind it unless `--allow-unstable-id` is explicitly given. The app scopes its
  ID to `sessionId`, ignores automatic role hints and requires assignment for
  each session. Endpoint paths are never presented as physical identity.
- `sessionId` is a fresh UUID after every HID reopen, source clock discontinuity
  or process restart. `seq` starts at 1 within that device/session and increases
  for each published motion packet. Silence alone does not authorize sequence
  rollback. A receiver retires old sessions and must not return to them when an
  old datagram arrives late. Reconnect requires a new orientation epoch/recenter.
- `sourceReadNs` is Python `time.monotonic_ns()` immediately after the HID read.
  `sourceTimeNs` estimates sample acquisition time on that same source clock.
  Neither is Android receive time or wall-clock/Unix time.
- `timer` is the original 8-bit report byte. The bridge unwraps its 5 ms ticks;
  the three original `0x30` samples are 5 ms apart. Duplicate/backward reports
  are rejected. Overlapping reports publish only new samples. A read gap over
  600 ms makes the half-cycle ambiguity unsafe and starts a new session.
- The first 100 ms of advancing reports establish the least delayed read/timer
  anchor and publish no motion. After warmup the anchor is fixed: delayed HID
  reads retain old sample times. A timer more than 5 ms ahead of source time
  invalidates the session instead of applying `last+1` or refreshing to `now`.
  Only estimates at or before `sourceReadNs` are published.
- `samples` contains 1–3 strictly ordered frames. Acceleration is m/s² including
  gravity; angular velocity is rad/s in the original controller sensor axes.
  The app applies its mounting transform. Do not send Euler angles or substitute
  the phone's IMU for Joy-Con data.

HID timer mapping estimates relative timing; it does **not** establish a measured
upper bound on Joy-Con radio/HID acquisition latency or sensor clock drift.
Initial transport delay and oscillator error still require hardware measurement.
Source-read time is recorded separately so this limitation remains visible.

## Clock synchronization

The bridge binds an ephemeral UDP endpoint for each device session, sends motion
to the configured Android address/port, and accepts replies only from that
Android endpoint. Android sends sync requests to the motion packet's **source
address and source port**, not a second hard-coded bridge port. A dedicated
bridge thread serves sync while HID reads are waiting.

Android → bridge:

```json
{"v":2,"type":"sync","token":"token-copied-from-app","nonce":"b1c04919-41a1-4510-88e5-4f0272092ed5","clientSendNs":2000000000}
```

Bridge → Android, using the same source endpoint as motion:

```json
{"v":2,"type":"sync_reply","token":"token-copied-from-app","nonce":"b1c04919-41a1-4510-88e5-4f0272092ed5","clientSendNs":2000000000,"sourceReceiveNs":1001000000,"sourceSendNs":1001010000}
```

All four timestamps are monotonic. Android validates nonce and echoed send time
against its outstanding request. For Android receive time `t4`, send time `t1`,
and source receive/send times `t2,t3`, the Android-minus-source clock offset lies
in `[t1-t2, t4-t3]` assuming nonnegative transit time. Android uses the conservative
lower bound, rejects network RTT above 30 ms, requests sync every 5 seconds and
expires it after 30 seconds. The interval is clock/network uncertainty; it does
not include unknown controller-to-bridge acquisition delay.

Clock state is separate for each UDP source endpoint. Motion received before
valid synchronization is dropped. Mapping never falls back to arrival `now`.
Android buffers at most 20 ms for reordering, rejects duplicate and retired-session
data, and drops samples older than 100 ms instead of replaying old hits. Queues
are bounded; overflow increments explicit dropped-input counters. Inspect packet
acceptance, delivered samples, sequence loss, duplicates, reordering, stale
samples and sync RTT separately in diagnostics.

Maximum payload is 8192 bytes. Both directions allocate one extra receive byte
to detect/reject oversized datagrams. Invalid schema, NaN, infinity, acceleration
magnitude above 200 m/s² and angular speed above 100 rad/s are rejected. A sync
request requires a string nonce (1–128 characters) and nonnegative 64-bit integer
`clientSendNs`; the app uses UUID nonces.

## HID identity, initialization and reconnect

The bridge uses HIDAPI and the documented reverse-engineered Nintendo protocol:
VID `0x057e`, PID `0x2006` (L) / `0x2007` (R). It filters known non-controller HID
usages and deduplicates endpoints by serial/MAC, preferring the standard
gamepad/joystick interface. Platforms without usage metadata retain their HID
endpoint but still require identity evidence for a durable binding.

1. Re-enumerate the selected identity on every connection attempt and open its
   current path. A missing controller is never replaced by another same-name unit.
2. Subcommand `0x40`, data `0x01`: enable IMU.
3. Subcommand `0x03`, data `0x30`: enable full input reports.
4. Subcommand `0x30`: set the role's player LED.
5. Subcommand `0x10`: read factory IMU SPI calibration `0x6020`, 24 bytes.
6. Decode samples at offsets 13, 25, 37 of report `0x30`; start timer warmup.

Each command requires a matching positive ACK and a full write. Unrelated IMU
reports and ACKs do not complete the transaction. SPI replies must echo the exact
address/length and provide the whole payload. Timeout retries at most three
times; NACK and short writes fail the connection. Invalid/unavailable factory
coefficients produce a visible default-scale warning. The bridge never writes
flash or permanent controller calibration.

Only valid advancing IMU data feeds the watchdog. Three seconds of invalid/no
data reconnects; duplicate timer streams also expire or trip the earlier timer
ambiguity guard. Each role has its own worker, HID connection and UDP channel;
one missing controller does not reset the other three.

## Running and recording

```sh
python3 -m pip install -r tools/bridge/requirements.txt
python3 tools/bridge/joydurm_bridge.py --list
python3 tools/bridge/joydurm_bridge.py --host PHONE_LAN_IP --token APP_TOKEN \
  --bind LEFT_HAND=ID1 --bind RIGHT_HAND=ID2 \
  --bind LEFT_FOOT=ID3 --bind RIGHT_FOOT=ID4 \
  --record joydurm-source-session.jsonl
```

`--record` creates a new JSONL file and never overwrites an existing one. It
records source packets/sync replies with the token removed. Capture makes sample
intervals, identities, sessions and report timers auditable. It is not an acoustic
latency measurement or proof of physical controller attribution.

`--simulate` sends explicit diagnostic input with `identityStable=false` and
`identitySource=simulator`, never connected hardware. Assign these session-local
devices in the app. Simulator output is excluded from hardware acceptance.

Run software regressions with:

```sh
python3 -m unittest discover -s tools/bridge -v
```

Tests include real loopback UDP motion/sync, six source samples delivered in two
adjacent datagrams, unchanged old sample times, timer wrap/overlap, matching ACKs,
source watchdogs and path-changing reconnect fixtures. Four real controllers,
platform HID enumeration, radio timing and ten-minute multi-device recording
remain `HARDWARE_PENDING` until measured with the final app/bridge.

Protocol references:

- [Nintendo Bluetooth report format](https://github.com/dekuNukem/Nintendo_Switch_Reverse_Engineering/blob/master/bluetooth_hid_notes.md)
- [Nintendo IMU data and units](https://github.com/dekuNukem/Nintendo_Switch_Reverse_Engineering/blob/master/imu_sensor_notes.md)
- [HIDAPI](https://github.com/libusb/hidapi)
- [Android controller sensors](https://developer.android.com/reference/android/view/InputDevice#getSensorManager())

Switch 2 Joy-Con uses a different BLE protocol and is outside this bridge's support.
