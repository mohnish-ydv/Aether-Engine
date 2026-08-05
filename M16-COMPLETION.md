# M16 Completion — Flexbox, Forms and Replaced Elements

M16 strengthens flex constraints, automatic main-axis margins, wrapping/cross-axis behavior and intrinsic form sizes. Buttons are no longer classified as replaced elements: nested spans and SVG icons receive normal layout boxes.

Textarea rows/columns, input size/type and select option text contribute to intrinsic dimensions. Control backgrounds and borders are emitted before child text/SVG so default chrome cannot cover the button's actual content.

Seven dedicated M16 tests cover button/container semantics, SVG retention, auto margins, controls and object-position parsing.
