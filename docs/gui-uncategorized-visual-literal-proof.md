# GUI Uncategorized Visual Literal Proof

Status: `Tentatively complete / awaiting manual visual review`

Implementation date: 2026-07-07

## Scope

This pass covers the `css-java-uncategorized-visual-literals` inventory
family. The family contained seven rows in `PipelineLauncher.java`, where
JavaFX inline style strings had been flagged for ownership cleanup.

## Source Contract

- `CLEARED_INLINE_STYLE` is the single named value for an intentional JavaFX
  inline-style reset.
- `clearInlineStyle(Node)` is the only approved reset helper for renderer-owned
  JavaFX subnodes, list cells, selected combo cells, check boxes, and similar
  nodes where ASTRA must clear renderer residue before style classes can own
  the visible state.
- `BASE_INLINE_STYLE_PROPERTY` is the named property key for preserving a
  control's pre-existing inline style before ASTRA applies changed-from-default
  state.
- `rememberBaseInlineStyle(Node)` owns base inline-style capture.
- `restoreBaseInlineStyle(Node)` owns base inline-style restoration.
- Changed-from-default visual styling remains CSS-owned through
  `astra-editor-changed-from-default`; the Java path only restores the base
  inline state before adding or removing that semantic style class.

## Inventory Result

| Family | Rows | Status |
| --- | ---: | --- |
| `css-java-uncategorized-visual-literals` | 7 | `tokenized-pass-8` |

All seven rows are now `closed-proven` through `tokenized-pass-8`.

## Proof

Focused source proof is enforced by
`PipelineLauncherTest.launcherUncategorizedVisualLiteralsUseNamedInlineStylePaths`.

The test asserts:

- the named reset and base-style constants exist;
- the reset, remember, and restore helpers exist;
- empty inline-style reset is routed through `CLEARED_INLINE_STYLE`;
- raw `setStyle("")` no longer appears in `PipelineLauncher.java`;
- raw `astra.baseStyle` property use no longer appears outside the named
  constant/helper path;
- stale `baseStyle + changedStyle` inline concatenation is gone;
- the inventory family has no unresolved classifications.

## Manual Review Remaining

No rendered screenshot is required for this family because the cleaned rows are
source-ownership paths, not new geometry, color, motion, or typography values.
Existing combo/list/dialog previews remain the visual evidence for the
surfaces that consume these renderer reset paths.
