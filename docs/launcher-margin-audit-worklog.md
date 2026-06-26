# ASTRA Launcher Margin Audit Worklog

This is the working rail for the final no-compromise GUI margin pass. It is
kept in writing so the pass can survive context compaction without redoing
discovery.

## Contract

- No screenshot-derived constants.
- No magic numbers.
- Every expected spacing value must be a named token or a formula from named
  tokens.
- Borders, strokes, gutters, bars, and popup bounds must be included in the
  formulas when they affect visible whitespace.
- Screenshots are acceptance QA only.
- Scientific settings, defaults, scripts, and runtime behavior are out of
  scope.

## Current Baseline

- Extension HEAD at start of this pass: `beff633 Add launcher margin audit
  diagnostics`.
- Existing diagnostics cover main geometry, focused panels, dashboard cards,
  basic header menu geometry, combo popup geometry, output pane shell geometry,
  all-settings shell geometry, advanced shell geometry, selected image dialog
  geometry, and canonical parameter/dependent rail relationships.
- Remaining task is not broad redesign. It is to close coverage gaps, run
  zero-delta scans, and fix only formula-proven violations.

## Current Closure Inventory

Current aggregate artifact set:

- `/private/tmp/astra-gui-final-margin-pass-v3`
- `/private/tmp/astra-gui-dynamic-margin-closure-v39-colocalization-custom`
- `/private/tmp/astra-gui-dynamic-margin-closure-v40-asset-combo`
- `/private/tmp/astra-gui-dynamic-margin-closure-v41-marker-key-map`
- `/private/tmp/astra-gui-dynamic-margin-closure-v42-channel-populated`
- `/private/tmp/astra-gui-dynamic-margin-closure-v45-header-placement`
- `/private/tmp/astra-gui-dynamic-margin-closure-v46-header-clamp`
- `/private/tmp/astra-gui-dynamic-margin-closure-v50-advanced-deep`
- `/private/tmp/astra-gui-dynamic-margin-closure-v52-dependency-matrix`
- `/private/tmp/astra-gui-dynamic-margin-closure-v55-row-state`
- `/private/tmp/astra-gui-dynamic-margin-closure-v57-list-code-editor`
- `/private/tmp/astra-gui-dynamic-margin-closure-v58-channel-multi-select`
- `/private/tmp/astra-gui-dynamic-margin-closure-v60-typography-optical`
- `/private/tmp/astra-gui-dynamic-margin-closure-v62-output-pane-kill`
- `/private/tmp/astra-gui-dynamic-margin-closure-v63-button-states-all-roles`

Final aggregate artifact folder:
`/private/tmp/astra-gui-dynamic-margin-closure-final-v64`.

It contains an `artifact-index.csv` plus copied CSV/Markdown/PNG outputs for
the current evidence set.

Aggregate status:

- CSV files scanned: `47`.
- Measurement rows scanned: `876`.
- Nonzero deltas found: `0`.
- Current inventory table:
  `docs/launcher-margin-current-inventory.csv`.
  It contains `878` rows: `876` zero-delta measured geometry rows plus
  `2` explicitly `intentionally out of scope` rows for non-rendered warning
  jump controls and typography perception.
- Bullet-level checklist table:
  `docs/launcher-margin-checklist-status.csv`.
  It contains all `232` objective checklist bullets: `223` direct measured
  zero-delta rows, `1` explicitly non-rendered optional control row, and
  `8` typography visual-QA rows.

| Family | Current status | Primary evidence |
| --- | --- | --- |
| Header and top rail | `zero-delta` | `final-margin-pass-v3`, header rail/menu CSVs |
| Header dropdown placement | `corrected`, then `zero-delta` | `v45-header-placement`, `v46-header-clamp` |
| View menu dynamic controls | `zero-delta` | `final-margin-pass-v3/view-menu-geometry.csv` |
| Dashboard and focused navigation | `zero-delta` | `final-margin-pass-v3/*geometry-overlay.csv` |
| Main workspace geometry | `zero-delta` | `final-margin-pass-v3/*geometry-overlay.csv` |
| Parameter rows | `zero-delta` | `v55-row-state`, focused overlay CSVs |
| Dependent panels | `zero-delta` | `v52-dependency-matrix`, focused overlay CSVs |
| Dependency state matrix | `zero-delta` | `v52-dependency-matrix` |
| Combo boxes and popups | `zero-delta` | `final-margin-pass-v3`, `v40-asset-combo` |
| Open-image channel panel | `zero-delta` | `final-margin-pass-v3`, `v42-channel-populated` |
| Custom editors | `zero-delta` | `v39-colocalization-custom`, `v41`, `v57`, `v58` |
| Dialogs and new windows | `zero-delta` | `final-margin-pass-v3`, `v20`, `v21`, `v22` |
| Output pane and StyledLogView | `zero-delta` | `final-margin-pass-v3`, `v8`, `v62-output-pane-kill` |
| Button dynamic states | `zero-delta` | `v63-button-states-all-roles` |
| Advanced-unlocked state | `zero-delta` | `v50-advanced-deep` |
| All Settings mode | `zero-delta` | `final-margin-pass-v3`, `v18` |
| Typography and optical alignment | `intentionally out of scope` for zero-delta text perception; shell is `zero-delta` | `v60-typography-optical` |

## Residual GUI Ownership Closure

This section was added after the launcher checklist was proven internally
complete but a repository-wide GUI scan found surfaces outside the original
launcher inventory.

Residual audit artifacts:

- `docs/gui-residual-margin-audit.csv`
- `docs/gui-css-layout-literals.csv`

Newly classified surfaces:

- Settings profile load uses a native JavaFX `FileChooser`. It is explicitly
  recorded as intentionally native/out of scope unless ASTRA replaces profile
  loading with an owned dialog.
- Run-complete notification uses QuPath `Dialogs.showInfoNotification(...)`.
  It is explicitly recorded as intentionally native/out of scope unless ASTRA
  replaces notifications with an owned surface.
- `RuntimeInstaller` preflight confirmation uses QuPath
  `Dialogs.showYesNoDialog(...)`. It is explicitly recorded as intentionally
  native/out of scope.
- `RuntimeInstaller.InstallProgress` is ASTRA-owned and is now tokenized by
  `InstallerGeometry`, deriving root padding, header row gap, root-to-log gap,
  and window size from `LauncherGeometryTokens`.
- `AnimatedGradientHeader` visible clip geometry now derives from
  `LauncherGeometryTokens.INTRA_PANEL_MARGIN` via `HEADER_CLIP_ARC`. Texture,
  dither, and overlay constants remain named visual-effect tokens rather than
  margin tokens.
- `astra-launcher.css` layout-like numeric declarations are captured in
  `docs/gui-css-layout-literals.csv`. This provenance table classifies each
  current padding/radius/width/height/inset/translate declaration as a mirrored
  Java geometry token, zero reset, shared button grammar token, popup/control
  style token, or visual style token.
- Tooltip popup surfaces are closed by source tokens plus rendered diagnostic
  evidence. The production JavaFX tooltip sites are listed, the CSS styling is
  token mirrored, and the preview harness captures an ASTRA-owned tooltip-style
  popup because native `Tooltip` scene exposure is not stable in headless
  preview.

Current residual completion state:

- Native chooser/notification/confirmation surfaces: classified as
  intentionally native/out of scope.
- RuntimeInstaller progress window: source-tokenized and rendered through the
  preview harness with zero-delta scene size, root padding, and gap rows.
- AnimatedGradientHeader clip radius: source-tokenized.
- CSS layout literals: source-classified with a provenance table.
- Tooltip popups: tokenized and proven by zero-delta tooltip-style popup
  diagnostics.

## Margin Families To Audit

