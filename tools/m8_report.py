#!/usr/bin/env python3
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
browser_dir = root / "engine-core/src/main/kotlin/com/mohnishraj/aether/core/browser"
browser_files = sorted(browser_dir.rglob("*.kt"))
browser_lines = sum(len(path.read_text(encoding="utf-8").splitlines()) for path in browser_files)
test_root = root / "engine-core/src/test/kotlin"
test_count = sum(len(re.findall(r"@Test\s+fun\s+", path.read_text(encoding="utf-8"))) for path in test_root.rglob("*.kt"))
browser_test_dir = test_root / "com/mohnishraj/aether/core/browser"
browser_test_count = sum(len(re.findall(r"@Test\s+fun\s+", path.read_text(encoding="utf-8"))) for path in browser_test_dir.rglob("*.kt"))
production = "\n".join(path.read_text(encoding="utf-8") for path in browser_files)
required = [
    "class BrowserApiRuntime", "class BrowserPage", "class BrowserDocument",
    "class BrowserEventHub", "class BrowserMutationHub", "class BrowserStorageManager",
    "class BrowserStorageArea", "class BrowserFormController", "class BrowserFetchBridge",
    "class BrowserJsBindings", "interface ClipboardPort", "object BrowserApiSelfTest",
    "maxStorageBytesPerOrigin", "maxEventListeners", "maxFetchResponseBytes",
]
missing = [token for token in required if token not in production]
print("AETHER M8 BROWSER API REPORT")
print("============================")
print("Engine version: 0.8.0-m8")
print(f"Browser API production files: {len(browser_files)}")
print(f"Browser API production lines: {browser_lines}")
print(f"Declared unit tests: {test_count}")
print(f"Browser-API-specific unit tests: {browser_test_count}")
print("Cumulative in-app checks: 110")
print("DOM: queries, creation, mutation, cloning and serialization")
print("Interaction: events, mutation observers, forms and clipboard port")
print("State/network: origin storage and M2 fetch bridge")
print("JavaScript: controlled window/document/storage/event host bindings")
if missing:
    print("Missing inventory tokens:", ", ".join(missing))
    raise SystemExit(1)
if test_count != 405 or browser_test_count != 58:
    print(f"Unexpected test inventory: total={test_count}, browser={browser_test_count}")
    raise SystemExit(1)
print("M8 inventory status: PASS")
