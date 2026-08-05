# M18 Final Verification

## Completed local gates

- Safe repository-root extraction: PASS
- Production `engine-core` Kotlin compilation with `-Werror`, JVM 17: PASS
- Complete test-source Kotlin compilation with warnings as errors: PASS
- Modified Android image painter compiled with Android/AndroidX API stubs and `-Werror`: PASS
- Standalone execution of all compiled JVM tests: **679/679 PASS**
- Focused M14–M18 compatibility harness: **20/20 PASS**
- Source/configuration/static audit: recorded by `tools/static_audit.py`
- Source-manifest coverage and SHA-256 integrity: recorded after final packaging
- ZIP CRC, duplicate, path traversal, symlink and clean-extraction checks: recorded in the external release report

## Defects found and fixed by verification

1. `border-color: currentColor` disappeared because shorthand preservation was removed during cascade expansion.
2. `border-radius` disappeared from computed style after corner expansion, causing rectangular instead of rounded backgrounds.
3. default long tokens stopped splitting and regressed the retained bounded-overflow contract.
4. descendant overflow was not propagated through intermediate layout boxes.
5. default control background detection treated computed transparent defaults as explicit author styling.
6. an opaque canvas command required retained paint tests to distinguish canvas-level commands from element commands.

## Android gate limitation

The execution environment does not contain a cached Android SDK/Gradle distribution and cannot resolve the Gradle host. Full manifest/resource processing, Android/Kotlin compilation, strict lint and APK assembly therefore remain assigned to the included **Aether Android CI M18** workflow. No claim is made that those unavailable local tasks were executed.