### Already Covered By Existing Diagnostics

- Main workspace outer margins.
- Input pane, scrollbar bar, and output pane spacing.
- Header-to-workspace spacing.
- Pane-to-bottom action bar spacing.
- Run button right/bottom rails.
- Channel/settings/advanced vertical stack gap.
- Focused panel outer inset and grid padding.
- Parameter row gaps.
- Independent/dependent bar/text/help/editor rails.
- Dependent panel left/right geometry.
- Dashboard card gaps and internal left padding.
- Help dialog high-level detail shell spacing.

### Historical Coverage Gaps Closed During This Pass

- Simple header menu rows for `Settings` and `Project`.
- Header adaptive dropdown placement at the right edge and narrowed widths.
- Combo box arrow/right rail and popup list width.
- Output log card internals: source tabs, source blocks, message cards,
  key-value cards, command cards, severity badges, timeline rows, and copy
  button rail.
- All Settings collapsible headers: arrow/title rails, header padding,
  header-to-content gap, and section-to-section gap.
- Advanced-unlocked internal groups and long-list behavior.
- Channel panel chips, swatches, and empty-state spacing.
- Custom editors: stage selector, project image selector, multi-select lists,
  marker/key maps, and colocalization check rows.
- Dialog variants beyond selected-image selection: parameter help, alerts,
  text input, settings profile dialogs, and list dialogs.
- Typography/baseline optical alignment where node bounds may be equal but
  rendered text still reads off.

## Checkpoint Log

- Started final pass by inventorying launcher source, existing diagnostics,
  geometry tokens, and StyledLogView geometry.
- Added diagnostic coverage hooks for:
  - simple `Settings` / `Project` header menu row geometry;
  - channel panel title/chip/empty-state geometry;
  - synthetic StyledLogView internals, including status card, source tabs,
    source blocks, line accents, message cards, and copy button rails.
- These are diagnostic additions only. No runtime or scientific behavior was
  changed at this checkpoint.
- Header menu preview revealed a real formula distinction between the menu
  content width and the popup shell width. Added `MENU_POPUP_WIDTH` and
  `SIMPLE_MENU_ITEM_SHELL_INSET` so Settings, Project, and View dropdowns are
  measured against their actual shell geometry. Re-ran
  `header-menus-geometry`; all header menu CSVs are zero-delta.
- Styled log preview initially measured a badge-bearing warning row as though
  it were a plain line. Added `astra-log-line-row` and changed the diagnostic
  to measure the first non-badge row for the universal accent-to-text gap.
  Re-ran `styled-log-geometry`; all StyledLogView measurements are zero-delta.
- Ran targeted preview modes into
  `/private/tmp/astra-gui-final-margin-pass-v3`:
  `header-menus-geometry`, `combo-popup-geometry`,
  `styled-log-geometry`, `channel-panel-geometry`,
  `all-settings-geometry`, `advanced-geometry`,
  `custom-controls-geometry`, `output-pane-geometry`, and
  `dialogs-geometry`.
- Full CSV delta scan across those generated artifacts found no nonzero
  deltas.
- Ran the broader `geometry-overlay` sweep into the same artifact folder. This
  added dashboard, Run Setup, Images & Scope, Models, Segmentation, and
  Parameter Help dialog measurement CSVs. These covered main workspace margins,
  focused-panel insets, dashboard card gaps, dependency-panel rails, global
  `?` alignment, help-dialog section spacing, help-detail accent width, and
  help-detail card gaps.
- Final aggregate CSV scan across all generated artifacts in
  `/private/tmp/astra-gui-final-margin-pass-v3` found no nonzero deltas.

## Current Coverage Notes

- Proven zero-delta in the aggregate current preview artifacts:
  - main workspace outer margins, pane/bar/output spacing, and bottom action
    rail spacing;
  - dashboard card column/row gaps and card internal padding;
  - Run Setup, Images & Scope, Models, and Segmentation focused-panel insets,
    parameter grid rails, row gaps, dependent-panel rails, and global `?`
    alignment;
  - header action rail and header menu popup/content rows;
  - closed combo and combo popup row rails;
  - output pane shell and status-card rails;
  - StyledLogView status, copy button, source tab/block, plain log row, and
    message-card rails;
  - All Settings section content padding, row gap, and help rail alignment;
  - advanced outer routine-to-advanced gap and opened advanced-section rows;
  - custom-control section shell padding, row gap, and bespoke editor states;
  - channel-panel empty-state title/gap spacing;
  - populated channel-chip spacing, swatch size, swatch-to-label gap, and
    chip-to-chip gap;
  - selected-image dialog content inset, title-to-list spacing,
    filter-to-chooser gap, dual-list gaps, and transfer-button gap;
  - multi-select dialog content inset, filter/list gap, list/action gap, and
    action-button gap;
  - ASTRA-owned settings-profile, reset, provisional, and run-failure dialog
    content insets and title/body gaps;
  - StyledLogView disclosure-button rail and expanded hidden-body gap;
  - Parameter Help dialog section spacing, detail accent width, detail-card
    right inset, and detail-card vertical gaps.
- Typography optical baseline perception is intentionally visual QA, not a
  zero-delta margin invariant. The representative typography shell itself is
  measured and zero-delta in `v60-typography-optical`.

## Source Inventory Expansion

The audit is not capped. A source pass over launcher-owned JavaFX construction
found additional margin families that must remain explicit rather than hiding
under broad custom-editor wording:

- Colocalization setup semantic cards:
  - semantic-card outer padding;
  - semantic-card title-to-subtitle gap;
  - semantic-card-to-semantic-card gap;
  - target model group outer padding;
  - target model group title-to-row gap;
  - target model group invalid-model warning gap;
  - colocalization target row label rail;
  - display-check selector row rail;
  - threshold row enabled/disabled geometry.
- Channel checkbox editors:
  - channel-checkbox editor title-to-button gap;
  - channel-checkbox selector-to-summary gap;
  - empty channel checkbox state;
  - populated channel checkbox state;
  - channel checkbox summary wrap rail.
- Colocalization check editor:
  - check-row nested-panel inset;
  - check-row top row gap;
  - check name field rail;
  - compartment combo rail;
  - delete button rail;
  - check-row selector row gap;
  - check channels field rail;
  - threshold exclusions field rail;
  - add-check button rail;
  - empty initial check state;
  - populated multi-check state.
- Marker key map editor:
  - marker-key nested-panel inset;
  - empty marker-key message padding;
  - populated marker-key row gap;
  - marker-key label rail;
  - marker-key input rail;
  - numeric marker-value state;
  - text marker-value state.
- List and code editors:
  - list editor text-field rail;
  - list editor hint gap;
  - list editor restore-button rail;
  - code editor text-area rail;
  - code editor text-area scrollbar gutter;
  - code editor hint gap;
  - code editor restore-button rail;
  - structured editor disabled/readable state if dependent.
- Ungrouped section:
  - ungrouped section left label rail;
  - ungrouped section editor rail;
  - ungrouped section horizontal gap;
  - ungrouped section vertical row gap.
- Dialog and alert variants:
  - provisional vascular automation alert padding;
  - reset image alert padding;
  - reset project alert padding;
  - run configuration error dialog padding;
  - settings profile save text-input content padding;
  - settings profile load dialog/list padding;
  - dialog OK/Cancel button rail;
  - dialog Apply/Cancel button rail.
- Asset-backed controls:
  - asset dropdown empty prompt rail;
  - asset dropdown populated value rail;
  - asset dropdown long project-relative label clipping/wrap behavior;
  - invalid saved-model folder warning rail;
  - pixel-classifier dropdown row rail.
