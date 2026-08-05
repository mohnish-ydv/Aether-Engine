# M7 Final Build-Fix Report

## Supplied GitHub Actions failure

The uploaded run completed real Android manifest/resource processing, then failed in `:engine-core:test`:

- Gradle reported `329 tests completed, 1 failed`.
- The failure was `JsParserTest > initializationError`.
- JUnit 4 rejected five expression-bodied Kotlin tests because `assertIs<T>(...)` made their JVM return type `T` instead of `void`.
- This was a test ABI defect, not a JavaScript parser runtime failure.

## Root fixes

1. Converted every expression-bodied `@Test` method in the repository to a block body, guaranteeing Kotlin `Unit`/JVM `void` test methods.
2. Added source-audit gates that reject expression-bodied tests and explicit non-`Unit` test return types.
3. Fixed all M7 `EditText.text = String` assignments. `EditText.text` is `Editable`; String values now use `setText(...)`, while clearing uses `text.clear()`.
4. Added source-audit gates that reject invalid String assignment through known `EditText` variables.
5. Reordered CI so core production/test bytecode and Android app/platform Kotlin variants compile before unit-test execution.
6. Enabled explicit JUnit 4 execution and full exception, cause and stack-trace logging.

## Post-fix verification

- All 347 repository unit tests compiled and executed successfully in a clean reflection runner using the same `kotlin.test` assertions.
- Every one of the 347 test methods was verified to return JVM `void`.
- Cumulative engine self-test: 94/94 PASS.
- Full core production source compilation with warnings as errors and JVM 17: PASS (539 classes).
- Android platform source compilation against API-surface stubs with warnings as errors: PASS (16 classes).
- Android app source compilation against Android/AndroidX API-surface stubs with warnings as errors: PASS (38 classes).
- Static source/configuration audit: 704/704 PASS.
- XML, YAML, Bash and Python structural/syntax checks: PASS.
- The supplied GitHub run's real AAPT2 manifest/resource processing: PASS.

## Final authority

A fresh run of `.github/workflows/android-ci.yml` remains the authoritative confirmation for real Android lint, R8 and APK assembly. The workflow now compiles every Kotlin source before tests and contains guards for both defects found in the supplied run/code.
