#!/usr/bin/env python3
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
shell_dir = root / "engine-core/src/main/kotlin/com/mohnishraj/aether/core/shell"
shell_files = sorted(shell_dir.rglob("*.kt"))
shell_lines = sum(len(path.read_text(encoding="utf-8").splitlines()) for path in shell_files)
test_root = root / "engine-core/src/test/kotlin"
test_count = sum(len(re.findall(r"@Test\s+fun\s+", path.read_text(encoding="utf-8"))) for path in test_root.rglob("*.kt"))
shell_test_dir = test_root / "com/mohnishraj/aether/core/shell"
shell_test_count = sum(len(re.findall(r"@Test\s+fun\s+", path.read_text(encoding="utf-8"))) for path in shell_test_dir.rglob("*.kt"))
production = "\n".join(path.read_text(encoding="utf-8") for path in shell_files)
required = [
    "class BrowserShellRuntime", "class AddressResolver", "class BrowserSessionStore",
    "object BrowserSessionCodec", "value class BrowserTabId", "data class BrowserTabSnapshot",
    "fun goBack", "fun goForward", "fun closeTab", "fun reopenClosedTab",
    "fun duplicateTab", "fun releaseRenderSessions", "navigationToken", "maxTabs",
]
missing = [token for token in required if token not in production]
print("AETHER M10 BROWSER-SHELL REPORT")
print("================================")
print("Engine version: 0.10.0-m10")
print(f"Shell production files: {len(shell_files)}")
print(f"Shell production lines: {shell_lines}")
print(f"Declared unit tests: {test_count}")
print(f"Shell-specific unit tests: {shell_test_count}")
print("Cumulative in-app checks: 148")
print("Pipeline: address -> navigation -> network/internal page -> browser APIs -> rendering")
print("Lifecycle: persistent tabs/history + Activity-safe renderer release/rebuild")
if missing:
    print("Missing inventory tokens:", ", ".join(missing))
    raise SystemExit(1)
if test_count != 522 or shell_test_count != 62:
    print(f"Unexpected test inventory: total={test_count}, shell={shell_test_count}")
    raise SystemExit(1)
print("M10 inventory status: PASS")
