# M10 Fix Report Included with M11

The M10 screenshot exposed real presentation defects even though it came from a debug APK.

## Root causes corrected

1. Browser chrome did not reserve Android system-bar/cutout insets.
2. The page viewport was estimated using a hardcoded toolbar subtraction instead of the measured compositor size.
3. The preview could load before the compositor had final dimensions.
4. The native Canvas consumers treated each text fragment baseline as an absolute page coordinate even though it is relative to the fragment rectangle, causing lines from different vertical positions to paint on top of one another.
5. The internal new-tab page also needed explicit layout rules supported by the current engine.
6. The debug metrics row was too large and visually competed with the page.

## Corrective changes

- Edge-to-edge layout with `WindowInsetsCompat`
- Measured compositor viewport callback and render-session resize
- Deferred initial page activation until view measurement
- Rebuilt responsive chrome and tab strip
- Absolute native text Y coordinate: `rect.y + baselinePx` in both compositor and Paint Lab
- Explicit supported new-tab CSS/semantic display defaults
- Debug HUD guarded by `BuildConfig.DEBUG`
- Four browser-shell visual-regression tests plus one in-app paint-coordinate check

The release build hides the debug HUD; both build types use the same corrected page viewport and rendering path.

## M11 GitHub compile fix — final correction

The uploaded GitHub Actions logs exposed one real Android Kotlin compilation error in both debug and release variants:

```text
MainActivity.kt:330:13 Unresolved reference 'selectAllOnFocus'
```

### Root cause

`EditText` exposes the Android method `setSelectAllOnFocus(boolean)`. The source incorrectly used `selectAllOnFocus = true`, which is not a valid Kotlin synthetic property for this API under Kotlin 2.1.20.

### Correction

- Replaced the invalid property assignment with `setSelectAllOnFocus(true)`.
- Added a static-audit regression guard that rejects future `selectAllOnFocus = ...` assignments.
- Added a positive guard requiring the Android setter call.
- Recompiled the complete app and Android platform source sets with warnings treated as errors against Android API-surface stubs.
- Re-ran all 591 JVM tests and verified every test passed.

No lint baseline, warning suppression, or compiler-warning downgrade was introduced.