- StyledLogView internals closed by the synthetic rich-log diagnostic:
  - key-value row key rail;
  - key-value row value rail;
  - command-card title rail;
  - command-card text rail;
  - metric badge gap;
  - timeline rail row gap;
  - timeline dot-to-label gap;
  - timeline label-to-duration gap;
  - failure-summary title/message/advice rails;
  - failure advice label/value gap;
  - copied-state Copy All button geometry.

Checkpoint:
- Added a synthetic rich StyledLog diagnostic state that renders key-value
  cards, command cards, metric badges, timeline content, a failure-summary
  card, and Cellpose disclosure controls.
- The first deep StyledLog run exposed an invalid diagnostic: metric badge gap
  was measured across two unrelated badge rows, producing a negative distance.
  Added a stable `.astra-log-metric-row` style class and changed the probe to
  measure only sibling badges inside a single metric row.
- Re-ran `./gradlew test --tests qupath.ext.astra.PipelineLauncherTest` and
  `styled-log-geometry` into
  `/private/tmp/astra-gui-dynamic-margin-closure-v8`.
- `styled-log-geometry.csv` is zero-delta for log view insets, status title,
  Copy All rail, source tab rail, source block accent rail, line accent width,
  line accent-to-text gap, message-card title rail, key-value key/value rails,
  command-card rails, metric badge gap, timeline dot width, timeline
  dot-to-label gap, failure-summary title rail, failure advice label/value
  rails, and disclosure-button rail.
- Header adaptive placement was re-probed with the rendered menu-box
  diagnostic. An early trial runtime formula that placed against
  `MENU_POPUP_WIDTH + shell inset * 2` did not close the invariant and was
  backed out because it was not proven. This historical failed probe is now
  superseded by the later `v45` / `v46` rendered-menu-box diagnostics, which
  close adaptive placement at zero-delta.
- Added `button-states-geometry` to measure normal-vs-hover, normal-vs-pressed,
  and normal-vs-disabled bounds for visible representative button roles:
  `Run`, `Cancel`, header menu, small button, help button, and output copy
  button where present.
- The first button-state preview found a real dynamic geometry failure:
  shared hover styling expanded button bounds by `12px` width and `11px`
  height, while pressed state shifted buttons vertically by `1px`.
- Fixed the shared button grammar by removing the geometry-expanding hover
  drop shadow, moving `-fx-background-insets: 0` to the base `.astra-button`
  rule, and removing the pressed-state Y translation. Added source assertions
  so role-specific hover/pressed drift and the old drop-shadow/translate
  behavior cannot return.
- Re-ran `button-states-geometry` into
  `/private/tmp/astra-gui-dynamic-margin-closure-v14`; all measured hover,
  pressed, and disabled bounds are zero-delta for the visible representative
  button roles.
- Added a structural `.astra-collapsible-section` class so All Settings
  collapsible wrappers can be measured directly instead of inferred from
  child nodes.
- The first collapsible-header diagnostic exposed a dead
  `COLLAPSIBLE_HEADER_WIDTH_ADJUSTMENT` path: JavaFX did not honor that
  preferred-width binding in the StackPane layout, so the diagnostic produced
  false `-4px` deltas. Removed the unused adjustment constant/binding and
  made the header contract explicit: header content begins after
  `SURFACE_BORDER_WIDTH`, while title and arrow rails are
  `COLLAPSIBLE_HEADER_HORIZONTAL_INSET + SURFACE_BORDER_WIDTH`.
- Re-ran `all-settings-geometry` into
  `/private/tmp/astra-gui-dynamic-margin-closure-v18`; collapsible header
  content left/right inset, title rail, arrow rail, arrow width,
  header-to-content join, section-to-section gap, section padding, row gap,
  and help/editor alignment are all zero-delta.
- Reconfirmed that the audit has no numeric ceiling. The earlier "100" was an
  example size only; additional margin families must keep being added whenever
  source inventory or live state reveals them.
- Converted the settings-profile name prompt from native `TextInputDialog` to
  an ASTRA-owned `Dialog<String>` using `SelectionGeometry` for the section
  title to field gap and content inset. This keeps the runtime profile prompt
  in the same geometry source-of-truth as the other launcher-owned dialogs.
- Added `settings-profile-dialog-geometry` and ran it into
  `/private/tmp/astra-gui-dynamic-margin-closure-v20`; content left/right
  inset and section-title-to-input gap are all zero-delta.
- Converted reset and provisional vascular confirmation prompts from native
  `Alert` instances to ASTRA-owned confirmation dialogs using
  `SelectionGeometry` content inset and content gap formulas.
- Added `reset-alert-geometry` and `provisional-alert-geometry`; ran them into
  `/private/tmp/astra-gui-dynamic-margin-closure-v21-reset` and
  `/private/tmp/astra-gui-dynamic-margin-closure-v21-provisional`. Both
  confirmation dialog content left/right inset and title-to-body gap are
  zero-delta.
- Replaced remaining launcher `Dialogs.showErrorMessage` calls with ASTRA-owned
  message dialogs using the same `.astra-dialog-owned-content` shell. Added
  `run-failure-dialog-geometry` and ran it into
  `/private/tmp/astra-gui-dynamic-margin-closure-v22-run-failure`; content
  left/right inset and title-to-body gap are zero-delta.

## Dynamic / Stateful Margin Audit

This section tracks margins that can change after a state transition, action,
popup open, dialog open, or custom-editor interaction. It is intentionally
stricter than the static margin contract.

### Dynamic Surfaces Proven By Current Artifacts

- Dashboard -> focused card transitions:
  - `dashboard-geometry-overlay`, `run-setup-geometry-overlay`,
    `images-scope-geometry-overlay`, `models-geometry-overlay`, and
    `segmentation-geometry-overlay` prove that focused views keep workspace
    margins, pane/bar/output spacing, panel insets, parameter grid rails, row
    gaps, and dependent-panel rails at zero-delta.
- Header dropdown open states:
  - `settings-menu-geometry`, `project-menu-geometry`, and
    `view-menu-geometry` prove menu popup width, simple menu item shell inset,
    simple label inset, View menu panel width, View menu panel insets, and
    View segmented button gaps/widths.
- Header dropdown adaptive placement states:
  - `header-menu-left-default-geometry`,
    `header-menu-right-overflow-geometry`, and
    `header-menu-clamp-too-narrow-geometry` prove left-default,
    right-on-overflow, and too-narrow clamp placement against
    `preferredHeaderMenuX(...)` using actual popup-window captures.
- Combo popup open state:
  - `closed-combo-geometry` and `combo-popup-geometry` prove closed combo cell
    inset, popup row inset, popup list-to-row inset, and contiguous row gap.
- Run-log rendered state:
  - `output-pane-geometry` and `styled-log-geometry` prove output shell rails,
    status-card rails, copy-button rail, source-tab rail, source-block line
    accent rail, log accent width, log accent-to-text gap for plain rows, and
    message-card title rail.
- Parameter Help popup:
  - `help-dialog-geometry-overlay` proves help section gaps, details accent
    width, details card right inset, and detail-card vertical gap.
- Selected image dialog:
  - `selected-images-dialog-geometry` proves content inset, title-to-list
    gap, filter-to-chooser gap, dual-list gaps, and transfer-button gap.
- Multi-select dialog:
  - `multi-select-dialog-geometry` proves content inset, filter/list gap,
    list/action gap, and action-button gap.
- StyledLogView Cellpose detail states:
  - `styled-log-geometry` and `styled-log-expanded-geometry` prove the
    collapsed disclosure-button rail and expanded hidden-body gap.
- Button dynamic states:
  - `button-states-geometry` proves normal, hover, pressed, and disabled
    bounds are stable for visible representative launcher button roles.
