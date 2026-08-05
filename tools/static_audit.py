#!/usr/bin/env python3
from __future__ import annotations
import hashlib
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
IGNORE = {'.git', '.gradle', 'build', 'out', '__pycache__'}
TEXT_SUFFIXES = {'.kt', '.kts', '.xml', '.yml', '.yaml', '.md', '.py', '.sh', '.properties', '.pro', '.bat', '.gitignore'}
passed: list[str] = []
failed: list[str] = []

def check(condition: bool, label: str, detail: str = '') -> None:
    if condition:
        passed.append(label)
    else:
        failed.append(f'{label}: {detail}' if detail else label)

def project_files() -> list[Path]:
    return sorted(path for path in ROOT.rglob('*') if path.is_file() and not any(part in IGNORE for part in path.parts))

def read_utf8(path: Path) -> str:
    raw = path.read_bytes()
    rel = path.relative_to(ROOT).as_posix()
    check(not raw.startswith(b'\xef\xbb\xbf'), f'no BOM {rel}')
    check(b'\r\n' not in raw, f'LF endings {rel}')
    check(b'\x00' not in raw, f'no NUL {rel}')
    try:
        return raw.decode('utf-8')
    except UnicodeDecodeError as exc:
        failed.append(f'UTF-8 {rel}: {exc}')
        return ''

required = [
    'settings.gradle.kts', 'build.gradle.kts', 'gradle.properties', 'gradle/wrapper/gradle-wrapper.properties',
    'gradlew', 'verify.sh', '.github/workflows/android-ci.yml', 'SOURCE-MANIFEST.sha256',
    'README.md', 'ARCHITECTURE.md', 'CHANGELOG.md', 'QA-REPORT.md', 'VERIFICATION-RESULTS.md', 'TERMUX-GITHUB.md',
    'M13-COMPLETION.md', 'M13-FINAL-VERIFICATION.md', 'M13-FIX-REPORT.md',
    'M14-COMPLETION.md', 'M15-COMPLETION.md', 'M16-COMPLETION.md', 'M17-COMPLETION.md', 'M18-COMPLETION.md', 'M18-FINAL-VERIFICATION.md',
    'app/build.gradle.kts', 'app/src/main/AndroidManifest.xml', 'app/src/main/kotlin/com/mohnishraj/aether/MainActivity.kt',
    'app/src/main/kotlin/com/mohnishraj/aether/AndroidImagePainter.kt',
    'engine-core/src/main/kotlin/com/mohnishraj/aether/core/BuildInfo.kt',
    'engine-core/src/main/kotlin/com/mohnishraj/aether/core/css/cascade/CascadeEngine.kt',
    'engine-core/src/main/kotlin/com/mohnishraj/aether/core/css/selector/CssSelector.kt',
    'engine-core/src/main/kotlin/com/mohnishraj/aether/core/css/selector/CssSelectorParser.kt',
    'engine-core/src/main/kotlin/com/mohnishraj/aether/core/layout/LayoutEngine.kt',
    'engine-core/src/main/kotlin/com/mohnishraj/aether/core/layout/LayoutValueParser.kt',
    'engine-core/src/main/kotlin/com/mohnishraj/aether/core/paint/PaintEngine.kt',
    'engine-core/src/main/kotlin/com/mohnishraj/aether/core/paint/PaintModel.kt',
    'engine-core/src/main/kotlin/com/mohnishraj/aether/core/render/RenderPipeline.kt',
    'engine-core/src/test/kotlin/com/mohnishraj/aether/core/css/M14CascadeSelectorCompatibilityTest.kt',
    'engine-core/src/test/kotlin/com/mohnishraj/aether/core/layout/M15FormattingContextCompatibilityTest.kt',
    'engine-core/src/test/kotlin/com/mohnishraj/aether/core/layout/M16FlexFormsReplacedCompatibilityTest.kt',
    'engine-core/src/test/kotlin/com/mohnishraj/aether/core/paint/M17PaintSvgTypographyCompatibilityTest.kt',
    'engine-core/src/test/kotlin/com/mohnishraj/aether/core/shell/M18RealWorldCompatibilityTest.kt',
    'tools/static_audit.py', 'tools/m18_report.py'
]
for rel in required:
    check((ROOT / rel).is_file(), f'required {rel}')

files = project_files()
texts: dict[Path, str] = {}
for path in files:
    if path.suffix.lower() in TEXT_SUFFIXES or path.name in {'gradlew', 'LICENSE'}:
        texts[path] = read_utf8(path)

