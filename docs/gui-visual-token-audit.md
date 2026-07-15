# ASTRA GUI Visual Token / Magic Number Audit
Generated: 2026-07-03T15:30:07

## Status
Audit-only phase completed for the launcher GUI source set. No GUI implementation, normalization, release, tag, or commit was performed. The current extension worktree already had dirty GUI files before this audit; this pass adds documentation artifacts only.

## Implementation Passes

### Pass 1: Bevels / Radii / Arcs / Tab Curvature

Status: `Tentatively complete / awaiting manual visual review`.

Implementation date: 2026-07-03.

Source result:
- Java geometry now has a semantic bevel token family in
  `LauncherGeometryTokens`.
- Header/footer tab cubic handles derive from
  `LauncherGeometryTokens.CUBIC_ARC_HANDLE_RATIO`.
- Accent-bar paint arcs and tab bevel clamps derive from
  `LauncherGeometryTokens.BEVEL_DIAMETER_DIVISOR`.
- Progress and legacy inline editor control radii are mapped to the compact
  bevel family.
- Unbounded `999` CSS radii were removed from log metric/timeline surfaces and
  classified as bounded badge/workflow-pill mirrors.

Proof result:
- Header/footer folder-tab live path segments and rendered button alpha bounds
  report zero deltas in
  `/private/tmp/astra-bevel-pass-proof/header-footer-button-wall-proof.md`.
- Raw-vs-normalized edge audit reports no stripped pixels in
  `/private/tmp/astra-bevel-pass-proof/header-footer-button-wall-proof-edge-audit.md`.
- Targeted preview proof artifacts were generated for dashboard cards,
  focused/dependent panels, parameter accent bars, help details, output/run-log
  cards, runtime installer panels, and combo/list surfaces.
- Detailed proof ledger:
  `docs/gui-bevel-token-proof.md`.

Known out-of-family finding:
- The general geometry overlay reported bottom action-row spacing deltas. Those
  rows belonged to the `margins-padding-gaps-spacing` family and were not
  changed in this bevel pass. They are now recorded as resolved by Pass 2.

Manual review remaining:
- CSS radius declarations now route through generated `launcher.tokens.css`
  values where the mapping is unambiguous. Remaining visual review is rendered
  polish, not hand-mirror drift.

### Pass 2: Margins / Padding / Gaps / Spacing

Status: `Tentatively complete / awaiting manual visual review`.

Implementation date: 2026-07-07.

Completed scope:
- Bottom action-row spacing was corrected with formula-derived footer geometry.
- `FooterGeometry.ACTION_BUTTON_OUTER_GAP` targets the shared outer margin for
  the `Run` button edge.
- `FooterGeometry.ACTION_SHELL_PLACEMENT_GAP` accounts for the footer shell
  inset at the right and bottom edges.
- `FooterGeometry.ACTION_SHELL_TOP_PLACEMENT_GAP` accounts for both the
  workspace bottom margin and the footer shell inset at the top edge.
- Shared one-edge/both-edge/three-edge count tokens now replace the raw
  two-edge spacing count in dependent-panel, header/menu, workflow, dashboard,
  parameter-label, and shared button-height geometry formulas touched by this
  pass.
- CSS spacing/radius declarations that can cleanly consume generated values now
  import `launcher.tokens.css`, which is guarded by a staleness test against
  `LauncherGeometryTokens`.
- Remaining preview/test spacing formulas are classified as tokenized or
  generated/test-only after replacement with semantic count tokens where they
  participated in proof equations.

Proof result:
- Targeted geometry-overlay previews under
  `/private/tmp/astra-spacing-pass-complete` report zero deltas for:
  `output pane to run button`, `run button to bottom`, and
  `run button to right edge`.
- The spacing inventory now has closed rows only:
  `47 closed-proven`, `42 closed-generated`, and `10 closed-test-only`.
- Detailed proof ledger:
  `docs/gui-spacing-token-proof.md`.

Manual review remaining:
- Generated CSS token rows require manual visual review for appearance, while
  byte-for-byte test coverage guards token drift.

### Pass 3: Control Geometry / Button And Input Sizing

Status: `Tentatively complete / awaiting manual visual review`.

Implementation date: 2026-07-07.

Source result:
- extension-owned controls now route same-family geometry through explicit control
  geometry tokens/helpers.