- All Settings collapsible sections:
  - `all-settings-geometry` proves collapsible header content rails, title
    rail, arrow rail, arrow width, header/content join, section gap, section
    padding, parameter row gap, and help/editor column alignment.
- Settings profile text-input dialog:
  - `settings-profile-dialog-geometry` proves the profile prompt content inset
    and title-to-input gap against `SelectionGeometry`.
- Reset and provisional confirmation dialogs:
  - `reset-alert-geometry` and `provisional-alert-geometry` prove confirmation
    dialog content inset and title-to-body gap against `SelectionGeometry`.
- Run-failure and generic ASTRA error dialogs:
  - `run-failure-dialog-geometry` proves the run-failure dialog content inset
    and title-to-body gap. Source checks confirm `PipelineLauncher` no longer
    uses native `Dialogs.showErrorMessage`, `Alert`, or `TextInputDialog` for
    launcher-owned message surfaces.
- Advanced-unlocked opened section state:
  - `advanced-geometry` in
    `/private/tmp/astra-gui-dynamic-margin-closure-v50-advanced-deep`
    proves the routine-to-advanced panel gap plus opened advanced section
    content left/right padding and parameter row gap after unlock, after
    switching the advanced navigator to All Settings and scrolling to the
    visible advanced section.
- Dependency state matrix:
  - `dependency-matrix-geometry` in
    `/private/tmp/astra-gui-dynamic-margin-closure-v52-dependency-matrix`
    proves every registry-declared dependency panel in both enabled and
    disabled states through the production `createDependentPanel(...)` and
    canonical parameter-row factory.
  - Covered registry tokens are `selected-images`,
    `threshold-selected-images`, `nucleus-model-source`,
    `cell-model-source`, `nucleus-parameter-source`,
    `cell-parameter-source`, `generic-parameter-source`,
    `positivity-method`, `threshold-mode`, `background-mode`,
    `batch-options`, `failure-recovery`, `custom-segmentation`, and
    `nucleus-gating`.
  - The matrix proves case insets, case title-to-panel gap, dependent title
    rail, title/reason/row vertical gaps, row-grid border insets, dependent row
    left padding, help-to-editor gap, and dependent editor left rail. Right
    edge filling is intentionally not a universal editor invariant because
    checkboxes and other compact controls do not fill the editor column.
- Row state and tall-editor alignment:
  - `row-state-geometry` in
    `/private/tmp/astra-gui-dynamic-margin-closure-v55-row-state` proves
    enabled/disabled row rails, disabled row opacity, single-line row
    centering, and tall structured-editor top alignment through the production
    parameter-row factory.
  - The tall-row anchor/help inset formula includes JavaFX device-pixel
    snapping with `Window.getOutputScaleY()`, closing the earlier sub-pixel
    offset without screenshot-derived constants.
- List and code structured editors:
  - `list-code-editor-geometry` in
    `/private/tmp/astra-gui-dynamic-margin-closure-v57-list-code-editor`
    proves the standalone `ListEditor` and `CodeEditor` internals that are not
    fully covered by the shared parameter-row shell.
  - Covered rails/gaps are list field left/right rails, list field-to-hint
    gap, list hint rail, list hint-to-restore gap, list restore-button left
    rail, list-to-code editor gap, code text-area left/right rails, code
    text-area-to-hint gap, code hint rail, code hint-to-restore gap, code
    restore-button left rail, and code text-area scrollbar right rail.
  - The code text-area scrollbar right rail is explicitly
    `SURFACE_BORDER_WIDTH * 2`, matching the two ASTRA TextArea border
    surfaces rather than assuming the scrollbar is flush to the TextArea
    outer bounds.
  - Aggregate CSV nonzero-delta scan across the artifact folder returned no
    rows.
- Channel checkbox and multi-select editors:
  - `channel-multi-select-geometry` in
    `/private/tmp/astra-gui-dynamic-margin-closure-v58-channel-multi-select`
    proves the shared `MultiSelectListEditor` path used by stage-style
    selectors and channel checkbox selectors.
  - The diagnostic renders and measures:
    - a populated titled `ChannelCheckboxEditor`;
    - an empty titled `ChannelCheckboxEditor`;
    - an untitled `MultiSelectListEditor` matching the stage-selector shape.
  - Covered rails/gaps are root left/right inset, editor-to-editor vertical
    gap, title-to-selector gap, selector left/right rails, selector-to-summary
    gap, summary left rail, and untitled selector top rail.
  - Aggregate CSV nonzero-delta scan across the artifact folder returned no
    rows.

### Dynamic Surfaces Structurally Covered And State-Probed

- Custom editors:
  - Stage selector, project image selector, marker/key map editors,
    multi-select/list editors, channel checkbox editors, and colocalization
    check rows share the canonical parameter-row or nested-panel shell.
  - Stage/multi-select dialog internals, project-image dual-list internals,
    colocalization check rows, marker/key maps, list/code structured editors,
    and channel/multi-select editors are now forced into populated, empty, or
    untitled visual states and recorded as zero-delta checkpoints.
  - No custom-editor margin family is currently listed as merely
    structurally covered without a state probe. New custom editors must be
    added to this worklog and given an explicit diagnostic surface.

### Dynamic Surfaces Still Unchecked

- None currently listed.

Typography optical baselines are intentionally not converted into fake
zero-delta margin math. Font fallback, glyph metrics, baseline offsets, and
anti-aliasing are renderer-dependent. The representative typography surface is
therefore covered by a visual QA artifact, while its panel shell spacing is
still measured by named geometry tokens.

## Canonical Dynamic Closure Checklist

This is the active checklist for the next closure pass. Every item must end as
`zero-delta`, `corrected`, `blocked`, or `intentionally out of scope`.
The checklist is not capped. It currently contains 225 individual bullet-level
audit lines across 17 families, and new dynamic states must be appended rather
than collapsed into a reassuring family name.

### 1. Header And Top Rail

- Settings menu closed button internal padding.
- Project menu closed button internal padding.
- View menu closed button internal padding.
- Header menu chevron rail.
- Header menu label-to-chevron gap.
- Header menu button-to-button gap.
- Header action rail outer padding.
- Header action rail tab-to-box join.
- Header action rail border/radius consistency.
- Header title block to action rail spacing.
- Header pipeline breadcrumb spacing.
- Header breadcrumb chip padding.
- Header breadcrumb separator spacing.
- Header gradient area to workspace margin.

### 2. Header Dropdown Dynamic Placement

- Settings dropdown left-aligned default state.
- Project dropdown left-aligned default state.
- View dropdown left-aligned default state.
- Settings dropdown right-on-overflow state.
- Project dropdown right-on-overflow state.
- View dropdown right-on-overflow state.
- Dropdown clamp when launcher is too narrow.
- Dropdown minimum readable width.
- Dropdown internal row width after alignment switch.
- Dropdown vertical growth/scrolling if constrained.
- Dropdown border-to-row inset.
- Dropdown row-to-row spacing.
- Dropdown disabled item padding.
- Dropdown selected/active item padding.

### 3. View Menu Dynamic Controls

- Run Log Pane `Show` segment padding.
- Run Log Pane `Hide` segment padding.
- Static/Dynamic gradient segment padding.
- Speed segment padding.
- Segment label rail.
- Segment label-to-control gap.
- Segment button-to-button gap.
- Segment row-to-row gap.
- View panel inner padding.
- View panel outer menu shell padding.

### 4. Dashboard And Focused Navigation

- Dashboard/All Settings toggle spacing.
- Dashboard/All Settings selected-state geometry.
- Back to Dashboard button rail.
- Focused view badge/title/subtitle rail.
- Focused view title block to nav controls spacing.
- Focused view header band padding.
- Focused view header-to-parameter-grid gap.
- Dashboard card grid column gap.
- Dashboard card grid row gap.
- Dashboard card internal padding.
- Dashboard card badge/title/subtitle spacing.
- Dashboard card vertical centering.
- Dashboard three-column default width.
- Dashboard two-column responsive breakpoint.
- No horizontal scrollbar at default width.

