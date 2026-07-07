# ASTRA GUI Dialog / Popup / Menu / List Token Proof

Status: `Tentatively complete / awaiting manual visual review`

Implementation date: 2026-07-07

## Source Contract

- The `dialogs-popups-menus-lists` inventory rows in this pass are
  preview-harness capture timings, not production GUI dimensions.
- `LauncherPreviewApp` now names those timings explicitly:
  - `HEADER_SETTINGS_MENU_CAPTURE_SECONDS`
  - `HEADER_PROJECT_MENU_CAPTURE_SECONDS`
  - `HEADER_VIEW_MENU_CAPTURE_SECONDS`
  - `SELECTED_IMAGES_DIALOG_CAPTURE_SECONDS`
  - `MULTI_SELECT_DIALOG_CAPTURE_SECONDS`
- Production dialog/list/menu geometry remains owned by the existing launcher
  geometry, control, theme, and typography token paths.
- The operating-system file chooser remains the intentional native exception.

## Ledger Result

Inventory family covered by this pass:

| Family | Result |
| --- | --- |
| `dialogs-popups-menus-lists` | `5 generated/test-only` |

No production dialog, popup, menu, or list visual row remains unresolved in this
family. The remaining `css-java-uncategorized-visual-literals` rows are a
separate JavaFX reset-style cleanup family.

## Proof Artifacts

Committed transient-surface ownership audit:

`src/test/resources/qupath/ext/astra/gui-transient-surface-audit.csv`

Generated preview proof directory for this pass:

`/private/tmp/astra-dialogs-pass-proof`

Targeted proof files generated during this pass:

- `header-action-rail-geometry.md`
- `header-action-rail-geometry.png`
- `settings-menu-geometry.md`
- `settings-menu-geometry.png`
- `multi-select-dialog-geometry.md`
- `multi-select-dialog-geometry.png`
- `selected-images-dialog-geometry.md`
- `selected-images-dialog-geometry.png`

Observed proof summary:

| Proof | Result |
| --- | --- |
| Header action rail button gap | `0.00` delta |
| Selected-images dialog title-to-list gap | `0.00` delta |
| Selected-images dialog content left/right inset | `0.00` delta |
| Selected-images dialog filter-to-chooser gap | `0.00` delta |
| Selected-images dialog dual-list transfer gaps | `0.00` delta |
| Selected-images dialog transfer button gap | `0.00` delta |
| Multi-select dialog content left/right inset | `0.00` delta |
| Multi-select dialog filter-to-chooser gap | `0.00` delta |
| Multi-select dialog child vertical gap | `0.00` delta |
| Multi-select dialog action button gap | `0.00` delta |
| Edge audits for generated captures | `NO_STRIPPED_PIXELS` |

The selected-images dialog preview now creates a disposable QuPath test project
under the preview output directory, adds generated PNG image entries, and feeds
the resulting project image names through the launcher test hook. This proves
the project-backed chooser path without mutating user projects or attaching a
real project to the hidden QuPath browser.

The header fallback Project/View menu captures are likewise not materialized in
the current default header page/ribbon mode. Fallback menu coverage remains in
the surface-state coverage ledger and should be revisited only if dropdown
fallback mode becomes active user-facing UI again.

## Focused Tests

Focused command used as the pass gate:

```bash
./gradlew test --tests qupath.ext.astra.PipelineLauncherTest
```

`PipelineLauncherTest` now guards that the dialog/menu preview capture timings
are named and that the `dialogs-popups-menus-lists` inventory family has no
unresolved classifications.

## Manual Review Remaining

- Manual live-GUI review of transient surfaces remains pending.
- If a future change restores dropdown fallback menus as prominent user-facing
  UI, actual popup-window captures should be regenerated for Settings,
  Project, and View in that mode.
