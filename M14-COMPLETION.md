# M14 Completion — CSS Cascade and Selector Engine

M14 separates user-agent and author origins in the render pipeline and changes shorthand handling from post-cascade normalization to declaration-time expansion. This fixes the two screenshot-level cascade defects where broad UA rules could defeat page CSS and where shorthand/longhand source order was lost.

Selector coverage now includes bounded `:has()` relative selectors, `:is()`, `:where()`, `:not()`, of-type formulas, only/first/last variants, form states, focus states, links, language, direction, scope and root. Pseudo-elements are accepted syntactically but do not match normal elements.

Eight dedicated M14 tests cover origin ordering, shorthand precedence, relative selectors, structural selectors, form state and specificity.