prod_paths = [p for p in texts if p.relative_to(ROOT).parts[0] in {'app', 'engine-core', 'engine-platform-android'}]
production = '\n'.join(texts[p] for p in prod_paths)

def source(rel: str) -> str:
    return texts.get(ROOT / rel, '')

build_info = source('engine-core/src/main/kotlin/com/mohnishraj/aether/core/BuildInfo.kt')
app_build = source('app/build.gradle.kts')
main_activity = source('app/src/main/kotlin/com/mohnishraj/aether/MainActivity.kt')
workflow = source('.github/workflows/android-ci.yml')
render = source('engine-core/src/main/kotlin/com/mohnishraj/aether/core/render/RenderPipeline.kt')
cascade = source('engine-core/src/main/kotlin/com/mohnishraj/aether/core/css/cascade/CascadeEngine.kt')
selector = source('engine-core/src/main/kotlin/com/mohnishraj/aether/core/css/selector/CssSelector.kt') + source('engine-core/src/main/kotlin/com/mohnishraj/aether/core/css/selector/CssSelectorParser.kt')
layout = source('engine-core/src/main/kotlin/com/mohnishraj/aether/core/layout/LayoutEngine.kt') + source('engine-core/src/main/kotlin/com/mohnishraj/aether/core/layout/LayoutValueParser.kt')
paint = source('engine-core/src/main/kotlin/com/mohnishraj/aether/core/paint/PaintEngine.kt') + source('engine-core/src/main/kotlin/com/mohnishraj/aether/core/paint/PaintModel.kt')
android_painter = source('app/src/main/kotlin/com/mohnishraj/aether/AndroidImagePainter.kt')
shell = source('engine-core/src/main/kotlin/com/mohnishraj/aether/core/shell/BrowserShellRuntime.kt')

checks = {
    'M18 engine version': 'ENGINE_VERSION = "0.18.0-m18"' in build_info,
    'M18 milestone identity': 'MILESTONE = "M14–M18 Native Web Compatibility Batch"' in build_info,
    'WebView disabled': 'USES_WEBVIEW = false' in build_info and not re.search(r'android\.webkit\.WebView|<\s*WebView\b', production),
    'Android version synchronized': 'versionCode = 1800' in app_build and 'versionName = "0.18.0-m18"' in app_build,
    'UI test inventory synchronized': 'JVM TESTS  679' in main_activity,
    'separate UA stylesheet': 'userAgentStyleSheetSource' in render and 'CssOrigin.USER_AGENT' in render,
    'declaration-time shorthand expansion': 'expandDeclaration' in cascade and 'order.toLong() * 32L' in cascade,
    'relative has selector': 'RelativeSelector' in selector and '"has"' in selector,
    'form pseudo states': '"read-only"' in selector and '"placeholder-shown"' in selector,
    'display contents': 'display == "contents"' in layout,
    'CSS clamp and dynamic viewport units': 'raw.startsWith("clamp(")' in layout and 'dvw' in layout and 'dvh' in layout,
    'recursive overflow propagation': 'childOverflowRight' in layout and 'child.scrollSize.width' in layout,
    'button is not replaced': 'REPLACED_ELEMENTS = setOf("img", "svg"' in layout and '"button"' not in re.search(r'REPLACED_ELEMENTS = setOf\(([^)]*)\)', layout, re.S).group(1),
    'flex min/max constraints': 'minMain' in layout and 'maxMain' in layout and 'MainMargins' in layout,
    'control intrinsic sizing': 'getAttribute("rows")' in layout and 'getAttribute("cols")' in layout,
    'canvas background': 'paintCanvasBackground()' in paint,
    'control chrome ordering': 'Native control chrome must be emitted before child text/SVG content' in paint,
    'SVG currentColor': 'replace("currentColor", currentColor.toHex()' in paint,
    'object position display list': 'data class ImagePosition' in paint and 'position = PaintValueParser.imagePosition' in paint,
    'native object positioning': 'position.clampedX' in android_painter and 'position.clampedY' in android_painter,
    'home overflow correction': 'box-sizing:border-box;width:auto;max-width:680px' in shell,
    'M18 CI name': 'Aether Android CI M18' in workflow,
    'M18 APK artifact': 'Aether-M18-APKs' in workflow,
    'M18 QA artifact': 'Aether-M18-QA-Reports' in workflow,
}
for label, condition in checks.items():
    check(bool(condition), label)

