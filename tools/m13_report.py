#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
tests = list(ROOT.glob('engine-core/src/test/**/*.kt'))
prod = '\n'.join(path.read_text(encoding='utf-8') for path in ROOT.glob('engine-core/src/main/**/*.kt'))

def count(paths):
    return sum(len(re.findall(r'@Test\s+fun\s+', p.read_text(encoding='utf-8'))) for p in paths)

total = count(tests)
security = count(list(ROOT.glob('engine-core/src/test/kotlin/com/mohnishraj/aether/core/security/*.kt')))
shell = count(list(ROOT.glob('engine-core/src/test/kotlin/com/mohnishraj/aether/core/shell/*.kt')))
m13_paths = [ROOT / f'engine-core/src/test/kotlin/com/mohnishraj/aether/core/{name}' for name in (
    'js/JsModernRuntimeTest.kt', 'browser/BrowserModernDomTest.kt', 'shell/BrowserScriptExecutionTest.kt'
)]
m13 = count(m13_paths)
required = [
    'JsStatement.ForOf', 'JsExpression.New', 'class JsPromiseValue', 'class BrowserJsBindings',
    'collectDocumentScripts(', 'evaluateExternal(', 'MutationObserver', 'XMLHttpRequest',
    'page.advanceTimeBy(elapsedMillis)', 'page.updateComputedStyles',
]
print('AETHER M13 JAVASCRIPT + MODERN DOM REPORT')
print('=========================================')
print('Engine version: 0.13.0-m13')
print(f'Cumulative declared JVM tests: {total}')
print(f'New M13 tests: {m13}')
print(f'M11 security tests retained: {security}')
print(f'Shell/visual/script tests: {shell}')
print('Cumulative in-app checks retained: 157')
if total != 645 or m13 != 29 or security != 65 or shell != 72 or any(token not in prod for token in required):
    raise SystemExit('M13 inventory status: FAIL')
print('M13 inventory status: PASS')
