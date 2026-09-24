# ASTRA GUI Spacing / Margin Proof

Generated: 2026-07-07

## Pass 2 Status

Spacing implementation pass 2 is tentatively complete and awaiting manual
visual review. It started with the known bottom action-row regression from the
bevel pass, then classified or token-fixed the remaining audited
`margins-padding-gaps-spacing` rows.

## Bottom Action-Row Correction

The footer action shell encloses the `Cancel` and `Run` buttons with the same
inset used by the header ribbon. The old geometry proof measured the inner
`Run` button but did not account for:

- the workspace bottom margin between the output pane and footer row;
- the footer shell inset around the button;
- the footer shell right/bottom placement gap needed to keep the inner button
  edge on the shared outer-margin rail.

The correction uses named formulas in `FooterGeometry`:

| Token / formula | Purpose |
| --- | --- |
| `ACTION_BUTTON_OUTER_GAP = LauncherGeometry.OUTER_MARGIN` | The target gap from the `Run` button edge to pane/window rails. |
| `ACTION_SHELL_PLACEMENT_GAP = ACTION_BUTTON_OUTER_GAP - ACTION_SHELL_INSET` | Right and bottom shell placement gap after accounting for shell inset. |
| `ACTION_SHELL_TOP_PLACEMENT_GAP = ACTION_BUTTON_OUTER_GAP - LauncherGeometry.OUTER_MARGIN - ACTION_SHELL_INSET` | Top shell placement gap after accounting for the workspace bottom margin and shell inset. |
| `mainActionBarPadding() -> FooterGeometry.actionBarPadding()` | The bottom action row uses the footer geometry source of truth. |

No screenshot-derived constants were introduced.

## Shared Edge-Count Tokenization And CSS Token Bridge

This pass also removed the raw two-edge spacing count from production geometry
formulas that were already part of the audited `margins-padding-gaps-spacing`
family.

New or promoted source tokens:

- `LauncherGeometryTokens.SINGLE_COUNT`
- `LauncherGeometryTokens.BILATERAL_EDGE_COUNT`
- `LauncherGeometryTokens.TRILATERAL_EDGE_COUNT`

Resolved inventory rows:

| Row | Resolution |
| --- | --- |
| `GVT-0007` | Dependent panel left inset now uses `BILATERAL_EDGE_COUNT`. |
| `GVT-0008` | Segment button width reduction now uses `SEGMENT_BUTTON_WIDTH_REDUCTION`. |
| `GVT-0010` | Segment row width now uses `SEGMENT_CONTROL_GAP_COUNT`. |
| `GVT-0011`, `GVT-0013`, `GVT-0015`, `GVT-0016`, `GVT-0017`, `GVT-0018` | Header/menu width and inset formulas now use `BILATERAL_EDGE_COUNT`. |
| `GVT-0022` | Workflow chip width now uses `BILATERAL_EDGE_COUNT` for row insets/node gaps. |
| `GVT-0029`, `GVT-0033` | Dashboard body/grid formulas now use `BILATERAL_EDGE_COUNT` and `TRILATERAL_EDGE_COUNT`. |
| `GVT-0041` | Parameter label text width now uses `BILATERAL_EDGE_COUNT`. |
| `GVT-0051`, `GVT-0052`, `GVT-0053`, `GVT-0057` | Shared geometry spacing tokens now derive from semantic count tokens. |

CSS declarations that can cleanly consume generated tokens now import
`launcher.tokens.css`. The generated file is committed for reviewability and
guarded by a byte-for-byte staleness test against `LauncherGeometryTokens`.
All audited spacing-family CSS mirror rows now consume generated token values;
no `margins-padding-gaps-spacing` row remains classified as
`css-semantic-spacing-mirror`.

## Proof Artifacts

Targeted geometry artifacts:

`/private/tmp/astra-spacing-pass-complete`

The following preview modes report zero deltas for the formerly failing bottom
action rows:

| Preview | `output pane -> Run` | `Run -> bottom` | `Run -> right edge` |
| --- | ---: | ---: | ---: |
| `dashboard-geometry-overlay` | `0.00` | `0.00` | `0.00` |
| `run-setup-geometry-overlay` | `0.00` | `0.00` | `0.00` |
| `images-scope-geometry-overlay` | `0.00` | `0.00` | `0.00` |
| `models-geometry-overlay` | `0.00` | `0.00` | `0.00` |
| `segmentation-geometry-overlay` | `0.00` | `0.00` | `0.00` |

Example formula rows from the proof:

- `output pane to run button`: expected `24.00`, observed `24.00`, delta `0.00`.
- `run button to bottom`: expected `24.00`, observed `24.00`, delta `0.00`.
- `run button to right edge`: expected `24.00`, observed `24.00`, delta `0.00`.

## Manual Review Remaining

The `margins-padding-gaps-spacing` family is tentatively complete and awaiting
manual visual review. The spacing rows are closed in
`docs/gui-visual-token-inventory.csv`; generated CSS token rows remain
reviewable through the staleness test plus manual visual review.
