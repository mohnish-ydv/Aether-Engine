# M8 Completion — Browser APIs

## Completed

- Browser page and normalized origin lifecycle
- Controlled JavaScript `window` and `document` bindings
- DOM ID/tag/class/CSS-selector lookup
- Element and text creation, insertion, replacement, removal and cloning
- Attribute, text-content and fragment HTML mutation
- Event listener registration/removal and capture/target/bubble dispatch
- Bounded mutation observer queues and delivery
- Persistent origin-scoped `localStorage`
- Runtime-scoped `sessionStorage`
- Form control extraction, validation and URL encoding
- Clipboard abstraction plus Android implementation
- Synchronous networking bridge using the M2 HTTP engine
- Browser API statistics and explicit safety limits
- Native Android Browser API Lab and DevTools commands
- M1–M7 regression preservation

## Deliberate scope boundaries

M8 is not a full WHATWG browser API conformance release. It does not yet include complete Web IDL conversions, custom elements, shadow DOM, CSSOM, selection/ranges, file inputs, navigation, asynchronous Promise-based fetch, XMLHttpRequest, IndexedDB, service workers, WebSocket bindings, history APIs or automatic style/layout/paint invalidation.

`fetchSync` is a temporary deterministic API built on the existing network client. It will evolve when Promise and event-loop integration is expanded. Clipboard access is explicit through a platform port and does not bypass Android's platform behavior.

## Verification status

- Complete repository regression suite: 405/405 test methods PASS.
- Browser-API-specific regression tests: 58/58 PASS.
- All 405 test methods use JUnit-compatible Kotlin block bodies and JVM `void` signatures.
- Cumulative engine self-test: 110/110 PASS.
- Core production and test sources compile with warnings as errors on JVM 17.
- Android platform and app sources type-check with warnings as errors against API-surface stubs.
- XML, workflow YAML, Python, Bash and source-manifest checks pass in the available environment.

The official Android AAPT2, lint, R8 and APK result is produced by the included GitHub Actions workflow.
