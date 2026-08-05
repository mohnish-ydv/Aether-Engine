# Aether Architecture Through M18

## Pipeline

```text
URL/network response
  → HTML tokenizer/parser and live DOM
  → M13 bounded JavaScript/browser bindings
  → external + inline stylesheet collection
  → M14 origin-aware cascade and selector matching
  → M15/M16 block, inline, flex, intrinsic and form layout
  → M17 display-list construction and SVG/image metadata
  → Android Canvas painter and compositor
```

Aether does not instantiate Android `WebView`. Native Android code consumes Aether's immutable display list.

## Stylesheet origin boundary

The renderer now supplies the user-agent stylesheet separately from page styles. `CssStyleSheet.origin` survives collection and the cascade compares importance, origin, specificity and declaration order. Shorthands expand before winner selection, so a later shorthand can override an earlier longhand and vice versa.

## Selector engine

Selectors are represented as bounded compound/complex selectors. M14 extends matching with relative selectors for `:has`, forgiving selector lists for `:is`/`:where`, structural/of-type formulas, attributes, form state, language, direction, focus and root/scope semantics. Pseudo-elements parse but never masquerade as normal DOM elements.

## Formatting and intrinsic sizing

`LayoutValueParser` resolves absolute/font/viewport units and bounded CSS math. The layout engine builds block, inline-block, flex, list-item and replaced boxes. `display:contents` flattens descendants into the parent formatting context. Intrinsic text/control measurement feeds shrink-to-fit behavior. Descendant scroll extents propagate upward instead of being lost at intermediate boxes.

## Flex and controls

Flex items carry basis, grow, shrink, order, automatic minimums, min/max limits and auto-margin flags. Distribution iterates until constrained items freeze. Buttons remain containers, while inputs, textarea, select, images and SVG retain replaced-element sizing where appropriate. Control chrome is placed before nested content in the display list.

## Paint and Android image path

The display list begins with an opaque canvas and optional propagated root/body color. Box shadows, backgrounds, gradients, controls, images/SVG, borders, text/list markers and inset effects follow deterministic order. `DrawImage` includes `ImageFit` and `ImagePosition`; Android raster fitting uses both axes for destination alignment and cover cropping. Private image-cache namespaces from M12 remain isolated.

## Security and privacy

M11 CSP, mixed-content, CORS, sandbox, permission and redirect checks remain active. M12 incognito storage/cookie/cache/history separation remains active. M13 scripts use the same security-aware network and DOM lifecycle rather than bypassing the browser pipeline.

## Compatibility boundary

The architecture intentionally remains bounded and auditable. It does not claim full HTML Living Standard, CSS, ECMAScript, SVG, accessibility, media, graphics or browser API conformance. M18 improves the custom engine's standards surface and protects it with real-world fixtures; it does not embed or recreate Chromium in five milestones.
