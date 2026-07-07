# ASTRA GUI Bevel / Radius / Tab-Curvature Proof

Generated: 2026-07-03

## Pass 2 Verdict

Shape / border / edge implementation pass 2 closes the remaining production
rows in the active shape-family group. The target families now have only
closed inventory states: generated CSS tokens, tokenized Java formulas, or
test/diagnostic proof scaffolding.

## Pass 1 Verdict

Bevel/radius/arc/tab-curvature implementation pass 1 is source-tokenized for
the Java geometry paths that were previously raw or unclear, and the primary
rendered proof targets have preview artifacts. Follow-up CSS token work first
tested JavaFX looked-up size values, but live preview smoke showed JavaFX
rejects looked-up numeric values for size properties with `ClassCastException`.
The renderer-safe contract is therefore: Java owns the token values,
`launcher.tokens.css` records the generated token manifest, and `launcher.css`
uses concrete generated size values guarded by staleness/source tests.

## Source-Of-Truth Tokens

- `LauncherGeometryTokens.BEVEL_DIAMETER_DIVISOR`
- `LauncherGeometryTokens.CUBIC_ARC_HANDLE_RATIO`
- `LauncherGeometryTokens.COMPACT_BEVEL_RADIUS`
- `LauncherGeometryTokens.CONTROL_BEVEL_RADIUS`
- `LauncherGeometryTokens.CARD_BEVEL_RADIUS`
- `LauncherGeometryTokens.SURFACE_BEVEL_RADIUS`
- `LauncherGeometryTokens.BADGE_BEVEL_RADIUS`
- `LauncherGeometryTokens.FILLER_BEVEL_RADIUS`
- `LauncherGeometryTokens.DIALOG_BEVEL_RADIUS`
- `LauncherGeometryTokens.WORKFLOW_PILL_BEVEL_RADIUS`
- `LauncherGeometryTokens.NO_BORDER_WIDTH`
- `LauncherGeometryTokens.BILATERAL_BORDER_WIDTH`
- `LauncherGeometryTokens.SETTINGS_CARD_ACCENT_ARC`
- `LauncherGeometryTokens.PRESSED_TRANSLATE_Y`
- `LauncherGeometryTokens.CHEVRON_OPTICAL_Y_OFFSET`

## Pass 2 Resolved Inventory Families

| Family | Final active-pass classifications |
| --- | --- |
| `bevels-radii-arcs-tab-curvature` | `113 generated-css-token`, `10 tokenized-pass-1`, `14 generated/test-only` |
| `borders-strokes` | `7 tokenized-pass-2`, `6 generated-css-token`, `9 generated/test-only` |
| `header-footer-tab-shape-metrics` | `3 tokenized-pass-2`, `1 already-tokenized`, `16 generated/test-only` |
| `z-layer-clipping-edge-artifacts` | `2 generated-css-token` |

## Pass 2 Resolved Inventory Rows

| Row group | Resolution |
| --- | --- |
| CSS stroke/border rows (`GVT-0207`, `GVT-0284`, `GVT-0356`, `GVT-0363`, `GVT-0369`, `GVT-0422`) | Production CSS now uses renderer-safe concrete generated values derived from `SURFACE_BORDER_WIDTH` or `NO_BORDER_WIDTH`. |
| CSS arc rows (`GVT-0314`, `GVT-0315`) | Dashboard card accent arc now uses a concrete generated value derived from `LauncherGeometryTokens.SETTINGS_CARD_ACCENT_ARC`. |
| CSS edge-offset rows (`GVT-0163`, `GVT-0222`) | Pressed and chevron offsets now use concrete generated values derived from `PRESSED_TRANSLATE_Y` and `CHEVRON_OPTICAL_Y_OFFSET`. |
| Java stroke/formula rows (`GVT-0004`, `GVT-0012`, `GVT-0014`, `GVT-0021`, `GVT-0030`, `GVT-0037`, `GVT-0054`) | Production formulas now use named bilateral-edge or bevel-divisor tokens instead of local raw stroke multipliers. |
| Header/footer shape rows (`GVT-0019`, `GVT-0020`, `GVT-0023`) | Header/footer dimensions use `BILATERAL_EDGE_COUNT`; diagnostic mirrors are classified test-only. |
| Preview/test rows in the active families | Classified as `generated/test-only`; they are proof scaffolding and not production visual source-of-truth. |