### 5. Main Workspace Geometry

- Header-to-workspace gap.
- Left outer workspace margin.
- Right outer workspace margin.
- Input pane to scrollbar visible bar.
- Visible bar to output pane.
- Scrollbar gutter width.
- Scrollbar thumb width.
- Scrollbar side padding.
- Input pane bottom to output pane bottom.
- Pane bottom to action buttons.
- Action buttons to window bottom.
- Run button to right edge.
- Cancel-to-Run button gap.
- Input panel vertical stack gap.
- Channel/settings/advanced panel gaps.

### 6. Parameter Rows

- Independent row left padding.
- Independent gradient bar left rail.
- Independent gradient bar width.
- Independent bar-to-text gap.
- Independent label-to-help gap.
- Help button column rail.
- Help button size.
- Help-to-editor gap.
- Editor right rail.
- Single-line row vertical centering.
- Multi-line row first-line anchoring.
- Explanatory text below editor spacing.
- Disabled row opacity without geometry drift.
- Tall editor row top alignment.

### 7. Dependent Panels

- Dependent panel outer left margin.
- Dependent panel outer right margin.
- Dependent panel title rail.
- Dependent panel reason rail.
- Dependent title-to-reason gap.
- Dependent reason-to-rows gap.
- Dependent inner row grid padding.
- Dependent row gradient bar rail.
- Dependent bar-to-text gap.
- Dependent help rail.
- Dependent editor rail.
- Disabled dependent row readability and geometry.
- Enabled dependent row geometry.
- Controller row to dependent panel gap.

### 8. Dependency State Matrix

- Selected image controls.
- Threshold selected image controls.
- Nucleus model-source controls.
- Cell model-source controls.
- Nucleus parameter-source controls.
- Cell parameter-source controls.
- Threshold mode controls.
- Background mode controls.
- Custom segmentation controls.
- Pixel scaling controls.
- Failure recovery controls.
- VSMC nucleus-gating controls.
- Colocalization threshold/dependency controls.
- Any remaining registry-declared dependency panel.

### 9. Combo Boxes And Popups

- Closed combo text inset.
- Closed combo arrow rail.
- Closed combo right padding.
- Closed combo disabled text inset.
- Popup shell width.
- Popup border-to-row inset.
- Popup row text inset.
- Popup selected row padding.
- Popup hover row padding.
- Popup disabled row padding.
- Popup row height.
- Popup scrollbar gutter if long.
- Asset-backed dropdown empty state.
- Asset-backed dropdown populated state.

### 10. Open-Image Channel Panel

- Channel panel title inset.
- Channel panel title-to-chip gap.
- Channel chip row gap.
- Channel chip internal padding.
- Channel swatch left rail.
- Channel swatch width.
- Swatch-to-label gap.
- Chip-to-chip gap.
- No-image empty-state spacing.
- Open-image populated-state spacing.

### 11. Custom Editors

- Stage selector button padding.
- Stage selector summary spacing.
- Project image selector summary spacing.
- Project image selector action button row gap.
- Paste Image Names button rail.
- Multi-select editor title rail.
- Multi-select button rail.
- Multi-select summary rail.
- Channel checkbox editor shell spacing.
- Marker/key map empty-state padding.
- Marker/key map populated row padding.
- Marker/key map key label rail.
- Marker/key map value field rail.
- Colocalization check row outer padding.
- Colocalization check top-row gaps.
- Colocalization check selector row gaps.
- Colocalization add/delete button rails.

### 12. Dialogs And New Windows

- Parameter Help dialog shell padding.
- Parameter Help title/subtitle spacing.
- Parameter Help summary card padding.
- Parameter Help summary label/value rails.
- Parameter Help quick-help spacing.
- Parameter Help details header spacing.
- Parameter Help details accent rail.
- Parameter Help details card padding.
- Parameter Help details card-to-card gap.
- Parameter Help scrollbar gutter.
- Selected image dialog filter margin.
- Selected image dialog dual-list gap.
- Selected image dialog transfer button column.
- Selected image dialog list label rail.
- Selected image dialog OK/Cancel rail.
- Multi-select dialog filter/list/action/hint spacing.
- Settings profile text-input dialog content padding.
- Reset image confirmation alert padding.
- Reset project confirmation alert padding.
- Run failure alert padding.
- Any launcher-owned list dialog padding.

Checkpoint:
- Strengthened the selected-image dialog probe so it now measures dialog
  content left/right inset, filter-to-chooser gap, available-list to transfer
  column gap, transfer-column to selected-list gap, transfer button vertical
  gap, and label-to-list gap. These measurements use existing
  `SelectionGeometry` formulas only.
- Ran `dialogs-geometry` into
  `/private/tmp/astra-gui-dynamic-margin-closure-v1`. The strengthened
  selected-image dialog measurements were all zero-delta:
  content left/right inset `12`, filter-to-chooser gap `9`, dual-list gaps
  `12`, transfer button vertical gap `8`, and label-to-list gap `5`.
- Added `multi-select-dialog-geometry` for the stage/multi-select dialog and
  ran it into `/private/tmp/astra-gui-dynamic-margin-closure-v1`. The dialog
  content left/right inset, filter/list gap, list/action vertical gap, and
  `Select All` / `Clear` action button gap were all zero-delta against
  `SelectionGeometry`.
- Added a stable `.astra-log-hidden-body` class and
  `styled-log-expanded-geometry` for expanded Cellpose details. The preview
  exposed and fixed a real disclosure-button bug: old detail buttons captured
  mutable `currentHiddenBody` / `currentHiddenToggle` fields that could later
  be reset to `null`. The handler now captures the created hidden body and
  toggle as local values. Collapsed disclosure-button left inset and expanded
  disclosure-to-hidden-body gap are zero-delta against `StyledLogView`
  formulas.
- Added raw adaptive-placement rows to `header-menus-geometry` so header
  dropdowns report launcher bounds, button bounds, popup width, popup window
  X, popup anchor X, and candidate left/right placement values. This exposed
  the pre-fix coordinate-frame mismatch while header menu row internals
  remained zero-delta:
  - Settings menu in
    `/private/tmp/astra-gui-dynamic-margin-closure-v6/settings-menu-geometry.csv`
    reports popup window X `12px` right of `preferredHeaderMenuX(...)`, and
    `PopupWindow.anchorX` `24px` right of that preferred value.
  - Project menu in
    `/private/tmp/astra-gui-dynamic-margin-closure-v6/project-menu-geometry.csv`
    shows the same `12px` window delta and `24px` anchor delta.
  - View menu in
    `/private/tmp/astra-gui-dynamic-margin-closure-v6/view-menu-geometry.csv`
    reports a `24px` window delta and `36px` anchor delta.
- The zero-delta rows for menu item inset, label inset, View panel width,
    panel inset, and segment button spacing remain valid. At this historical
    checkpoint, the unresolved item was adaptive popup placement, where the
    desired ASTRA edge
    must be defined against the rendered header menu box rather than mixed
    JavaFX popup window/root/anchor coordinates.

Checkpoint:
- Closed the populated channel-panel chip/swatch gap without requiring a real
  open image by adding `channel-panel-populated-geometry`, a synthetic preview
  window that calls the production `PipelineLauncher.createChannelPanel(...)`
  method with real `ImageChannel.getInstance(...)` values.
- Added `.astra-channel-chip-swatch` to the swatch node so the diagnostic no
  longer relies on a fake `.rectangle` lookup.