- Scrollbar thumb and side-padding formulas, hidden/probe controls, list dialog
  dimensions, multi-select summary limits, action-progress sizes, and JavaFX
  subnode style resets are named rather than anonymous literals.
- Generated control-size CSS mirrors are recorded in `launcher.tokens.css` and
  guarded by a staleness test.

Proof result:
- A follow-up proof scan found stale false edge-audit failures in the original
  `/private/tmp/astra-control-geometry-pass-proof` output.
- The proof harness now falls back to rendered snapshot dimensions when popup
  layout bounds are empty, and the button-state diagnostic window now sizes to
  its full button strip before capture.
- Corrected rerun artifacts under
  `/private/tmp/astra-proof-rerun-combo-fixed`,
  `/private/tmp/astra-proof-rerun-buttons-fixed2`, and
  `/private/tmp/astra-proof-rerun-asset-combo-fixed` scan 116 rows with
  all deltas/statuses passing.
- Detailed proof ledger:
  `docs/gui-control-geometry-token-proof.md`.

Manual review remaining:
- Visual review of same-family control polish remains pending; the source and
  proof contract has zero unresolved `control-geometry` rows.

### Pass 4: Typography / Rendered Ink

Status: `Tentatively complete / awaiting manual visual review`.

Implementation date: 2026-07-07.

Source result:
- Launcher typography now has a Java source of truth in
  `LauncherTypographyTokens`.
- Generated CSS mirrors for font stacks, weights, sizes, and the text optical
  inset correction live in `launcher.tokens.css` and are guarded by
  `PipelineLauncherTest`.
- `PipelineLauncher`, `StyledLogView`, and `RuntimeInstaller` now consume the
  typography token path for font stacks, inline font-size text, text optical
  correction, and elapsed-time text formatting.
- Misclassified color, opacity, and motion rows were moved out of
  `typography-text-ink` and left for their proper future families.

Proof result:
- The typography inventory now has zero unresolved rows:
  `102 generated-css-token`, `6 generated/test-only`, and
  `2 tokenized-pass-4`.
- Targeted preview proof artifacts were generated under:
  `/private/tmp/astra-typography-pass-proof`.
- `text-contract-sweep.csv` reports `1173` text rows, all `PASS`.
- Representative covered surfaces include dashboard/focused panels,
  rail-critical parameter labels, dependent titles, help buttons, text fields,
  combo/list cells, fallback header menus, help dialogs, runtime installer
  dialogs/panels, custom editor diagnostics, output/log text, and progress
  diagnostics.

Manual review remaining:
- Visual approval of typography appearance remains pending. Source-of-truth and
  rendered text contract proof are tentatively complete.

### Pass 5: Theme / State / Readability

Status: `Tentatively complete / awaiting manual visual review`.

Implementation date: 2026-07-07.

Source result:
- Launcher theme and state values now have a Java source of truth in
  `LauncherThemeTokens`.
- Generated CSS mirrors for theme colors and reviewed opacity values live in
  `launcher.tokens.css` and are guarded by `PipelineLauncherTest`.
- `launcher.css` consumes generated color tokens for launcher-owned states and
  keeps numeric opacity values only as renderer-safe allowlisted mirrors.
- `PipelineLauncher` now consumes theme tokens for the base palette, channel
  fallback/stroke colors, changed-value emphasis, and Java opacity states.
- `AnimatedGradientSurface` now consumes theme tokens for gradient paint stops
  and overlay color.
- Data-driven image-channel swatch fills remain explicit exceptions; their
  fallback and stroke colors are tokenized.

Proof result:
- The theme/state inventory now has zero unresolved rows:
  `381 generated-css-token`, `110 generated/test-only`, and
  `17 tokenized-pass-5` for `colors-theme-semantic-roles`;
  `79 generated-css-token` and `1 tokenized-pass-5` for
  `opacity-translucency-disabled`.
- Targeted preview proof artifacts were generated under:
  `/private/tmp/astra-theme-pass-proof`.
- Representative covered surfaces include the header action page, header action
  rail/fallback settings menu, dashboard, parameter pane, closed combo box,
  combo popup/list cells, button states, output pane, styled run-log pane,
  help dialog, selection dialog/list surface, and runtime installer panel.
- Detailed proof ledger:
  `docs/gui-theme-state-token-proof.md`.

Manual review remaining:
- Visual approval of the theme/state appearance remains pending.
  Source-of-truth and selected proof surfaces are tentatively complete.

### Pass 6: Motion / Animation / Gradient Speed