## Resolved Inventory Rows

| Row | Resolution |
| --- | --- |
| `GVT-0006` | Accent bar arc now uses `PARAMETER_ANCHOR_PAINT_RADIUS * LauncherGeometryTokens.BEVEL_DIAMETER_DIVISOR`. |
| `GVT-0024` | Tab bevel clamp now uses `height / LauncherGeometryTokens.BEVEL_DIAMETER_DIVISOR`. |
| `GVT-0050` | Legacy inline control radius now routes through `compactBevelCss()`. |
| `GVT-0055` | Progress radius now maps to `COMPACT_BEVEL_RADIUS`. |
| `GVT-0724` | Log metric badge no longer uses unbounded `999`; it is classified as bounded badge/workflow-pipe mirror. |
| `GVT-0726` | Log timeline dot no longer uses unbounded `999`; it is classified as bounded badge/workflow-pipe mirror. |
| `GVT-0923` | Header preview proof now uses `BEVEL_DIAMETER_DIVISOR`. |
| `GVT-0925` | Preview cubic handle proof now uses `CUBIC_ARC_HANDLE_RATIO`. |
| `GVT-0929` | Footer preview proof now uses `BEVEL_DIAMETER_DIVISOR`. |
| `GVT-1023` | Stale source assertion now expects the shared bevel divisor. |
| `GVT-1030` | Numeric test now derives accent radius from `BEVEL_DIAMETER_DIVISOR`. |
| `GVT-1031` | Numeric test now derives accent arc from `BEVEL_DIAMETER_DIVISOR`. |

## Rendered Proof Artifacts

| Surface family | Artifact |
| --- | --- |
| Header/footer tab path and rendered button-wall zero deltas | `/private/tmp/astra-bevel-pass-proof/header-footer-button-wall-proof.md` |
| Header/footer edge audit | `/private/tmp/astra-bevel-pass-proof/header-footer-button-wall-proof-edge-audit.md` |
| Dashboard cards, focused panels, dependent panels, accent rails, help details | `/private/tmp/astra-bevel-pass-proof-geometry/` |
| Output/run-log cards and header actions | `/private/tmp/astra-bevel-pass-proof-output/output-pane-geometry.md` |
| Runtime installer cards/panel | `/private/tmp/astra-bevel-pass-proof-runtime/runtime-installer-geometry.md` |
| Combo closed and popup/list surfaces | `/private/tmp/astra-bevel-pass-proof-combo/` |

## Important Non-Bevel Finding

The bevel pass originally found bottom action-row spacing deltas in the general
layout table:

- `output pane to run button`: expected `24`, observed `38`.
- `run button to bottom`: expected `24`, observed `14`.
- `run button to right edge`: expected `24`, observed `38`.

Those were spacing-family rows, not bevel/radius rows. They are resolved by the
spacing pass recorded in `docs/gui-spacing-token-proof.md`; this bevel pass
still does not claim ownership of them.

## CSS Token Follow-Up

`launcher.tokens.css` remains generated byte-for-byte from
`LauncherGeometryTokens`. `launcher.css` imports it as the durable token
manifest, but renderer-facing size properties are concrete generated values
rather than JavaFX looked-up size values. `PipelineLauncherTest` guards that the
concrete CSS values match the geometry tokens and that generated token names do
not leak into size properties JavaFX cannot parse reliably.

Remaining manual review is visual: the rendered surfaces listed above are ready
for human inspection.
