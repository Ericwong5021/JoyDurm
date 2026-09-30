# JoyDurm input protocol v1

Android receives UDP on port `18185` (configurable). The user enables the listener
and copies a generated 128-bit token to the bridge. No discovery, internet server
or cloud account is required. This token prevents accidental injection by other
devices; UDP is not encrypted. Use a trusted local network.

```json
{
  "v": 1,
  "token": "token-copied-from-app",
  "device": "stable-device-id",
  "name": "Joy-Con L",
  "role": "LEFT_HAND",
  "seq": 123,
  "samples": [
    {"a": [0.0, 0.0, 9.80665], "g": [0.0, 0.0, 0.0]}
  ]
}
```

Roles: `LEFT_HAND`, `RIGHT_HAND`, `LEFT_FOOT`, `RIGHT_FOOT`.
One Joy-Con may only have one role. The App's explicit assignment takes precedence
over an incoming role hint. Disconnecting a device retains its user's role and
calibration, but its status changes to waiting for data. Diagnostics report the
last sample age and sample count; input expires after three seconds without fresh
data. The performance screen uses a stricter 500 ms live indicator. Closing the
bridge immediately clears bridge input and persists the disabled listener setting.

`a`: accelerometer m/s² including gravity; `g`: angular velocity rad/s.
`samples`: 1–3 ordered samples, 5 ms apart for original Joy-Con 0x30 reports.
Never send Euler angles or use the phone's IMU in place of Joy-Con data.

The App stamps packets with its own monotonic clock; sender clocks are not assumed
synchronized. A strictly increasing `seq` prevents duplicates/reordering. After a
2-second gap the sequence can restart. Invalid schema, NaN, infinity and implausible
IMU ranges are rejected. The maximum accepted payload is 8192 bytes. The receiver allocates one extra
byte to detect and reject oversized datagrams, including truncated packets. Acceleration
magnitude above 200 m/s² and angular speed above 100 rad/s are rejected.

## Original Joy-Con initialization

The bridge uses open-source HIDAPI and the documented reverse-engineered Nintendo
protocol. Vendor ID `0x057e`, product IDs `0x2006` (L) / `0x2007` (R).

1. Output 0x01 subcommand 0x40, data 0x01: enable IMU.
2. Subcommand 0x03, data 0x30: full input reports.
3. Subcommand 0x30: player LEDs.
4. Read SPI calibration 0x6020, 24 bytes via subcommand 0x10.
5. Decode three accelerometer/gyro samples at offsets 13, 25, 37 in report 0x30.

The bridge waits for ACKs, checks addresses and coefficients, falls back to default
scale if factory data is missing, and retries the connection on timeout. It never
writes flash or changes permanent controller calibration.

References:
- https://github.com/dekuNukem/Nintendo_Switch_Reverse_Engineering
- https://github.com/libusb/hidapi
- https://developer.android.com/reference/android/view/InputDevice#getSensorManager()

Switch 2 Joy-Con uses a different BLE protocol and is outside v0.1.1 support.
