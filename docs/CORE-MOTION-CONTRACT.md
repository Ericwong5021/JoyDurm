# Motion and execution contract

The core is a pure Kotlin orientation-zone engine. It does not claim measured Joy-Con position, geometric six-degree-of-freedom drum collisions, or verified hardware/acoustic latency.

## Frames and orientation

`Quaternion` maps controller body vectors into world coordinates. Gyroscope input is body angular velocity in rad/s; accelerometer input is m/s² with approximately +9.80665 along world up at rest. Integration uses `q * exp(omega_body * dt)` followed by gravity correction only near the gravity magnitude. Six-axis fusion cannot observe absolute yaw; acceleration during motion can still distort gravity inference. These limits require physical trace evaluation.

`ImuFrame.sourceTimeNs` remains the source timestamp. `sample.timeNs` is the sample time mapped onto Android elapsed realtime; `receivedTimeNs` records delivery. Clock uncertainty over 30 ms, mapped future times beyond uncertainty, and motion older than 250 ms at receipt are rejected. The executor separately rejects gestures held in its queue for over 250 ms. Trace replay preserves the same event-time decisions while allowing delivery delay to vary.

`ControllerIdentity.physicalId` identifies the physical controller where possible. `bindingId` is the live routing key matched against `sample.device`, and may include a session namespace for an unstable transport. A new `SessionId`, disconnect, calibration completion, or sample gap over 300 ms invalidates the neutral epoch. Fresh recenter is required before IMU-triggered sound. Restored physical calibration and settings never restore session neutral.

Relative attitude comes from `neutral.conjugate() * current`, not from subtracting Euler angles. Hand strokes record the peak attitude and timestamp; target choice uses that peak even though detection is confirmed during deceleration. A target must have squared normalized distance below 4, with a runner-up distance margin of at least 0.35. Ambiguous overlapping targets reject the stroke. `Hit.timeNs` is the peak event time; `receivedTimeNs` is delivery time. This does not undo the detector's decision delay.

## Feet

Kick acceleration is `accel - inverse(q) * worldGravity`, projected onto the configured body axis and sign. A negative lift excursion must precede a positive downstroke threshold crossing. A held impulse and rebound cannot retrigger until quiet recovery and cooldown. Axis and sign change the actual detector input. Lift/down thresholds are software policies requiring mounting-specific physical validation.

Hi-hat requires closed/open pose capture after recenter. The axis and range are derived from the relative quaternion between those poses, supporting arbitrary mounting directions. Continuous openness is smoothed with elapsed-time exponential smoothing (35 ms time constant), with open/closed hysteresis. A closure faster than one normalized openness unit per second can emit one CHICK, with 140 ms cooldown; slow closure emits openness controls without a foot strike. Loss, recenter, calibration, queue overflow and layout invalidation send independent `HatControl.ChokeAll`; none fabricate CHICK. Captures are session-only. Legacy `hatRange`/`hatSign` are migration fields, not the active two-pose mapping.

## Layout and ownership

`replaceLayout` publishes one shared revision, geometry, scale and yaw. Changed arrangement clears hand targets and invalidates neutral; after recenter each target must be explicitly rebound. The original templates are available only for revision zero. Resetting a changed layout cannot restore the original template targets. Reanchoring AR at identical local coordinates passes `forceRevision=true` to invalidate the old mapping.

`EngineExecutor` is the sole runtime writer. UI and render read published immutable `EngineSnapshot`; `call` returns a future and never waits for queue space. Queue overflow is explicit through rejection counters/futures and invalidates assigned motion state. Disconnect controls use four reserved role slots, so a full sample/command queue cannot suppress a required choke. The queue is bounded; closing rejects pending commands and returns immediately, with `terminated` available for lifecycle tests. Audio callbacks run outside engine/hub locks. Persistence restores role settings before executor startup, then uses snapshots.

## Software evidence scope

The pure regression suite includes quaternion compound/world-axis/inverted/±π/sample-rate trajectories; relative quaternion composition; reconnect, restart and long-gap neutral epochs; namespaced binding identity; lift-only and repeated stomp traces; mounting and sample-rate hi-hat traces; disconnect choke; layout invalidation, peak pose/time and ambiguity; delayed trace replay; and concurrent queue/close/stalled-writer tests. Test results must be taken from the integrated Gradle run. Synthetic 200-action traces do not imply 200 real foot actions passed. No Android device or real Joy-Con acceptance is represented by these tests.
