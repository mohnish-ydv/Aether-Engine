# M12 Strict Android Lint Fix Report

The second uploaded GitHub Actions run confirmed that the previous Kotlin compile blocker was fixed: manifest/resource compilation and every Kotlin compile task passed. Its uploaded JUnit XML contains 61 suites and 612 tests with 0 failures, 0 errors, and 0 skipped tests. The run then stopped at strict Android lint with exactly three app-module errors in both debug and release reports.

## Confirmed lint findings

1. `MainActivity.kt` used `Build.VERSION.SDK_INT >= 26` even though the application minimum SDK is already 26 (`ObsoleteSdkInt`).
2. `AndroidImagePainter.kt` parsed a file URI with `Uri.parse(source)` instead of the available Android KTX `String.toUri()` extension (`UseKtx`).
3. `AndroidImagePainter.kt` manually paired `Canvas.save()` / clipping / `restoreToCount()` instead of the available Android KTX `Canvas.withClip()` scope (`UseKtx`).

## Corrections

- Notification-channel creation is now unconditional because every supported device is API 26 or newer.
- File URI parsing now uses `source.toUri().path` with `androidx.core.net.toUri`.
- SVG clipping and transform drawing now execute inside `canvas.withClip(target)`, preserving automatic save/restore semantics even if drawing exits unexpectedly.
- Added source-audit regression guards that reject the obsolete SDK check, the old `Uri.parse` call, and manual save/restore in the native image painter.

No lint baseline, issue suppression, warning downgrade, minimum-SDK change, or feature removal was used.
