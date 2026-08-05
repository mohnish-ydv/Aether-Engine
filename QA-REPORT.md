# Aether Engine M18 — QA Report

## Scope

The M14–M18 batch targets real mobile rendering defects rather than adding disconnected API stubs. The supplied screenshots were reduced to reproducible engine causes: cascade-origin mixing, shorthand precedence, content-box overflow, button replaced-element classification, control paint order and incomplete SVG/image positioning.

## Executed verification

| Gate | Result |
|---|---:|
| Production core source compile (`-Werror`, JVM 17) | PASS |
| Complete test source compile | PASS |
| Modified Android image painter stubbed API compile (`-Werror`) | PASS |
| Cumulative JVM tests | **679/679 PASS** |
| Focused compatibility harness | **20/20 PASS** |
| New M14–M18 declared tests | **34** |
| WebView implementation references | 0 |

The complete JVM run was performed by compiling production and test source, then invoking every runtime-retained `kotlin.test.Test` method in a clean standalone runner. It exposed six genuine code/test-contract regressions, all corrected before packaging.

## Regression areas

- UA versus author cascade origins
- shorthand/longhand source order
- modern/structural selector matching
- CSS math and viewport units
- block/inline overflow and text layout
- flex automatic margins and constraints
- intrinsic form dimensions
- nested button/SVG layout and paint order
- canvas/background propagation
- object-fit/object-position display-list metadata
- 320/360/412px real-world mobile fixtures

## Remaining risk

Android resource/manifest compilation, Android-specific Kotlin, lint and APK assembly require GitHub Actions because this sandbox has no usable Android/Gradle distribution. Advanced web-platform compatibility remains incomplete and is documented in README/ARCHITECTURE.