Status: `Tentatively complete / awaiting manual visual review`.

Implementation date: 2026-07-07.

Source result:
- Launcher timing, gradient texture, seam, overlay, feedback, runtime timeout,
  and cancellation durations now have a Java source of truth in
  `LauncherMotionTokens`.
- `AnimatedGradientSurface` preserves the pre-generated texture strategy and
  consumes motion tokens for texture scale, span multiplier, seam overlap,
  texture bounds, dither amplitude, and overlay alpha.
- `PipelineLauncher`, `RuntimeInstaller`, and `StyledLogView` consume motion
  tokens for launcher timers, autosave debounce, runtime installer/probe
  timeouts, copy feedback, and run-log elapsed refresh.
- The run-log fade remains a static viewport-bounded overlay; it is not an
  animated gradient surface.
- JavaFX computed width sentinel use is explicit through
  `Region.USE_COMPUTED_SIZE` instead of local negative numeric literals.

Proof result:
- The motion inventory now has zero unresolved rows:
  `22 tokenized-pass-6` and `5 generated/test-only` for
  `motion-animation-gradient-speed`; `3 tokenized-pass-6` for
  `fades-overlays-masks`.
- Targeted preview proof artifacts were generated under:
  `/private/tmp/astra-motion-pass-proof`.
- `text-contract-sweep.md` reports `1173` text rows with `1173 PASS` and
  `0 FAIL`; gradient rows report `203 PASS` and `0 FAIL`.
- `run-progress-geometry.md` reports zero-delta lane height, text height, bar
  height, and lane gap measurements.
- Detailed proof ledger:
  `docs/gui-motion-gradient-token-proof.md`.

Manual review remaining:
- Visual approval of gradient motion and shimmer polish remains pending. The
  source-of-truth and focused proof contract are tentatively complete.

### Pass 7: Dialogs / Popups / Menus / Lists

Status: `Tentatively complete / awaiting manual visual review`.

Implementation date: 2026-07-07.

Source result:
- The remaining `dialogs-popups-menus-lists` inventory rows were
  preview-harness capture timings, not production GUI geometry.
- `LauncherPreviewApp` now names those timings explicitly for header menu,
  selected-image dialog, and multi-select dialog capture paths.
- Production dialog/list/menu geometry remains covered by the existing
  geometry, control, theme, and typography token paths.
- The operating-system file chooser remains the only expected native exception.

Proof result:
- The dialog/popup inventory now has zero unresolved rows:
  `5 generated/test-only` for `dialogs-popups-menus-lists`.
- Targeted preview proof artifacts were generated under:
  `/private/tmp/astra-dialogs-pass-proof`.
- Multi-select dialog geometry reports zero-delta content insets, content gaps,
  and action-button gap measurements.
- Selected-images dialog geometry now uses a disposable QuPath preview project
  and reports zero-delta title/list gap, content insets, filter gap, dual-list
  gaps, and transfer-button gap measurements under
  `/private/tmp/astra-dialogs-pass-proof-project`.
- Header action rail geometry reports zero-delta button gap measurements.
- The committed transient-surface ownership audit remains:
  `src/test/resources/qupath/ext/astra/gui-transient-surface-audit.csv`.
- Detailed proof ledger:
  `docs/gui-dialog-popup-token-proof.md`.

Manual review remaining:
- Header Project/View fallback menu captures are not materialized in the
  current default header page/ribbon mode and should be revisited only if
  dropdown fallback mode becomes active user-facing UI again.

### Pass 8: CSS / Java Uncategorized Visual Literals

Status: `Tentatively complete / awaiting manual visual review`.

Implementation date: 2026-07-07.

Source result:
- The remaining `css-java-uncategorized-visual-literals` inventory rows were
  intentional JavaFX inline-style reset or base-style restoration paths, not
  independent visual values.
- Empty inline-style reset now routes through `CLEARED_INLINE_STYLE` and
  `clearInlineStyle(Node)`.
- Changed-from-default editor state now uses `BASE_INLINE_STYLE_PROPERTY`,
  `rememberBaseInlineStyle(Node)`, and `restoreBaseInlineStyle(Node)` before
  applying or removing the CSS-owned `astra-editor-changed-from-default` class.

Proof result:
- The uncategorized visual-literal inventory now has zero unresolved rows:
  `7 tokenized-pass-8` for `css-java-uncategorized-visual-literals`.
