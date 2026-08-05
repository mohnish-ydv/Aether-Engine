#!/usr/bin/env python3
from __future__ import annotations
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TEST_ROOT = ROOT / 'engine-core/src/test/kotlin'
NEW = [
    TEST_ROOT / 'com/mohnishraj/aether/core/css/M14CascadeSelectorCompatibilityTest.kt',
    TEST_ROOT / 'com/mohnishraj/aether/core/layout/M15FormattingContextCompatibilityTest.kt',
    TEST_ROOT / 'com/mohnishraj/aether/core/layout/M16FlexFormsReplacedCompatibilityTest.kt',
    TEST_ROOT / 'com/mohnishraj/aether/core/paint/M17PaintSvgTypographyCompatibilityTest.kt',
    TEST_ROOT / 'com/mohnishraj/aether/core/shell/M18RealWorldCompatibilityTest.kt',
]

def count(paths: list[Path]) -> int:
    return sum(len(re.findall(r'@Test\s+fun\s+', path.read_text(encoding='utf-8'))) for path in paths)

all_tests = list(TEST_ROOT.rglob('*.kt'))
total = count(all_tests)
new_count = count(NEW)
security = count(list((TEST_ROOT / 'com/mohnishraj/aether/core/security').glob('*.kt')))
shell = count(list((TEST_ROOT / 'com/mohnishraj/aether/core/shell').glob('*.kt')))
production = '\n'.join(path.read_text(encoding='utf-8') for path in (ROOT / 'engine-core/src/main/kotlin').rglob('*.kt'))
required = [
    'CssOrigin.USER_AGENT', 'expandDeclaration', 'RelativeSelector', 'display == "contents"',
    'minMain', 'MainMargins', 'ImagePosition', 'paintCanvasBackground',
    'replace("currentColor"', 'object-position', 'width:min(90vw,584px)'
]
missing = [token for token in required if token not in production and token not in '\n'.join(p.read_text(encoding='utf-8') for p in NEW)]

print('AETHER M14-M18 COMPATIBILITY REPORT')
print('====================================')
print('Engine version: 0.18.0-m18')
print(f'Cumulative tests: {total}')
print(f'New M14-M18 tests: {new_count}')
print(f'Security tests: {security}')
print(f'Shell/real-world tests: {shell}')
if total != 679 or new_count != 34 or security != 65 or shell != 78 or missing:
    raise SystemExit(f'M18 inventory status: FAIL; missing={missing}')
print('M18 inventory status: PASS')
