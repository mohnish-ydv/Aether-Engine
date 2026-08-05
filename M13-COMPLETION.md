# M13 Completion — JavaScript Runtime + Modern DOM Foundation

M13 cumulatively preserves M1–M12.1 and connects real page scripts to Aether's native DOM, security and rendering pipeline.

## JavaScript runtime

- Arrow functions with expression or block bodies
- `for...of` over arrays and strings
- Constructor calls through `new`
- `throw` and `try/catch/finally`
- Promise fulfillment/rejection reactions and microtask delivery
- Recurring intervals and cancellation
- JSON parsing plus broader Array, String and Object helpers
- JavaScript keyword property names after dot access, including `.catch` and `.finally`

Existing source, AST, statement, step, recursion, task, timer, string, array and object limits remain active.

## Live browser platform

`window`, `document`, Node and Element values are live host objects rather than detached maps. M13 adds DOM mutation, querying, attributes, `classList`, `dataset`, inline style, events, property handlers, lifecycle states, `MutationObserver`, storage, Fetch, XMLHttpRequest, clipboard, location, console, viewport and performance foundations.

## Document and render integration

The browser shell discovers bounded inline and external classic scripts, resolves relative URLs, applies CSP/sandbox and redirect security, executes immediate/deferred scripts, dispatches interactive/complete lifecycle transitions and keeps script-updated titles in tab/history state.

Timer, interval and microtask work is advanced by the render session. DOM mutation records request new style/layout/paint/compositor work, allowing scripts to change later native frames.

## Verification inventory

- 645 cumulative declared JVM tests
- 29 new M13 JavaScript/DOM/script tests
- 65 retained M11 security tests
- 72 shell/visual/script tests
- 157 retained in-app self-test checks
- 29/29 M13 tests executed successfully in the standalone local test gate

## Scope boundary

M13 does not claim ES modules, `async`/`await`, classes, generators, Shadow DOM, Custom Elements, Canvas/WebGL, service workers, WebAssembly, full CSSOM or Chromium-level website compatibility.