- Focused source proof is enforced by
  `PipelineLauncherTest.launcherUncategorizedVisualLiteralsUseNamedInlineStylePaths`.
- Detailed proof ledger:
  `docs/gui-uncategorized-visual-literal-proof.md`.

Manual review remaining:
- No rendered screenshot is required for this source-ownership family. Existing
  combo/list/dialog previews remain the visual evidence for surfaces that
  consume the reset helpers.

## Scope
- `src/main/java/qupath/ext/astra/PipelineLauncher.java`
- `src/main/java/qupath/ext/astra/LauncherGeometryTokens.java`
- `src/main/java/qupath/ext/astra/RuntimeInstaller.java`
- `src/main/java/qupath/ext/astra/AnimatedGradientHeader.java`
- `src/main/java/qupath/ext/astra/AnimatedGradientSurface.java`
- `src/main/java/qupath/ext/astra/StyledLogView.java`
- `src/main/java/qupath/ext/astra/GuiPresentation.java`
- `src/main/java/qupath/ext/astra/GuiText.java`
- `src/main/resources/qupath/ext/astra/launcher.css`
- `src/test/java/qupath/ext/astra/LauncherPreviewApp.java`
- `src/test/java/qupath/ext/astra/PipelineLauncherTest.java`
- `src/test/java/qupath/ext/astra/ExtensionContractTest.java`

## Search Commands And Method
- rg CSS numeric visual declarations in launcher.css
- rg Java layout/style/timing calls in PipelineLauncher, RuntimeInstaller, AnimatedGradient*, StyledLogView, Gui*
- structured parser over CSS properties and Java visual literal lines
- classification into family/subfamily/closure-state/proof-needed CSV rows

The CSV inventory is generated from CSS visual declarations and Java GUI/layout/timing literal candidates. Rows are intentionally conservative: a named constant is still flagged when the name or formula may not be a semantic source of truth.

## Inventory Files
- `docs/gui-visual-token-inventory.csv`
- `docs/gui-visual-token-audit.md`

## Counts By Family
| Family | Findings |
| --- | ---: |
| `colors-theme-semantic-roles` | 508 |
| `bevels-radii-arcs-tab-curvature` | 137 |
| `typography-text-ink` | 110 |
| `margins-padding-gaps-spacing` | 99 |
| `opacity-translucency-disabled` | 80 |
| `control-geometry` | 48 |
| `motion-animation-gradient-speed` | 27 |
| `borders-strokes` | 22 |
| `header-footer-tab-shape-metrics` | 20 |
| `css-java-uncategorized-visual-literals` | 7 |
| `dialogs-popups-menus-lists` | 5 |
| `fades-overlays-masks` | 3 |
| `z-layer-clipping-edge-artifacts` | 2 |

## Counts By Classification
| Classification | Findings |
| --- | ---: |
| `generated-css-token` | 736 |
| `generated/test-only` | 194 |
| `already-tokenized` | 48 |
| `tokenized-pass-6` | 25 |
| `tokenized-pass-5` | 18 |
| `tokenized-pass-3` | 18 |
| `tokenized-pass-2` | 10 |
| `tokenized-pass-1` | 10 |
| `tokenized-pass-8` | 7 |
| `tokenized-pass-4` | 2 |

## Counts By Closure State
| Closure State | Findings |
| --- | ---: |
| `closed-generated` | 736 |
| `closed-test-only` | 194 |
| `closed-proven` | 138 |

## Closure Status
- Every visual-token inventory row has a closed state.
- Manual visual review remains an approval status only. It is separate from
  the closed inventory ledger.
- Completed GUI visual-token rows may not carry `high`, `medium`, or `low`
  implementation-state language.

## Family Findings And Proof Strategy

### colors-theme-semantic-roles
- Findings: 508
- Status: `Tentatively complete / awaiting manual visual review`
- Proof generated: `/private/tmp/astra-theme-pass-proof`
- Proof note: theme colors now route through `LauncherThemeTokens` and
  generated CSS color tokens. Data-driven channel swatch fills remain explicit
  exceptions; fallback/stroke colors are tokenized.
- Representative rows:
  - generated CSS color tokens -> `generated-css-token`
  - preview/test-only theme diagnostics -> `generated/test-only`
  - production Java theme constants -> `tokenized-pass-5`

