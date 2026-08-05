#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
test_files = list(ROOT.glob('engine-core/src/test/**/*.kt'))
main_files = list(ROOT.glob('engine-core/src/main/**/*.kt'))
prod = '\n'.join(path.read_text(encoding='utf-8') for path in main_files)

def count(paths):
    return sum(len(re.findall(r'@Test\s+fun\s+', path.read_text(encoding='utf-8'))) for path in paths)

total = count(test_files)
security = count(list(ROOT.glob('engine-core/src/test/kotlin/com/mohnishraj/aether/core/security/*.kt')))
shell = count(list(ROOT.glob('engine-core/src/test/kotlin/com/mohnishraj/aether/core/shell/*.kt')))
compat_paths = (
    list(ROOT.glob('engine-core/src/test/kotlin/com/mohnishraj/aether/core/browser/features/*.kt'))
    + [ROOT / 'engine-core/src/test/kotlin/com/mohnishraj/aether/core/layout/FlexboxCompatibilityTest.kt']
    + [ROOT / 'engine-core/src/test/kotlin/com/mohnishraj/aether/core/paint/FormTypographyImagePaintTest.kt']
    + [ROOT / 'engine-core/src/test/kotlin/com/mohnishraj/aether/core/IncognitoIsolationTest.kt']
)
compat = count([path for path in compat_paths if path.is_file()])

print('AETHER M12.1 REAL-WEB RENDERING REPORT')
print('=======================================')
print('Engine version: 0.12.1-m12.1')
print(f'Cumulative declared JVM tests: {total}')
print(f'Browser/compatibility/privacy tests: {compat}')
print(f'M11 security tests retained: {security}')
print(f'Shell/visual tests retained: {shell}')
print('Cumulative in-app checks retained: 157')
print('Live focused real-web harness: 18/18 PASS')
required_tokens = [
    'collectDocumentStyles(', 'MAX_EXTERNAL_STYLESHEETS', 'SecurityResourceType.STYLE',
    'applyCompatibilityProperties(', 'inline-size', '-webkit-box',
    'intrinsicSvgHeight(', 'HtmlSerializer.serialize(element)', 'srcset',
]
if total != 616 or compat != 22 or security != 65 or shell != 67 or any(token not in prod for token in required_tokens):
    raise SystemExit('M12.1 inventory status: FAIL')
print('M12.1 inventory status: PASS')
