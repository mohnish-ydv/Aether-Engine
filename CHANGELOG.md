# Changelog

## 0.18.0-m18 — M14–M18 Native Web Compatibility Batch

- Separate user-agent and author stylesheet origins in the live render pipeline
- Expand shorthands before cascade winner selection and preserve shorthand compatibility values
- Add modern structural, form-state and relative selector coverage including bounded `:has()`
- Add CSS math, dynamic viewport units, `display:contents`, intrinsic sizing and recursive overflow propagation
- Improve inline whitespace, wrapping, transform, indent, alignment and baseline behavior
- Improve Flexbox constraints, auto margins, stretch/wrap behavior and form intrinsic dimensions
- Reclassify `<button>` as a container so nested text/SVG content survives layout
- Paint native control chrome before nested content, fixing blank icon buttons
- Add canvas background propagation, list markers, SVG `currentColor`, object-position and native raster positioning
- Fix Aether home responsive overflow
- Add 34 M14–M18 regression tests; all 679 cumulative JVM tests pass locally

## 0.13.1-m13 CI test fix

- Migrated the legacy synchronous clipboard binding test to the standard Promise-based Clipboard API introduced in M13
- Added a persistent-realm readback assertion so the test verifies both Promise microtask delivery and clipboard state
- Preserved the production runtime unchanged because `navigator.clipboard.readText()` and `writeText()` were already correctly Promise-based
- Confirmed from the uploaded GitHub run that Android resources and every Kotlin source compiled before the single stale test stopped CI

## 0.13.0-m13 — JavaScript Runtime + Modern DOM Foundation

- Automatically execute bounded inline, external and deferred classic page scripts
- Add arrow functions, `for...of`, constructors, exceptions, Promises, intervals and JSON parsing
- Add live standard DOM properties, classList/dataset/style, events and MutationObserver
- Add Fetch/XHR, storage, clipboard, location, performance and lifecycle foundations
- Pump timer/microtask DOM mutations into the native rendering pipeline
- Accept JavaScript keyword property names after dot access (for example `promise.catch(...)` and `promise.finally(...)`)
- Add 29 M13 regression tests and cumulative M13 release verification

## 0.12.1-m12.1 — Real-Web Rendering Rescue

- Replaced the app-like global user-agent theme with standards-oriented browser defaults while keeping the internal new-tab design scoped
- Added CSP-authorized external stylesheet loading, `<base>` resolution, redirects, document ordering and bounded recursive `@import`
- Added logical CSS properties and legacy WebKit Flexbox compatibility aliases
- Improved intrinsic sizing for replaced elements and native form controls
- Added inline SVG display-list generation and native path/polygon/polyline painting
- Added `srcset` and `data-src` image source fallback plus common system colors
- Added four regression tests; cumulative declared JVM inventory is now 616
- Added an 18-check live real-web compatibility harness and M12.1 release audits

## 0.12.0-m12 strict lint fix

- Fixed all three errors from the uploaded debug/release Android lint reports
- Removed the obsolete API 26 runtime check while retaining API 33 notification-permission handling
- Migrated file URI parsing to Android KTX `String.toUri()`
- Migrated native SVG clipping to Android KTX `Canvas.withClip()`
- Added regression guards without lint baselines, suppressions or warning downgrades
- Preserved the cumulative M1–M12 implementation and repository-root layout

## 0.12.0-m12 compile fix

- Fixed Android debug/release Kotlin compilation in the Downloads screen by snapshotting the cross-module nullable `actualSha256` property before dereferencing it
- Added a source-audit regression guard for this compiler-sensitive access pattern
- Preserved the complete cumulative M1–M12 implementation and version identity

## 0.12.0-m12

- Added bookmark folders, CRUD, search, duplicate handling and JSON import/export
- Added full visit history, time grouping, search, selected/all clearing and counters
- Added persistent resumable downloads with checkpoints, pause/cancel/retry, notifications, progress and SHA-256 verification
- Added Reader Mode extraction, heading/byline detection, font/theme controls and progress
- Added highlighted Find in Page with previous/next, case mode and match counter
- Added a separate memory-backed incognito runtime and comprehensive wipe path
- Fixed an incognito local-storage object-retention privacy defect found during regression testing
- Added Flexbox direction, wrap, grow, shrink, basis, order, justify/align and gap behavior
- Improved percentage sizing, aspect ratio, positioning, overflow, clipping, stacking and ellipsis
- Added native form-control paint states and typography spacing/decorations
- Added PNG, JPEG, WebP, static GIF and SVG-foundation image rendering with object-fit
- Added M12 browser UX pages and toolbar integration
- Added 21 M12 regression tests; cumulative declared JVM inventory is 612
- Updated CI, version metadata, documentation, source audit and repository packaging for M12

## 0.11.0-m11

- Fixed M10 system-inset, browser-chrome and measured-viewport integration
- Rebuilt responsive browser toolbar and tab strip
- Added debug-only compact rendering HUD
- Added new-tab paint-coordinate regression protection
- Added tuple origins and same-origin enforcement
- Added CSP parser and resource decisions
- Added mixed-content and HTTPS-downgrade protection
- Added CORS, sandbox and Permissions-Policy enforcement
- Integrated security with navigation, fetch, forms, clipboard and JavaScript bindings
- Added Security Engine Lab, DevTools commands, 65 security tests and cumulative 157-check self-test

Earlier cumulative milestone history remains represented by the retained completion and fix documents and source tree.