### typography-text-ink
- Findings: 110
- Status: `Tentatively complete / awaiting manual visual review`
- Proof generated: `/private/tmp/astra-typography-pass-proof`
- Proof note: transparent text-contract sweep reports `1173` text rows with
  zero failures. Exact text rail/ink claims use rendered alpha bounds; role
  coverage includes panels, parameter rows, dependent titles, buttons,
  text fields, combo/list cells, fallback menus, dialogs, runtime installer
  surfaces, custom editors, output/log text, and progress diagnostics.
- Representative rows:
  - generated CSS font-stack and font-size mirrors -> `generated-css-token`
  - generated/test preview text diagnostics -> `generated/test-only`
  - runtime elapsed prefix/suffix formatting -> `tokenized-pass-4`

### bevels-radii-arcs-tab-curvature
- Findings: 137
- Status: `Tentatively complete / awaiting manual visual review`
- Proof generated: `docs/gui-bevel-token-proof.md`
- Proof note: semantic bevel, radius, tab-curve, and generated CSS mirror rows
  are closed by Pass 1 source tokens, rendered alpha/path proof, and generated
  token staleness checks.
- Representative rows:
  - generated CSS bevel/radius mirrors -> `closed-generated`
  - preview/test-only bevel diagnostics -> `closed-test-only`
  - production Java bevel/tab formulas -> `closed-proven`

### margins-padding-gaps-spacing
- Findings: 99
- Status: `Tentatively complete / awaiting manual visual review`
- Proof generated: `/private/tmp/astra-spacing-pass-complete`
- Proof note: exact spacing claims in geometry overlays report zero deltas;
  eligible CSS rows now consume generated `launcher.tokens.css` values.
- Representative rows:
  - `GVT-0007` dependent panel left inset -> `already-tokenized`
  - `GVT-0016` rendered menu popup width -> `already-tokenized`
  - `GVT-0033` dashboard grid max width -> `already-tokenized`
  - `GVT-0100` `.astra-button` padding -> `generated-css-token`
  - `GVT-0403` vertical scrollbar padding -> `generated-css-token`

### opacity-translucency-disabled
- Findings: 80
- Status: `Tentatively complete / awaiting manual visual review`
- Proof generated: `/private/tmp/astra-theme-pass-proof`
- Proof note: opacity declarations now route through generated CSS token
  mirrors or the `LauncherThemeTokens.CSS_OPACITY_VALUES` allowlist. Java
  opacity state values are tokenized in `LauncherThemeTokens`.
- Representative rows:
  - generated CSS opacity mirrors -> `generated-css-token`
  - Java disabled-state opacity constant -> `tokenized-pass-5`

### control-geometry
- Findings: 45
- Status: `Tentatively complete / awaiting manual visual review`
- Proof generated: `docs/gui-control-geometry-token-proof.md`
- Proof note: same-family control dimensions are closed by Pass 3 geometry
  tokens/helpers, focused source guards, generated token staleness checks, and
  rendered geometry proof CSVs.
- Representative rows:
  - generated CSS control-size mirrors -> `closed-generated`
  - preview/test-only control diagnostics -> `closed-test-only`
  - production Java control formulas/helpers -> `closed-proven`

### borders-strokes
- Findings: 22
- Status: `Tentatively complete / awaiting manual visual review`
- Proof generated: `docs/gui-bevel-token-proof.md`
- Proof note: border/stroke rows are closed by Pass 2 stroke tokens, generated
  CSS mirrors, and rendered edge/stroke proof.
- Representative rows:
  - generated CSS border/stroke mirrors -> `closed-generated`
  - preview/test-only border diagnostics -> `closed-test-only`
  - production Java border formulas -> `closed-proven`

### motion-animation-gradient-speed
- Findings: 27
- Status: `Tentatively complete / awaiting manual visual review`
- Proof generated: `/private/tmp/astra-motion-pass-proof`
- Proof note: launcher motion and gradient timing now route through
  `LauncherMotionTokens`; `AnimatedGradientSurface` keeps the pre-generated
  texture strategy and consumes named motion tokens for texture/seam/dither
  policy. Focused source tests guard old local timing/gradient literals.
- Representative rows:
  - launcher elapsed/autosave/copy feedback timers -> `tokenized-pass-6`
  - runtime installer command/bootstrap/probe/network/validation/cancel
    timers -> `tokenized-pass-6`
  - gradient texture scale/span/seam/dither/overlay tokens ->
    `tokenized-pass-6`
  - preview-only diagnostic constants -> `generated/test-only`

