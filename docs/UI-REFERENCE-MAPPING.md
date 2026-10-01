# JoyDrum native UI reference mapping

Scope: redesign the existing Android application from the supplied twelve-screen board.

Reference: user-supplied `11921.png`, 1536 × 1024, inspected as actual pixels locally. The complete reference board is not packaged as application UI. Base source: `fdcf1fa290cf5351340a951ac35d5f719379d4ab`.

## Visual and runtime decisions

- Native Kotlin Android views; preserve ControllerHub, EngineExecutor, SoundPool and SceneView/ARCore. No HTML prototype or rasterized screen replacement.
- Dark stage background, blue-gray card outlines, large corner radii, blue primary CTA, white display hierarchy, muted secondary text. Left hand cyan, right hand coral, hi-hat green, kick yellow.
- Reference typography resembles a heavy system sans display with neutral sans UI copy. Use Android's system sans with appropriate weights and Chinese fallback. Do not reproduce the pictured iOS status bar, battery, clock or home indicator.
- Hero, sound check and performance use the existing authored eight-piece GLB in the real renderer. Controller illustrations are separate image assets; standard UI icons use licensed Material assets. No fabricated camera room background.
- Chinese-first product copy. Actual names/status/IMU counts replace the example connected/battery/12ms data. Unsupported recording and firmware update are clearly unavailable; only the three existing sound banks are selectable. Language availability is stated honestly.
- Portrait-first flow with narrow-screen scrolling, minimum 48dp targets, system font-scale support and Android system insets. Keep back navigation, skip-to-touch playing and access to all existing advanced controls.

| Reference screen | Native page and real implementation |
| --- | --- |
| 1 stage welcome | WELCOME, rendered GLB hero, start setup or touch playing |
| 2 four controllers | CONNECT, actual motion counts, system pairing and LAN diagnostics; Android12+ driver limitation visible |
| 3 roles | ROLES, four unique engine role bindings from actual fresh input devices |
| 4 hand calibration | HANDS, existing three-second stationary bias calibration, recenter, target-direction binding and threshold tuning; no invented automatic sensitivity training |
| 5 hi-hat | HAT, actual closed/open capture and engine openness, retry/recenter; no fixed sample angles |
| 6 AR placement | PLACE, real camera permission/ARCore capability, ground hit-test/anchor, layout rotation/scale and re-placement; ordinary3D fallback |
| 7 sound check | SOUND_CHECK, real eight-piece rendered kit, playable pads and existing audio engine |
| 8 performance | PLAY, immersive scene, four live role indicators, openness, settings and touch pads |
| 9 hit feedback | PLAY hit state, actual renderer animation, bounded blue/warm point-light pulse and UI event highlight; no fabricated recording state |
| 10 drum kit | KIT, existing Studio/Electronic/Lo-fi sound banks, layout editor, GLB/WAV imports |
| 11 devices | DEVICES, fresh/absent input state, rebinding, tests/IMU export; battery unavailable if transport supplies none, no firmware updater |
| 12 settings | SETTINGS, Android audio output settings, volume/BPM/metronome, real thresholds and trace export; acoustic latency unmeasured |

## Acceptance boundary

Compile/lint/regressions and actual emulator screenshot comparison validate the UI and software flows only. Four physical controllers, foot mounting, real camera AR tracking, thermal stress and acoustic latency remain hardware acceptance items. Prior version0.2.0 APKs remain preserved in the external handoff directory; a new versionCode is required for the redesigned APK.