check(not re.search(r'\b(TODO|FIXME|HACK|XXX)\b|NotImplementedError\s*\(', production), 'zero unfinished production markers')
check('allWarningsAsErrors = true' in app_build, 'Kotlin warnings fatal')
check('warningsAsErrors = true' in app_build and 'abortOnError = true' in app_build, 'Android lint strict')
check('\t' not in workflow, 'workflow no tabs')
for token in [
    'actions/checkout@v6', 'actions/setup-java@v5', 'actions/upload-artifact@v7',
    'bash verify.sh --source-only', ':engine-core:compileKotlin', ':engine-core:compileTestKotlin',
    ':engine-core:test', ':engine-core:check', ':app:compileDebugKotlin', ':app:compileReleaseKotlin',
    ':engine-platform-android:lintDebug', ':engine-platform-android:lintRelease', ':app:lintDebug', ':app:lintRelease',
    ':app:assembleDebug', ':app:assembleRelease'
]:
    check(token in workflow, f'CI gate {token}')

settings = source('settings.gradle.kts')
for module in [':engine-core', ':engine-platform-android', ':app']:
    check(f'include("{module}")' in settings, f'Gradle module {module}')
wrapper = source('gradle/wrapper/gradle-wrapper.properties')
check('gradle-8.14.5-bin.zip' in wrapper, 'Gradle wrapper pinned')

for path in files:
    if path.suffix.lower() == '.xml':
        try:
            ET.parse(path)
            check(True, f'XML parse {path.relative_to(ROOT).as_posix()}')
        except Exception as exc:
            check(False, f'XML parse {path.relative_to(ROOT).as_posix()}', str(exc))

all_test_files = list((ROOT / 'engine-core/src/test/kotlin').rglob('*.kt'))
def test_count(paths: list[Path]) -> int:
    return sum(len(re.findall(r'@Test\s+fun\s+', path.read_text(encoding='utf-8'))) for path in paths)
new_test_files = [ROOT / rel for rel in required if re.search(r'/M1[4-8].*Test\.kt$', rel)]
total_tests = test_count(all_test_files)
new_tests = test_count(new_test_files)
check(total_tests == 679, 'unit-test inventory', f'found {total_tests}')
check(new_tests == 34, 'M14-M18 test inventory', f'found {new_tests}')

forbidden_suffixes = {'.apk', '.aab', '.class', '.dex', '.jar', '.keystore', '.jks', '.pyc'}
forbidden = [p.relative_to(ROOT).as_posix() for p in files if p.suffix.lower() in forbidden_suffixes or p.name in {'local.properties', '.DS_Store', 'Thumbs.db'}]
check(not forbidden, 'no generated binaries/local secrets', ', '.join(forbidden[:10]))
symlinks = [p.relative_to(ROOT).as_posix() for p in ROOT.rglob('*') if p.is_symlink()]
check(not symlinks, 'no symlinks', ', '.join(symlinks[:10]))

manifest = ROOT / 'SOURCE-MANIFEST.sha256'
manifest_count = 0
if manifest.is_file():
    seen: set[str] = set()
    problems: list[str] = []
    for number, line in enumerate(manifest.read_text(encoding='utf-8').splitlines(), 1):
        if not line.strip():
            continue
        parts = line.split(maxsplit=1)
        if len(parts) != 2:
            problems.append(f'line {number}')
            continue
        expected, rel = parts
        rel = rel.removeprefix('*').removeprefix('./')
        pure = PurePosixPath(rel)
        if pure.is_absolute() or '..' in pure.parts or rel in seen:
            problems.append(f'unsafe {rel}')
            continue
        seen.add(rel)
        target = ROOT / rel
        if not target.is_file():
            problems.append(f'missing {rel}')
            continue
        if hashlib.sha256(target.read_bytes()).hexdigest() != expected:
            problems.append(f'changed {rel}')
        manifest_count += 1
    expected_paths = {p.relative_to(ROOT).as_posix() for p in files if p.name != 'SOURCE-MANIFEST.sha256'}
    check(not problems, 'source manifest integrity', ', '.join(problems[:10]))
    check(seen == expected_paths, 'source manifest coverage', f'manifest={len(seen)} files={len(expected_paths)}')

print('AETHER M18 STATIC AUDIT')
print('=======================')
print(f'project files scanned: {len(files)}')
print(f'UTF-8 text files scanned: {len(texts)}')
print(f'declared unit tests: {total_tests}')
print(f'new M14-M18 tests: {new_tests}')
print(f'manifest entries verified: {manifest_count}')
print(f'checks passed: {len(passed)}')
print(f'checks failed: {len(failed)}')
if failed:
    for item in failed:
        print('FAIL', item)
    sys.exit(1)
print('STATIC AUDIT PASS')