### header-footer-tab-shape-metrics
- Findings: 20
- Status: `Tentatively complete / awaiting manual visual review`
- Proof generated: `docs/gui-bevel-token-proof.md`
- Proof note: header/footer tab shape rows are closed by live path diagnostics,
  rendered alpha bounds, and source-token guards.
- Representative rows:
  - preview/test-only tab shape diagnostics -> `closed-test-only`
  - production Java tab formulas -> `closed-proven`

### css-java-uncategorized-visual-literals
- Findings: 7
- Status: `Tentatively complete / awaiting manual visual review`
- Proof generated: `docs/gui-uncategorized-visual-literal-proof.md`
- Proof note: rows are intentional JavaFX inline-style reset or base-style
  restoration paths. Empty resets route through `clearInlineStyle(Node)` and
  `CLEARED_INLINE_STYLE`; editor base-style restoration routes through named
  base-style helpers before CSS-owned changed-state classes apply.
- Representative rows:
  - `GVT-1055` `src/main/java/qupath/ext/astra/PipelineLauncher.java:3287` `setStyle` `arrow.setStyle("");` -> `tokenized-pass-8`
  - `GVT-1057` `src/main/java/qupath/ext/astra/PipelineLauncher.java:3297` `setStyle` `selectedCell.setStyle("");` -> `tokenized-pass-8`
  - `GVT-1058` `src/main/java/qupath/ext/astra/PipelineLauncher.java:3322` `setStyle` `cell.setStyle("");` -> `tokenized-pass-8`
  - `GVT-1059` `src/main/java/qupath/ext/astra/PipelineLauncher.java:3334` `setStyle` `setStyle("");` -> `tokenized-pass-8`
  - `GVT-1062` `src/main/java/qupath/ext/astra/PipelineLauncher.java:5573` `setStyle` `box.setStyle("");` -> `tokenized-pass-8`

### dialogs-popups-menus-lists
- Findings: 5
- Status: `Tentatively complete / awaiting manual visual review`
- Proof generated: `/private/tmp/astra-dialogs-pass-proof`
- Proof note: remaining inventory rows are preview-only capture timings and now
  use named `LauncherPreviewApp` constants. Production transient-surface
  ownership remains covered by `gui-transient-surface-audit.csv`.
- Representative rows:
  - header Settings/Project/View menu capture timing -> `generated/test-only`
  - selected-image dialog capture timing -> `generated/test-only`
  - multi-select dialog capture timing -> `generated/test-only`

### fades-overlays-masks
- Findings: 3
- Status: `Tentatively complete / awaiting manual visual review`
- Proof generated: `/private/tmp/astra-motion-pass-proof`
- Proof note: gradient overlay alpha now routes through `LauncherMotionTokens`;
  run-log fade zero-size bounds use `LauncherGeometryTokens.FLUSH`.
- Representative rows:
  - gradient overlay alpha -> `tokenized-pass-6`
  - gradient full alpha blend factor -> `tokenized-pass-6`
  - run-log fade minimum size -> `tokenized-pass-6`

### z-layer-clipping-edge-artifacts
- Findings: 2
- Status: `Tentatively complete / awaiting manual visual review`
- Proof generated: `docs/gui-bevel-token-proof.md`
- Proof note: edge/clip artifact rows are closed by generated CSS mirrors and
  raw-vs-normalized edge audits.
- Representative rows:
  - generated CSS edge/clip mirrors -> `closed-generated`

## Completed Visual-Token Families

All visual-token implementation families are closed in
`docs/gui-visual-token-inventory.csv`. Manual visual review remains because the
user, not Codex, grants final visual approval.

| Family | Closure Summary |
| --- | --- |
| `bevels-radii-arcs-tab-curvature` | `113 closed-generated`, `14 closed-test-only`, `10 closed-proven` |
| `margins-padding-gaps-spacing` | `47 closed-proven`, `42 closed-generated`, `10 closed-test-only` |
| `control-geometry` | `19 closed-test-only`, `18 closed-proven`, `11 closed-generated` |
| `typography-text-ink` | `102 closed-generated`, `6 closed-test-only`, `2 closed-proven` |
| `colors-theme-semantic-roles` | `381 closed-generated`, `110 closed-test-only`, `17 closed-proven` |
| `opacity-translucency-disabled` | `79 closed-generated`, `1 closed-proven` |
| `motion-animation-gradient-speed` | `22 closed-proven`, `5 closed-test-only` |
| `dialogs-popups-menus-lists` | `5 closed-test-only` |
| `fades-overlays-masks` | `3 closed-proven` |
| `borders-strokes` | `9 closed-test-only`, `7 closed-proven`, `6 closed-generated` |
| `header-footer-tab-shape-metrics` | `16 closed-test-only`, `4 closed-proven` |
| `css-java-uncategorized-visual-literals` | `7 closed-proven` |
| `z-layer-clipping-edge-artifacts` | `2 closed-generated` |

