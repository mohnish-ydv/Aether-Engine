# M12 GitHub Compile Fix Report

The uploaded GitHub Actions logs exposed one real Kotlin compilation failure in both Android debug and release variants.

```text
MainActivity.kt:842:79 Smart cast to 'String' is impossible, because
'actualSha256' is a public API property declared in different module.
```

## Root cause

`ManagedDownload.actualSha256` is a nullable public property owned by the `engine-core` module. The Downloads UI checked `item.actualSha256 != null` and then read the public property again inside the same expression. Kotlin cannot guarantee that an externally declared public property returns the same value on the second access, so the cross-module smart cast is rejected. With warnings-as-errors and both build variants compiled, CI correctly stopped.

## Correction

- Snapshot `item.actualSha256` into the local immutable value `actualSha256`.
- Null-check and dereference only that stable local value.
- Add source-audit guards requiring the snapshot and rejecting the former unsafe dereference.
- Preserve all M12 browser, compatibility, privacy and rendering functionality without suppressions or weakened compiler settings.

No lint baseline, warning downgrade, nullable assertion, or unsafe cast was introduced.
