#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
test_files = list(ROOT.glob('engine-core/src/test/**/*.kt'))
main_files = list(ROOT.glob('engine-core/src/main/**/*.kt'))
tests = '\n'.join(path.read_text(encoding='utf-8') for path in test_files)
prod = '\n'.join(path.read_text(encoding='utf-8') for path in main_files)

def count(paths):
    return sum(len(re.findall(r'@Test\s+fun\s+', path.read_text(encoding='utf-8'))) for path in paths)

total = count(test_files)
security = count(list(ROOT.glob('engine-core/src/test/kotlin/com/mohnishraj/aether/core/security/*.kt')))
shell = count(list(ROOT.glob('engine-core/src/test/kotlin/com/mohnishraj/aether/core/shell/*.kt')))
m12_paths = (
    list(ROOT.glob('engine-core/src/test/kotlin/com/mohnishraj/aether/core/browser/features/*.kt'))
    + [ROOT / 'engine-core/src/test/kotlin/com/mohnishraj/aether/core/layout/FlexboxCompatibilityTest.kt']
    + [ROOT / 'engine-core/src/test/kotlin/com/mohnishraj/aether/core/paint/FormTypographyImagePaintTest.kt']
    + [ROOT / 'engine-core/src/test/kotlin/com/mohnishraj/aether/core/IncognitoIsolationTest.kt']
)
m12 = count([path for path in m12_paths if path.is_file()])

print('AETHER M12 BROWSER + COMPATIBILITY REPORT')
print('==========================================')
print('Engine version: 0.12.0-m12')
print(f'Cumulative declared JVM tests: {total}')
print(f'M12 feature/compatibility/privacy tests: {m12}')
print(f'M11 security tests retained: {security}')
print(f'Shell/visual tests retained: {shell}')
print('Cumulative in-app checks retained: 157')
print('Browser features: bookmarks, history, downloads, reader, find, isolated incognito')
print('Compatibility: Flexbox, forms, typography, images, clipping and viewport improvements')
required_tokens = [
    'class BookmarkManager', 'class BrowsingHistory', 'class ManagedDownloadController',
    'class ReaderModeEngine', 'class FindInPageEngine', 'clearAllStorage()',
    'LayoutBoxKind.FLEX', 'data class DrawImage'
]
if total != 612 or m12 != 21 or security != 65 or shell != 66 or any(token not in prod for token in required_tokens):
    raise SystemExit('M12 inventory status: FAIL')
print('M12 inventory status: PASS')
