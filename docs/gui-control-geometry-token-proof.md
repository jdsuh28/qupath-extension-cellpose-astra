# GUI Control Geometry Token Proof

## Pass 3 Verdict

The `control-geometry` family is tentatively complete and awaiting manual
visual review.

The implementation ledger now has zero unresolved `control-geometry` rows:

| Classification | Rows |
| --- | ---: |
| `tokenized-pass-3` | 15 |
| `generated-css-token` | 11 |
| `generated/test-only` | 19 |

## Source-Of-Truth Changes

- Scrollbar thumb and side-padding formulas now use named geometry divisors:
  `SCROLLBAR_THUMB_GUTTER_DIVISOR` and `SCROLLBAR_SIDE_PADDING_DIVISOR`.
- Hidden/probe controls use `HIDDEN_CONTROL_SIZE` instead of anonymous zero
  dimensions.
- Single-list selection dialog dimensions use
  `SINGLE_LIST_WIDTH_SCALE` and `SINGLE_LIST_HEIGHT_SCALE`.
- Multi-select summary truncation uses
  `MULTI_SELECT_SUMMARY_CHARACTER_LIMIT`.
- The JavaFX subnode inline-style reset path is named through
  `clearInlineStyle(...)`, so empty style resets are not treated as geometry.
- `launcher.tokens.css` now records generated control-size tokens for staleness
  checking while `launcher.css` keeps renderer-safe concrete values.

## Required Proof Targets

The control-geometry pass uses the same preview harness as the spacing and
shape passes. The expected proof location is:

`/private/tmp/astra-control-geometry-pass-proof`

Required representative surfaces:

| Surface | Proof Mode |
| --- | --- |
| macro buttons and button families | `button-states-geometry` |
| output macro buttons | `output-pane-geometry` |
| combo/list controls | `combo-popup-geometry`, `asset-backed-combo-geometry` |
| custom inline controls | `custom-controls-geometry` |
| runtime installer controls | `runtime-installer-geometry` |
| progress/action lane | `run-progress-geometry` |

Generated proof artifacts:

`/private/tmp/astra-control-geometry-pass-proof`

Post-closure proof correction:

- A follow-up scan found that the original edge-audit summaries contained
  stale false failures for popup-style roots and the button-state diagnostic
  strip.
- The popup failure was a proof-harness defect: empty popup layout bounds were
  used instead of rendered snapshot dimensions.
- The button-state failure was a diagnostic-window sizing defect: the proof
  window was narrower than the full button strip.
- Both defects were corrected in `LauncherPreviewApp`, with source guards in
  `PipelineLauncherTest`.
- Corrected rerun artifacts:
  `/private/tmp/astra-proof-rerun-combo-fixed`,
  `/private/tmp/astra-proof-rerun-buttons-fixed2`, and
  `/private/tmp/astra-proof-rerun-asset-combo-fixed`.

Structured proof scan:

- CSV proof files scanned: `18`
- Rows scanned: `173`
- Proof fields checked: `173`
- Corrected rerun rows scanned: `116`
- Corrected rerun result: all scanned proof deltas/statuses passed.

## Completion Standard

- Focused `PipelineLauncherTest` passes.
- Generated token CSS remains byte-for-byte synchronized with
  `LauncherGeometryTokens`.
- All proof CSV rows for same-family control dimensions have zero deltas where
  exact equality is claimed.
- Edge audits may retain painted content only when no normalization strip is
  being removed; `REVIEW_NON_BACKGROUND_PIXELS` is a failure.