- The populated diagnostic initially exposed real subpixel stroke behavior:
  an outside swatch stroke made the rendered swatch `0.5px` wider than the
  named swatch size and shifted vertical centering by `0.25px`.
- Fixed this by naming `CHANNEL_SWATCH_STROKE_WIDTH = SURFACE_BORDER_WIDTH /
  2.0` and setting the swatch stroke type to `StrokeType.INSIDE`; the visible
  chip keeps its stroke, but the swatch obeys `CHANNEL_SWATCH_SIZE`.
- Re-ran `channel-panel-populated-geometry` into
  `/private/tmp/astra-gui-dynamic-margin-closure-v25-channel-populated`.
  All rows are zero-delta:
  title insets, child gap, chip-to-chip gap, chip left/right insets,
  swatch size, centered swatch top inset, and swatch-to-label gap.

Historical stopping checkpoint before context exhaustion:
- The active focus at that earlier checkpoint was header adaptive dropdown
  placement.
- Header menu internals remain proven zero-delta: action rail tab join,
  header button content inset, menu item shell insets, simple label inset,
  View panel width, panel insets, and segment button widths/gaps.
- `header-menus-geometry` in
  `/private/tmp/astra-gui-dynamic-margin-closure-v26-header-menus` and
  `/private/tmp/astra-gui-dynamic-margin-closure-v27-header-menus` confirmed
  that the then-unresolved family was specifically visible menu-box X
  placement for the rendered popup near the right edge.
- Production code now performs a second placement pass after render:
  `renderedHeaderMenuBounds(menu)` obtains the rendered menu width when
  available, `preferredHeaderMenuX(...)` computes left-default/right-on-
  overflow placement from launcher bounds and button bounds, and
  `menu.setX(alignedX)` writes the preferred visible menu X.
- A trial shell-offset correction was intentionally backed out because the
  diagnostic showed JavaFX `ContextMenu.setX(...)` should receive the visible
  menu-box target X, not a manually shifted popup-shell X.
- This stop-point instruction is superseded by the later `v45` / `v46`
  adaptive-placement closure, where Settings, Project, and View all report
  zero-delta for rendered menu-box adaptive X placement.

Checkpoint:
- Closed header adaptive dropdown placement using named JavaFX popup geometry
  rather than a pixel nudge.
- The diagnostic proved that the visible popup box is wider than the declared
  context-menu content width. Production now names that relationship as:
  `MENU_RENDERED_POPUP_WIDTH = MENU_POPUP_WIDTH + (MENU_EDGE_MARGIN * 2.0)`.
- The diagnostic also proved that the initial `ContextMenu.show(...)` anchor
  is offset from the visible popup window by one menu edge margin. Production
  now names that as `MENU_ANCHOR_TO_WINDOW_OFFSET = MENU_EDGE_MARGIN` and
  opens the menu at `preferredVisibleX + MENU_ANCHOR_TO_WINDOW_OFFSET`.
- A post-show correction still runs, but it uses
  `popupWindow.getWidth()` with `MENU_RENDERED_POPUP_WIDTH` as the minimum
  width and writes `menu.setX(alignedX)`. The `ContextMenu.show(...)` call
  needs the anchor offset; the post-show `setX(...)` path operates in popup
  window coordinates and must not add the offset a second time.
- Earlier `header-menus-geometry` runs proved Settings, Project, and View menu
  row internals and View segment rows are zero-delta. The later adaptive
  placement closure below supersedes the older placement trial artifacts.

Checkpoint:
- Added explicit diagnostic hooks for colocalization-specific custom editor
  surfaces that previously hid behind the broad `custom editors` family:
  semantic cards, labeled rows, model-source cards, channel checkbox editors,
  populated colocalization check rows, nested fields, marker-key map editors,
  and multi-select editors.
- Added `colocalization-custom-geometry` preview coverage. The preview opens
  the real colocalization launcher, focuses `Colocalization Setup`, clicks
  `Add check` to force the populated check-row state, and captures top,
  middle-scroll, and lower-scroll geometry artifacts.
- First run exposed two formula issues, not layout-token edits:
  bordered semantic/model/nested surfaces report visible child insets as their
  content token plus `SURFACE_BORDER_WIDTH`; populated check rows use the same
  bordered-surface equation. The diagnostic formulas were corrected to include
  the border width explicitly.
- Re-ran `colocalization-custom-geometry` into
  `/private/tmp/astra-gui-dynamic-margin-closure-v39-colocalization-custom`.
  The aggregate nonzero-delta scan across the generated CSVs was clean.
- Newly proven zero-delta in that artifact set:
  - colocalization semantic-card-to-card gap;
  - semantic card title inset and title-to-subtitle gap;
  - model source card title inset and title-to-first-row gap;
  - labeled row label-to-editor gap;
  - colocalization check editor rows-to-add-button gap;
  - populated check-row top/left insets;
  - check top-row to selector-row gap;
  - check top-row and selector-row child gaps;
  - nested field label-to-editor gap;
  - marker-key empty-state top/left insets;
  - multi-select editor child gap.

### 13. Output Pane And StyledLogView

- Output pane outer padding.
- Output header status rail.
- Kill Run button rail.
- Status card padding.
- Status title rail.
- Status detail rail.
- Timeline row gap.
- Copy All button rail.
- Source tab left inset.
- Source tab-to-source block join.
- Source block padding.
- Log line accent rail.
- Log line accent width.
- Plain log line accent-to-text gap.
- Badge-bearing log line accent-to-badge gap.
- Badge-to-text gap.
- Message card padding.
- Key-value card padding.
- Command card padding.
- Failure summary card padding.
- Warning/error jump control spacing if present.
- Collapsed Cellpose details toggle rail.
- Expanded Cellpose hidden-body padding.

### 14. Button Dynamic States

- Run normal/hover/pressed/disabled.
- Cancel normal/hover/pressed/disabled.
- Header menu normal/hover/pressed/disabled.
- Small buttons normal/hover/pressed/disabled.
- Help `?` normal/hover/pressed/disabled.
- Dialog buttons normal/hover/pressed/disabled.
- Output buttons normal/hover/pressed/disabled.
- Segmented controls normal/selected/hover/pressed.
- Confirm no hover state changes size or rail position.

### 15. Advanced-Unlocked State

- Unlock panel padding before unlock.
- Unlock phrase field rail.
- Unlock button rail.
- Unlock status/error text rail.
- Advanced panel appears without changing prior pane margins.
- Advanced section-to-section gap.
- Advanced row rails.
- Advanced dependent panels.
- Advanced long-list scrollbar behavior.
- Advanced bottom alignment with output pane.

### 16. All Settings Mode

- All Settings toggle selected geometry.
- Collapsible section header padding.
- Header title rail.
- Header chevron rail.
- Header-to-content gap.
- Section-to-section gap.
- Long-list scrollbar gutter.
- Collapsed section geometry.
- Expanded section geometry.
- Mixed advanced/routine section spacing.

### 17. Typography And Optical Alignment

Status: `intentionally out of scope` for zero-delta margin math, with a
targeted visual QA artifact and zero-delta shell measurements.

- Single-line parameter baseline perception.
- Dependent title baseline vs row label baseline.
- Badge/title/subtitle visual alignment.
- Button text vertical centering.
- Combo text vertical centering.
- Dialog title/body baseline.
- Log badge text centering.
- Any node-bound-equal but visually-off text case.

## Progress Checkpoints

### Typography Optical Review

Status: `intentionally out of scope` for zero-delta margin math; measured
shell geometry is `zero-delta`.

Artifact folder:
`/private/tmp/astra-gui-dynamic-margin-closure-v60-typography-optical`.

