# GUI Theme / State / Readability Token Proof

## Pass 5 Verdict

The `colors-theme-semantic-roles` and `opacity-translucency-disabled` families
are tentatively complete and awaiting manual visual review.

The implementation ledger now has zero unresolved rows for these families:

| Family | Classification | Rows |
| --- | --- | ---: |
| `colors-theme-semantic-roles` | `generated-css-token` | 381 |
| `colors-theme-semantic-roles` | `generated/test-only` | 110 |
| `colors-theme-semantic-roles` | `tokenized-pass-5` | 17 |
| `opacity-translucency-disabled` | `generated-css-token` | 79 |
| `opacity-translucency-disabled` | `tokenized-pass-5` | 1 |

## Source-Of-Truth Changes

- `LauncherThemeTokens` owns launcher palette values, semantic state colors,
  gradient paint colors, Java opacity constants, the CSS opacity allowlist,
  channel fallback/stroke colors, and changed-value emphasis color.
- `launcher.tokens.css` records generated theme tokens for JavaFX CSS and is
  byte-for-byte guarded by `PipelineLauncherTest`.
- `launcher.css` imports `launcher.tokens.css` and uses generated color tokens
  for launcher-owned visual states.
- CSS opacity declarations remain renderer-safe concrete numeric mirrors, but
  each value is guarded against `LauncherThemeTokens.CSS_OPACITY_VALUES`.
- `PipelineLauncher` consumes theme tokens for base palette, channel fallback
  and stroke colors, changed-from-default emphasis, and Java opacity states.
- `AnimatedGradientSurface` consumes theme tokens for generated gradient paint
  stops and overlay color.

## Explicit Exceptions

- User/project channel swatches remain data-driven because their fill colors
  come from image channel metadata. Their fallback and stroke colors are
  tokenized.
- JavaFX CSS numeric opacity values remain concrete mirrors because JavaFX
  looked-up values are not reliable for all numeric CSS properties. The
  allowlist test prevents unreviewed opacity drift.

## Required Proof Targets

Representative proof targets for this pass:

| Surface | Evidence |
| --- | --- |
| header action page | `header-actions-page.png`, `header-actions-page-edge-audit.md` |
| header action rail and fallback settings menu | `header-action-rail-geometry.png`, `settings-menu-geometry.png` |
| dashboard | `dashboard.png`, `dashboard-edge-audit.md` |
| parameter pane | `run-setup.png`, `run-setup-edge-audit.md` |
| combo popup and selected rows | `combo-popup-geometry.png`, `combo-popup-geometry.md` |
| closed combo box | `closed-combo-geometry.png`, `closed-combo-geometry.md` |
| button states | `button-states-geometry.png`, `button-states-geometry.md` |
| output pane | `output-pane-geometry.png`, `output-pane-geometry.md` |
| styled run-log pane | `styled-log-geometry.png`, `styled-log-geometry.md` |
| help dialog | `help-dialog-geometry.png`, `help-dialog-geometry.md` |
| selection dialog/list surface | `selected-images-dialog-geometry.png`, `selected-images-dialog-geometry.md` |
| runtime installer panel | `runtime-installer-geometry.png`, `runtime-installer-geometry.md` |

Generated proof artifacts:

`/private/tmp/astra-theme-pass-proof`

Structured proof checks:

- Closed combo cell inset: `0` delta.
- Combo popup list and row text rails: `0` delta.
- Header action rail/menu gap: `0` delta.
- Button hover/pressed/disabled bounds: `0` delta for run, cancel, header menu,
  small, help, dialog, output, and segmented button families.
- Output-pane button and card rails: `0` delta.
- Help dialog details and section geometry rows: `0` delta.
- Selection dialog/list geometry rows: `0` delta.
- Styled log fade/card/status geometry rows: `0` delta.
- Runtime installer panel geometry rows: `0` delta.
- Runtime installer edge audit: no stripped pixels.

## Completion Standard

- Focused `PipelineLauncherTest` passes.
- The generated theme CSS remains synchronized with `LauncherThemeTokens`.
- `launcher.css` contains no raw hex or rgb/rgba color declarations.
- CSS opacity values are limited to the reviewed theme-token allowlist.
- Production Java theme values in `PipelineLauncher` and
  `AnimatedGradientSurface` route through `LauncherThemeTokens` or a documented
  data-driven exception.
- Manual visual review remains required before either family can be marked
  `Fully complete / user-approved`.
