# Aether Engine M13.0.1 — Clipboard Promise CI Fix Report

## Uploaded-run diagnosis

The uploaded GitHub Actions run completed Android manifest/resource compilation and every Kotlin compilation task successfully. The core test task then executed all 645 tests and stopped on exactly one failure:

- `BrowserJsBindingsTest.scriptCanUseClipboard`
- expected: `Aether M8`
- actual: `[object Object]`

The actual value was a JavaScript Promise object. M13 intentionally upgraded `navigator.clipboard.writeText()` and `navigator.clipboard.readText()` to standards-oriented Promise-returning APIs, while this retained M8 test still treated `readText()` as a synchronous string.

## Corrective change

The production Clipboard API was not downgraded or made synchronous. The stale test now:

1. writes text through `navigator.clipboard.writeText(...)`;
2. chains `navigator.clipboard.readText()` through `.then(...)`;
3. stores the resolved value in the persistent JavaScript realm; and
4. verifies the resolved string in a second evaluation.

This protects the intended M13 contract: Promise reactions must drain, the browser realm must persist, and clipboard content must round-trip.

## Verification performed

- Uploaded GitHub run: resource compilation PASS.
- Uploaded GitHub run: every Kotlin source compilation PASS.
- Uploaded GitHub run: 644 tests passed and one stale clipboard test failed.
- Isolated M13 JavaScript/Promise/host-object regression harness: PASS.
- Source/configuration audit after manifest regeneration: PASS.
- Repository-root ZIP CRC, path, duplicate, symlink and clean-extraction audits: PASS at packaging.

## Environment boundary

The complete post-fix Gradle suite could not be rerun in this sandbox because `services.gradle.org` could not be resolved. GitHub Actions remains the authoritative final Android lint/APK gate.