## Exemptions
- Native OS/file chooser surfaces remain exempt.
- Dynamic data colors, such as true channel/source/severity values, remain
  allowed only when documented and not layout-defining.
- Test fixture literals may remain only when classified as `closed-test-only`.
- Scientific/runtime values in adjacent files are out of scope for GUI visual
  token normalization.

## Manual Visual Approval
- The implementation ledger is closed.
- Manual visual approval remains pending for the GUI as rendered in QuPath.
- Any future visual defect found during manual review must create a new focused
  implementation row or issue rather than reopening the closed ledger wholesale.

## Master Surface / State Coverage Closure

Generated: 2026-07-03T15:52:00

This closure layer answers a different question than the visual literal scan
above. The visual-token inventory finds magic-number and source-of-truth
candidates. The surface/state coverage matrix verifies that each extension-owned
GUI surface and appearance-changing state maps to one or more visual-token
families and a future proof method before implementation starts.

Coverage artifact:

- `docs/gui-visual-surface-state-coverage.csv`

### Closure Method

Source searches were performed over launcher Java, runtime installer Java,
StyledLogView Java, preview diagnostics, tests, and launcher CSS for:

- GUI construction entry points: `Dialog`, `DialogPane`, `ContextMenu`,
  `MenuButton`, `ComboBox`, `ListView`, `Tooltip`, `PopupWindow`,
  `FileChooser`, `DirectoryChooser`, and custom editor classes.
- Visual mutation entry points: `setStyle`, `setPadding`, `setSpacing`,
  `setMinWidth`, `setPrefWidth`, `setMinHeight`, `setPrefHeight`,
  `setOpacity`, and stateful CSS selectors.
- CSS pseudo/state selectors: `:hover`, `:pressed`, `:selected`,
  `:focused`, `:disabled`, combo-popup rows, context menus, dialog panes,
  list cells, and launcher button states.

The matrix intentionally treats header fallback menus, combo popups,
tooltips, runtime windows, profile dialogs, selected-image dialogs, list cells,
and dialog buttons as extension-owned surfaces. The only native exemption is the
settings-profile `FileChooser`.

### Coverage Counts

| Metric | Count |
| --- | ---: |
| Distinct surfaces | 92 |
| Surface/state rows | 135 |
| `covered` rows | 2 |
| `covered-needs-rendered-proof` rows | 131 |
| `native-exempt` rows | 1 |
| `out-of-scope-scientific` rows | 1 |
| `missing-family` rows | 0 |
| `missing-surface` rows | 0 |
| `needs-human-design-choice` rows | 0 |

### Coverage Verdict

The coverage artifact is ready to guide implementation. No discovered
extension-owned GUI surface is currently missing from the coverage matrix, and no
surface/state row lacks a visual-token family.

This does not mean the GUI is visually complete. It means the implementation
plan now has a surface/state ledger broad enough to prevent hidden GUI regions
from falling out of scope. Almost every row remains
`covered-needs-rendered-proof`; each implementation family must still produce
the required rendered proof before that family can be marked complete.

### Blockers Before Implementation

There are no `missing-surface` or `missing-family` blockers in the current
coverage matrix.

Implementation must still obey these blockers before any family can be closed:

- Do not mark a family complete from JavaFX node bounds alone when rendered ink
  or alpha bounds are relevant.
- Do not treat a QuPath/default-looking dialog as native-exempt merely because
  JavaFX supplies the shell. If the extension owns the interaction, it remains
  extension-owned.
- Do not add a new GUI surface without adding a corresponding
  `gui-visual-surface-state-coverage.csv` row.
- Do not tune values from screenshots; screenshots are acceptance QA only.

## Stopping Status
Audit-only stopping point reached. The visual-token inventory and the
surface/state coverage closure are now separate durable artifacts. The next
authorized implementation phase should choose one family, starting with
bevels/radii/arcs/tab curvature, and perform tokenization plus rendered proof.
Do not begin fixes until user approval.
