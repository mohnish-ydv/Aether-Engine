# Aether Engine M18 — Native Web Compatibility Batch

Aether is a phone-first Android browser-engine project that renders through its own HTML, CSS, layout, paint, compositor and JavaScript/DOM pipeline. It does not instantiate Android `WebView`.

M14–M18 are one cumulative compatibility batch built on the M13 JavaScript and DOM foundation. The batch targets the concrete defects visible in real mobile pages: incorrect CSS origin ordering, shorthand cascade errors, narrow-screen overflow, incomplete inline/flex sizing, blank icon buttons, missing SVG `currentColor`, and image positioning.

## M14 — CSS cascade and selectors

- User-agent and author stylesheets now retain separate cascade origins.
- Shorthands are expanded at declaration time, preserving source order against longhands.
- CSS-wide values and compatibility aliases are normalized after cascade selection.
- Selector coverage now includes `:has()`, `:is()`, `:where()`, `:not()`, structural/of-type selectors, form state selectors, language/direction selectors, `:scope`, focus states and bounded relative selectors.
- Pseudo-elements are parsed without incorrectly matching the originating DOM element.

## M15 — formatting contexts and responsive sizing

- Block and inline layout gained intrinsic `min-content`, `max-content` and `fit-content` foundations.
- `min()`, `max()`, `clamp()`, dynamic viewport units and additional font-relative units resolve through the native length engine.
- `display: contents` participates without generating a principal box.
- Descendant overflow propagates through scroll geometry.
- Inline layout now handles text indent, transform, whitespace modes, justification, baselines, vertical alignment and bounded long-token breaking.
- The internal Aether home page no longer uses `width:100%` plus content-box padding, removing its visible right-edge overflow.

## M16 — Flexbox, forms and replaced elements

- Flex grow/shrink distribution is iterative and min/max constrained.
- Main-axis auto margins, wrap behavior, cross-axis stretch and `align-content` behavior are improved.
- `<button>` is a real container rather than a replaced element, preserving nested text and SVG children.
- Inputs, textareas and selects use type/size/rows/cols/options for intrinsic dimensions.
- Default control chrome paints before child content instead of covering icon buttons.

## M17 — painting, SVG and typography

- Canvas background propagation uses HTML/body backgrounds over an opaque canvas.
- Object-position metadata travels from CSS to raster drawing for contain, cover, none and scale-down modes.
- Inline SVG `currentColor` is materialized from computed text color before native painting.
- Ordered and unordered list markers are emitted into the display list.
- Native control paint order, text spacing, decoration and baseline metadata remain connected to the Android Canvas renderer.

## M18 — real-world compatibility sprint

- Added narrow-screen search-form fixtures at 320, 360 and 412 CSS pixels.
- Added regression coverage for the exact blank-button, root overflow and UA-style defects observed in screenshots.
- Added 34 M14–M18 tests; cumulative declared JVM inventory is 679 tests.
- All 679 compiled JVM tests pass in the standalone local test runner.

## Build on Android/Termux

```bash
chmod +x gradlew verify.sh
bash verify.sh --source-only
./gradlew --no-daemon --stacktrace :engine-core:test
./gradlew --no-daemon --stacktrace :app:assembleDebug
```

For the authoritative Android gate, upload the repository-root contents to GitHub and run **Aether Android CI M18**. Download `Aether-M18-APKs` only after manifest/resources, every Kotlin target, 679 tests, strict lint, and both APK assemblies are green.

## Honest compatibility boundary

M18 materially improves Aether's own engine; it is not Chromium, Gecko or WebKit. CSS Grid, tables, advanced font shaping, complete SVG, modules, `async`/`await`, classes, Shadow DOM, Web Components, Canvas/WebGL, media, workers, service workers, WebAssembly and the full Web Platform remain incomplete. Large production sites can still render partially. The new real-world fixtures protect measured progress without claiming browser parity.
