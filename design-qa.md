# Native UI design QA — JoyDrum 0.3.0

Status: **PASS for the native UI translation, readability and tested software flows**. This is not a pixel-identical photographic render or physical-controller/AR acceptance.

The supplied 1536×1024 twelve-screen board was materialized and visually inspected. Actual Android display captures, including the SceneView SurfaceView, were compared side by side; no mock screenshot, source-board raster or fabricated room is used as app UI. Comparison artifacts remain in `../ui-redesign/`.

## Reference decisions and final visual review

| Category | Review and intentional differences |
| --- | --- |
| Fonts | Android system sans with Chinese fallback; 52sp welcome display, 22/23sp page titles, 14/15sp buttons. Chinese-first copy changes line lengths. Native Android status/navigation bars replace the illustrated iOS chrome. |
| Spacing and shapes | Dark stage, thin blue-gray card borders, 16dp rounded cards, blue primary actions and four controller colors. Minimum48dp touch targets. Setup next/skip actions stay reachable while details scroll. PLAY controls hug the bottom. |
| Colors | UI background#080e13, cards#131d26, blue#1677ff, text#f4f7fb. The original coral/metal GLB remains; ordinary3D stage lighting now preserves those material colors and provides blue/warm hit-light contrast. AR environmental light estimation is unchanged. |
| Images | Separate transparent controller atlas, licensed Material icons, and a scene-only preview cropped from an actual native render. The same geometry preview appears for all three existing audio banks. Photoreal drum/room images from the board are not substituted for the real renderer/camera. |
| Copy and state | Real device identity/freshness and calibration state replace example connected/battery/12ms values. No recording, firmware update or extra banks are fabricated. Acoustic latency and transport battery availability are stated honestly. |
| Responsive behavior | Tested1080×2400 at420dpi, reference-oriented1080×1920 at420dpi, and840×1920 at420dpi with fontScale1.3. Actual aspect ratios are preserved in the comparison; the board's wide AR illustrations are letterboxed rather than stretching native screenshots. Dense settings/calibration content scrolls. |
| Hit feedback | Actual touch hits drive the existing animation timeline and a single bounded Filament point light. Blue light visibly reaches the snare; cymbals use warm light. Captures of sustained touch playing show a current hit even on the slower emulator compositor. |

## Iteration history

1. **P0/P1:** initial screenshot capture occurred before window composition, producing a white welcome transition and prior-page images. Wait for composition and actual kit pixels; keep the8-second cap. Initial fixed-height PLAY footer floated upward; switched to content height with a39% scroll cap. Setup primary actions moved to a fixed footer.
2. **P0:** an early WindowInsetsController access caused a startup exception. Moved it after content creation; preserved the failed logs, then actual11-case lifecycle/navigation regression passed.
3. **P1/P2:** ordinary3D background conversion and bright illumination reduced contrast. Applied linear clear color and stage lighting; bounded blue/warm pulses follow the transformed head in world space. Framed welcome/sound-check kit above guidance controls. Replaced hand-drawn previews with real rendered pixels, and removed duplicated output text.
4. **P1:** narrow/fontScale1.3 screenshots showed vertically wrapped pad labels and a clipped recenter button. Reduced compact-control inner padding while retaining48dp targets; both viewport suites were rerun and inspected. No remaining actionable P0/P1/P2 findings from this software UI review.

## Final software evidence

- 118 JVM tests,0 failures/errors/skipped; `accepted-ui-build.log`.
- 11 actual Android cases PASS,45.701s; runID `ui-stage-accepted-20261001-452ea27c`, `qa-accepted/`. The original model visibility threshold/deadline is preserved; controller art is excluded from its sample area.
- 4 UI cases PASS in the reference-oriented viewport and4 PASS in the narrow/large-font viewport. These are independent scoped runs, not substitutes for the11-case gate.
- 15 local delivery gate tests PASS. Debug and release lint each0 errors,44 warnings; existing warnings are not represented as resolved.
-APK authored/dependency asset inventory verification passes. Original local debug signer and actual3→4 install upgrade are checked in the external delivery reports.

## Remaining physical acceptance

Four real Joy-Con identity/sample freshness, mounting/foot behavior, ARCore camera tracking/anchors and acoustic latency remain HARDWARE_PENDING. The software screenshots intentionally show zero connected controllers and ordinary3D. The app remains native SceneView/ARCore; an independent WebAR page is not part of this UI delivery.
