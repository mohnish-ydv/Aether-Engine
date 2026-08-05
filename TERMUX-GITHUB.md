# Aether M18 — Termux and GitHub

## Termux source audit

```bash
pkg install git openjdk-17 python
chmod +x gradlew verify.sh
bash verify.sh --source-only
```

When network access and Gradle dependencies are available:

```bash
./gradlew --no-daemon --stacktrace :engine-core:test
./gradlew --no-daemon --stacktrace :app:assembleDebug
```

The debug APK is expected at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions

Upload the ZIP contents directly to the repository root. Open **Actions → Aether Android CI M18** and run the workflow. Install an APK only after the entire job is green. The APK artifact is `Aether-M18-APKs`; QA/lint/test reports are `Aether-M18-QA-Reports`.

The workflow intentionally fails on compiler warnings, test failures, lint warnings/errors, missing APKs, malformed resources/manifests or source-audit drift.