Evidence:
- Added `typography-optical-review`, a targeted visual QA surface containing
  representative ASTRA text contexts:
  - parameter labels and dependent panel titles;
  - importance badge, focused title, and focused subtitle;
  - primary, secondary, small, and help buttons;
  - combo-box text;
  - warning severity badge and ASTRA source tab.
- Added a zero-height width probe so shell right inset is measured against a
  real full-width layout child rather than a non-expanding `Label`.
- Generated PNG/CSV/Markdown artifacts:
  - `typography-optical-review.png`
  - `typography-optical-review.csv`
  - `typography-optical-review.md`
- Proved zero-delta for:
  - typography root left inset;
  - typography root right inset;
  - typography title-to-note gap.
- The optical baseline lines remain visual QA only. They are intentionally not
  expressed as zero-delta numeric margins because rendered glyph perception
  depends on platform font fallback, glyph metrics, baseline offsets, and
  anti-aliasing.

### Asset-Backed Combo Empty/Populated States

Status: `zero-delta`.

Artifact folder:
`/private/tmp/astra-gui-dynamic-margin-closure-v40-asset-combo`.

Evidence:
- Added package-local access to the production asset-backed combo builder so
  preview diagnostics exercise the same ComboBox factory used for project
  model/classifier asset selectors.
- Added `asset-backed-combo-geometry` preview mode with two synthetic asset
  states:
  - empty discovery with a current saved value;
  - populated discovery with three saved model values.
- Generated closed-state and popup-state PNG/CSV/Markdown artifacts:
  - `asset-backed-combo-geometry.*`
  - `asset-backed-combo-popup-geometry.*`
- Proved zero-delta for:
  - empty asset-backed combo left cell inset;
  - populated asset-backed combo left cell inset;
  - asset combo vertical row gap;
  - asset combo popup list left-to-row inset;
  - asset combo popup row text inset;
  - asset combo popup row gap.
- Aggregate CSV nonzero-delta scan across the artifact folder returned no
  rows.
- `./gradlew test --tests qupath.ext.astra.PipelineLauncherTest` passed.
- `git diff --check` passed.

Notes:
- The diagnostic window is synthetic by design. It isolates the exact
  asset-backed dropdown widget states without depending on a live QuPath
  project, while still using the production asset-backed ComboBox factory.

### Marker Key Map Populated State

Status: `zero-delta`.

Artifact folder:
`/private/tmp/astra-gui-dynamic-margin-closure-v41-marker-key-map`.

Evidence:
- Added `marker-key-map-geometry` preview mode with a populated
  `MarkerKeyMapEditor` containing two marker keys:
  - `SMA|DAPI`
  - `CD31|DAPI`
- Generated populated-state PNG/CSV/Markdown artifacts:
  - `marker-key-map-geometry.*`
- Proved zero-delta for:
  - populated marker-key first row left inset;
  - populated marker-key first row top inset;
  - populated marker-key row gap;
  - populated marker-key label-to-input gap;
  - populated marker-key input left rail;
  - populated marker-key input right rail.
- Aggregate CSV nonzero-delta scan across the artifact folder returned no
  rows.
- `./gradlew test --tests qupath.ext.astra.PipelineLauncherTest` passed.
- `git diff --check` passed.

Notes:
- This closes the explicit prior gap where marker/key maps had only been
  measured in their empty state. The diagnostic uses the production
  `MarkerKeyMapEditor` and its real `refresh(...)` path to force populated
  rows.

### Populated Channel Panel Chips And Swatches

Status: `zero-delta`.

Artifact folder:
`/private/tmp/astra-gui-dynamic-margin-closure-v42-channel-populated`.

Evidence:
- Re-ran `channel-panel-populated-geometry` with the production
  `createChannelPanel(List<ImageChannel>)` path using three concrete channels:
  `DAPI`, `AF555`, and `AF647`.
- Generated populated-state PNG/CSV/Markdown artifacts:
  - `channel-panel-populated-geometry.*`
- Proved zero-delta for:
  - channel title left inset;
  - channel title top inset;
  - channel panel child vertical gap;
  - channel chip horizontal gap;
  - channel chip left inset to swatch;
  - channel chip swatch size;
  - channel chip centered swatch top inset;
  - channel swatch-to-label gap;
  - channel chip right inset from label.
- Aggregate CSV nonzero-delta scan across the artifact folder returned no
  rows.
- `git diff --check` passed.

Notes:
- This closes the prior populated channel-chip gap without requiring a live
  QuPath image. The runtime open-image path resolves the image channels and
  then calls the same production `createChannelPanel(List<ImageChannel>)`
  method measured here.

### Header Dropdown Adaptive Placement

Status: `corrected` and then `zero-delta`.

Artifact folders:
- `/private/tmp/astra-gui-dynamic-margin-closure-v45-header-placement`
- `/private/tmp/astra-gui-dynamic-margin-closure-v46-header-clamp`

Evidence:
- Added actual popup-window placement diagnostics for:
  - left-aligned default placement;
  - right-on-overflow placement;
  - too-narrow clamp placement.
- The first placement run exposed a real right-overflow drift:
  `header rendered menu box adaptive x placement` observed `+12px` from the
  expected formula.
- Root cause: `menu.show(...)` needs the JavaFX anchor/window offset, but the
  post-show correction path was adding that offset again to `menu.setX(...)`.
- Corrected the post-show placement to set `menu.setX(alignedX)` directly.
- Generated left-default and right-on-overflow popup PNG/CSV/Markdown
  artifacts in `v45`; both are zero-delta for:
  - rendered menu box adaptive X placement;
  - popup shell left/right inset;
  - popup window X versus anchor X;
  - menu item shell insets and label inset.
- Added a clamp-only preview mode after the three-window diagnostic sequence
  showed the clamp artifact was not reliably emitted. The clamp-only mode uses
  a formula-derived too-narrow window width,
  `MENU_RENDERED_POPUP_WIDTH + MENU_EDGE_MARGIN`, which is still below the
  minimum span needed for normal placement but avoids a degenerate preview.
- Generated the too-narrow clamp popup PNG/CSV/Markdown artifact in `v46`.
  The clamp artifact is zero-delta for the same rendered placement and popup
  shell measurements.
- `./gradlew test --tests qupath.ext.astra.PipelineLauncherTest` passed after
  the placement correction and diagnostic additions.

Notes:
- This closes the explicit prior gap for adaptive header dropdown placement.
  Header dropdown internals were already zero-delta; this section proves the
  dynamic window-edge placement states using actual ContextMenu popup windows.

### Advanced-Unlocked Deep Section Geometry

Status: `zero-delta`.

Artifact folder:
`/private/tmp/astra-gui-dynamic-margin-closure-v50-advanced-deep`.

Evidence:
- Strengthened `advanced-geometry` so the preview now:
  - unlocks the advanced panel;
  - switches the advanced navigator to `All Settings`;
  - opens the first advanced collapsible section;
  - scrolls to the advanced panel before measuring visible section rows.
- Earlier `v47` and `v49` trials exposed a diagnostic flaw: the section
  measurement was allowed to select hidden/unmanaged collapsed content and
  compare a stale nested editor rail against the outer section right edge.
- Corrected the diagnostic to measure only managed direct children of the
  visible section grid for section-level padding.
- Generated `advanced-geometry.*` artifacts in `v50`.
- Proved zero-delta for:
  - routine-to-advanced panel gap;
  - advanced section content left padding;
  - advanced section content right padding;
  - advanced section parameter row gap.
- Aggregate CSV nonzero-delta scan across the artifact folder returned no
  rows.
- `./gradlew test --tests qupath.ext.astra.PipelineLauncherTest` passed.
- `git diff --check` passed.

