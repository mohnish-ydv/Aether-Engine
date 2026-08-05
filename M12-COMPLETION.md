# M12 Completion — Browser Features + Compatibility Sprint

M12 is cumulative and preserves the complete M1–M11 repository architecture.

## Delivered

- Persistent bookmarks, folders, search, JSON transfer and duplicate policies
- Visit-level history, time groups, search, counters and clearing controls
- Persistent resumable download controller with integrity verification and Android notifications
- Reader Mode and highlighted Find in Page
- Fully separate incognito browser/runtime state with auto wipe
- Flexbox formatting context and compatibility improvements
- Form controls, typography, local/remote image decoding, relative URL resolution and security-gated lazy loading
- Native Browser UX pages and toolbar integration
- 21 M12 regression tests and static/packaging gates

## Important implementation boundary

This milestone does not delegate rendering to Android `WebView`. GIF is intentionally static-first-frame support. SVG is a bounded foundation. M12 improves compatibility substantially but does not claim complete standards parity with Chromium, Gecko or WebKit.

## Strict lint correction addendum

The subsequent GitHub run passed repository audit, Android manifest/resources, all Kotlin compile tasks, and core tests. Its complete debug/release lint reports contained exactly three findings; this release removes the obsolete API-level check and adopts the two requested Android KTX extensions. See `M12-LINT-FIX-REPORT.md`.
