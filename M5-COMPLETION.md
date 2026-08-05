# M5 Completion — Layout Engine

## Delivered

M5 converts the computed-style DOM produced by M3 and M4 into an immutable, inspectable layout tree without using Android WebView.

### Formatting and geometry

- Block normal flow
- Inline line construction, fragments, whitespace modes and wrapping
- Long-token splitting and explicit line breaks
- Content/padding/border/margin rectangles
- Content-box and border-box sizing
- Width, height and min/max constraints
- Supported CSS absolute, font-relative, viewport and percentage lengths
- Additive `calc()` expressions
- Auto horizontal margins and supported vertical-margin collapse
- Replaced-element intrinsic sizing

### Positioning and overflow

- Static, relative, absolute, fixed and bounded sticky positioning
- Containing-block calculations
- Overflow visible, hidden, clip, scroll and auto
- Clip rectangles and scroll extents
- Deterministic z-index/document-order paint sequence

### Developer surfaces

- Layout inspector
- Native Android Layout Lab with editable HTML/CSS and viewport presets
- `layout-status`, `layout-demo`, `layout-tree` and `layout-paint` commands
- M5 runtime and boot diagnostics

## Regression preservation

All M1 foundation, M2 networking, M3 HTML/DOM and M4 CSS functionality remains present. The cumulative in-app self-test now contains 62 checks.

## Deferred by design

Full Flexbox, Grid, advanced tables, floats, bidi/text shaping, fragmentation and final pixel painting are not claimed in M5. These require dedicated future milestones; M6 is the Paint Engine.