Notes:
- This closes the prior advanced-unlocked gap for visible opened advanced
  sections. It does not attempt optical baseline judgment; that remains a
  visual-review category rather than a node-bound geometry category.

### Dependency State Matrix

Status: `zero-delta`.

Artifact folder:
`/private/tmp/astra-gui-dynamic-margin-closure-v52-dependency-matrix`.

Evidence:
- Added `dependency-matrix-geometry` preview mode with a diagnostic surface
  that renders every `DEPENDENCY_PANELS` registry entry in both enabled and
  disabled states through the production `createDependentPanel(...)` path.
- Generated dependency-matrix PNG/CSV/Markdown artifacts:
  - `dependency-matrix-geometry.*`
- Covered registry tokens:
  - `selected-images`
  - `threshold-selected-images`
  - `nucleus-model-source`
  - `cell-model-source`
  - `nucleus-parameter-source`
  - `cell-parameter-source`
  - `generic-parameter-source`
  - `positivity-method`
  - `threshold-mode`
  - `background-mode`
  - `batch-options`
  - `failure-recovery`
  - `custom-segmentation`
  - `nucleus-gating`
- Proved zero-delta for each enabled and disabled dependency case:
  - diagnostic case left/right inset;
  - diagnostic case title-to-panel gap;
  - dependent title text inset;
  - dependent title-to-reason gap;
  - dependent reason-to-row-grid gap;
  - dependent row-grid left border inset;
  - dependent row-grid right inset;
  - dependent row left padding;
  - dependent help-to-editor gap;
  - dependent editor left rail.
- Aggregate CSV nonzero-delta scan across the artifact folder returned no
  rows.
- `./gradlew test --tests qupath.ext.astra.PipelineLauncherTest` passed.
- `git diff --check` passed.

Notes:
- `custom-segmentation` currently declares an empty dependent-name set in the
  registry, so the diagnostic uses a synthetic row for that token to prove the
  shared panel shell and enabled/disabled row-state geometry. That synthetic
  row does not alter runtime behavior.
- The diagnostic intentionally measures the editor left rail, not universal
  editor right fill. Right-edge fill is not a valid invariant for compact
  controls such as checkboxes.

### Row State And Tall Editor Alignment

Status: `zero-delta`.

Artifact folder:
`/private/tmp/astra-gui-dynamic-margin-closure-v55-row-state`.

Evidence:
- Added `row-state-geometry` preview mode with a diagnostic surface that
  renders:
  - one enabled single-line row;
  - one disabled single-line row;
  - one tall structured `Map` row.
- The diagnostic uses the production `addParameterRow(...)` path and production
  `setEnabled(...)` path.
- Generated row-state PNG/CSV/Markdown artifacts:
  - `row-state-geometry.*`
- Proved zero-delta for:
  - grid left padding;
  - enabled-to-disabled row gap;
  - disabled-to-tall row gap;
  - disabled label X drift;
  - disabled label width drift;
  - disabled editor X drift;
  - disabled editor width drift;
  - disabled label opacity;
  - disabled editor opacity;
  - single-line editor top alignment;
  - tall editor top alignment;
  - single-line anchor and help vertical insets;
  - tall-row anchor and help vertical insets.
- Aggregate CSV nonzero-delta scan across the artifact folder returned no
  rows.
- `./gradlew test --tests qupath.ext.astra.PipelineLauncherTest` passed.
- `git diff --check` passed.

Notes:
- The first row-state run exposed a diagnostic formula issue: anchor/help
  controls are centered inside their rendered label rows, not inset by
  `PARAMETER_ROW_VERTICAL_PADDING` directly.
- The corrected formula uses
  `snapUpToPixelGrid((rendered label row height - rendered control height) / 2)`
  and the current JavaFX output scale, avoiding both magic numbers and
  screenshot-derived constants.

### Residual GUI Ownership Closure

Status: `closed`.

Artifacts:
- `docs/gui-residual-margin-audit.csv`
- `docs/gui-css-layout-literals.csv`
- `docs/launcher-margin-checklist-status.csv`
- `docs/launcher-margin-current-inventory.csv`

Evidence added:
- Settings profile loading still uses JavaFX `FileChooser`. It is intentionally
  native/out of scope because the file chooser is not an ASTRA-owned render
  tree and its geometry cannot be asserted by ASTRA.
- Run-complete notifications still use QuPath
  `Dialogs.showInfoNotification(...)`. They are intentionally native/out of
  scope unless ASTRA later replaces them with an owned notification surface.
- Runtime setup confirmation now routes through
  `PipelineLauncher.createAstraSuccessConfirmationDialog(...)`; it is
  ASTRA-owned and uses the shared dialog shell.
- Run-complete notification now routes through
  `PipelineLauncher.showAstraMessage(...)`; it is ASTRA-owned and uses the
  shared dialog shell.
- Settings profile loading remains the only intentionally native launcher
  surface because it uses JavaFX `FileChooser`.
- `RuntimeInstaller.InstallProgress` no longer owns ad hoc raw layout literals.
  Its root padding, header gap, content gap, and scene size now derive from
  `InstallerGeometry`, which itself derives from `LauncherGeometryTokens`.
- Bottom run progress lane was added to the main action row before Cancel/Run.
  Its height, radius, and minimum width derive from
  `LauncherGeometryTokens.ACTION_PROGRESS_*`; it is always managed/reserved and
  receives the same shared animated gradient surface used by the header.
- Targeted idle Run Setup preview confirmed the reserved bottom progress lane:
  `/private/tmp/astra-gui-progress-lane/run-setup.png`.
- Runtime installer progress content is built through
  `createInstallProgressRoot(...)`, receives ASTRA style classes and the shared
  launcher stylesheet, and is exposed to the preview harness through
  `runtime-installer-geometry`.
- Tooltip geometry now has source-of-truth tokens:
  `LauncherGeometryTokens.TOOLTIP_VERTICAL_INSET` and
  `LauncherGeometryTokens.TOOLTIP_HORIZONTAL_INSET`.
- The stylesheet mirrors the tooltip formulas with an explicit comment, and
  `tooltip-geometry` emits text inset diagnostics through `Surface.TOOLTIP`.
  JavaFX did not expose a stable native `Tooltip` scene in the preview harness,
  so the diagnostic uses an ASTRA-owned popup carrying the same `.tooltip`
  style class and verifies the styled tooltip surface. The final run emitted
  zero-delta left, top, and right text inset rows:
  `/private/tmp/astra-gui-exhaustive-margin-tooltip-v5/tooltip-geometry.csv`.
- `runtime-installer-geometry` emitted zero-delta scene size, root padding,
  header/log gap, and header row gap rows:
  `/private/tmp/astra-gui-exhaustive-margin-runtime-installer/runtime-installer-geometry.csv`.
- `AnimatedGradientHeader` clip radius derives from
  `LauncherGeometryTokens.INTRA_PANEL_MARGIN`; its remaining texture and motion
  constants are named visual-effect tokens rather than margin tokens.
- `docs/gui-css-layout-literals.csv` classifies each current layout-like CSS
  declaration. `PipelineLauncherTest.cssLayoutLiteralsHaveResidualProvenanceRows`
  fails if declarations drift without matching provenance rows.

Closure verification:
- `./gradlew test --tests qupath.ext.astra.PipelineLauncherTest` passed after
  the final source/doc edits.
- Extension `./gradlew test` passed.
- `git diff --check` passed in both the extension repo and the main ASTRA repo.
- Main ASTRA `./gradlew :contracts:clean :contracts:test` was later rerun after
  the experimental vessel-sandbox long-edge contract was made opt-in by default;
  it passed. During active GUI iteration, do not rerun broad contracts unless
  explicitly requested or preparing a final release gate. Use targeted launcher
  tests and targeted diagnostics instead.
