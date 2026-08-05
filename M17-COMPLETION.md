# M17 Completion — Native Painting, SVG and Typography

M17 adds explicit canvas background propagation, list markers, image-position metadata and native raster positioning. Inline SVG markup replaces `currentColor` with the element's computed color before Android painting.

Paint order is now verified for nested icon buttons: default control chrome precedes nested SVG and text. Existing clipping, shadows, borders, opacity, typography metadata and isolated image cache behavior remain cumulative.

Six dedicated M17 tests cover canvas propagation, control/SVG order, currentColor, markers and image fit/position data.
