# ASTRA GUI Visual Token / Magic Number Audit
Generated: 2026-07-03T15:30:07

## Status
Audit-only phase completed for the launcher GUI source set. No GUI implementation, normalization, release, tag, or commit was performed. The current extension worktree already had dirty GUI files before this audit; this pass adds documentation artifacts only.

## Scope
- `src/main/java/qupath/ext/astra/PipelineLauncher.java`
- `src/main/java/qupath/ext/astra/LauncherGeometryTokens.java`
- `src/main/java/qupath/ext/astra/RuntimeInstaller.java`
- `src/main/java/qupath/ext/astra/AnimatedGradientHeader.java`
- `src/main/java/qupath/ext/astra/AnimatedGradientSurface.java`
- `src/main/java/qupath/ext/astra/StyledLogView.java`
- `src/main/java/qupath/ext/astra/GuiPresentation.java`
- `src/main/java/qupath/ext/astra/GuiText.java`
- `src/main/resources/qupath/ext/astra/astra-launcher.css`
- `src/test/java/qupath/ext/astra/LauncherPreviewApp.java`
- `src/test/java/qupath/ext/astra/PipelineLauncherTest.java`
- `src/test/java/qupath/ext/astra/ExtensionContractTest.java`

## Search Commands And Method
- rg CSS numeric visual declarations in astra-launcher.css
- rg Java layout/style/timing calls in PipelineLauncher, RuntimeInstaller, AnimatedGradient*, StyledLogView, Gui*
- structured parser over CSS properties and Java visual literal lines
- classification into family/subfamily/risk/proof_needed CSV rows

The CSV inventory is generated from CSS visual declarations and Java GUI/layout/timing literal candidates. Rows are intentionally conservative: a named constant is still flagged when the name or formula may not be a semantic source of truth.

## Inventory Files
- `docs/gui-visual-token-inventory.csv`
- `docs/gui-visual-token-audit.md`

## Counts By Family
| Family | Findings |
| --- | ---: |
| `colors-theme-semantic-roles` | 350 |
| `typography-text-ink` | 277 |
| `bevels-radii-arcs-tab-curvature` | 137 |
| `margins-padding-gaps-spacing` | 99 |
| `opacity-translucency-disabled` | 79 |
| `control-geometry` | 45 |
| `borders-strokes` | 22 |
| `motion-animation-gradient-speed` | 22 |
| `header-footer-tab-shape-metrics` | 20 |
| `css-java-uncategorized-visual-literals` | 7 |
| `dialogs-popups-menus-lists` | 5 |
| `fades-overlays-masks` | 3 |
| `z-layer-clipping-edge-artifacts` | 2 |

## Counts By Classification
| Classification | Findings |
| --- | ---: |
| `named-but-semantics-unclear` | 568 |
| `already-tokenized` | 194 |
| `needs-human-review` | 180 |
| `raw-literal-needs-token` | 84 |
| `generated/test-only` | 39 |
| `formula-needs-semantic-name` | 3 |

## Counts By Risk
| Risk | Findings |
| --- | ---: |
| `high` | 492 |
| `medium` | 343 |
| `low` | 233 |

## High-Risk Areas
- `typography-text-ink`: 224 high-risk findings.
- `bevels-radii-arcs-tab-curvature`: 135 high-risk findings.
- `margins-padding-gaps-spacing`: 77 high-risk findings.
- `control-geometry`: 39 high-risk findings.
- `header-footer-tab-shape-metrics`: 17 high-risk findings.

High-risk does not mean broken; it means the family can visibly drift and needs tokenization plus rendered proof before being considered done.

## Family Findings And Proof Strategy

### colors-theme-semantic-roles
- Findings: 350
- Proof required later: pixel sampling against theme tokens + state screenshots
- Representative rows:
  - `GVT-0001` `src/main/java/qupath/ext/astra/PipelineLauncher.java:155` `color` `#7fa3ad` -> `already-tokenized`
  - `GVT-0044` `src/main/java/qupath/ext/astra/PipelineLauncher.java:5182` `setStyle` `3140 #31404a` -> `raw-literal-needs-token`
  - `GVT-0076` `src/main/java/qupath/ext/astra/AnimatedGradientSurface.java:38` `color` `6 23 32 rgb(6, 23, 32)` -> `named-but-semantics-unclear`
  - `GVT-0077` `src/main/java/qupath/ext/astra/AnimatedGradientSurface.java:42` `color` `0.00d 071d 9 #071d29` -> `raw-literal-needs-token`
  - `GVT-0078` `src/main/java/qupath/ext/astra/AnimatedGradientSurface.java:43` `color` `0.08d 092937 #092937` -> `raw-literal-needs-token`

### typography-text-ink
- Findings: 277
- Proof required later: transparent node snapshot + glyph alpha bounds + layout-vs-ink delta table
- Representative rows:
  - `GVT-0049` `src/main/java/qupath/ext/astra/PipelineLauncher.java:8435` `color` `12px #fbfdff` -> `raw-literal-needs-token`
  - `GVT-0068` `src/main/java/qupath/ext/astra/RuntimeInstaller.java:1459` `java-visual-literal` `0s` -> `raw-literal-needs-token`
  - `GVT-0071` `src/main/java/qupath/ext/astra/RuntimeInstaller.java:1621` `java-visual-literal` `0s` -> `raw-literal-needs-token`
  - `GVT-0072` `src/main/java/qupath/ext/astra/AnimatedGradientSurface.java:32` `java-visual-literal` `3.0d` -> `named-but-semantics-unclear`
  - `GVT-0073` `src/main/java/qupath/ext/astra/AnimatedGradientSurface.java:34` `java-visual-literal` `1.0d` -> `named-but-semantics-unclear`

### bevels-radii-arcs-tab-curvature
- Findings: 137
- Proof required later: rendered alpha bounds + path segment extraction + curve tangent/end-point proof
- Representative rows:
  - `GVT-0006` `src/main/java/qupath/ext/astra/PipelineLauncher.java:258` `radius/arc` `2.0` -> `raw-literal-needs-token`
  - `GVT-0024` `src/main/java/qupath/ext/astra/PipelineLauncher.java:486` `java-visual-literal` `2.0` -> `raw-literal-needs-token`
  - `GVT-0050` `src/main/java/qupath/ext/astra/PipelineLauncher.java:8436` `java-visual-literal` `4 4` -> `raw-literal-needs-token`
  - `GVT-0055` `src/main/java/qupath/ext/astra/LauncherGeometryTokens.java:17` `radius/arc` `2.0` -> `named-but-semantics-unclear`
  - `GVT-0101` `src/main/resources/qupath/ext/astra/astra-launcher.css:15` `-fx-border-radius` `5` -> `named-but-semantics-unclear`

### margins-padding-gaps-spacing
- Findings: 99
- Proof required later: rendered bounds + overlay line deltas + CSV expected/observed/delta
- Representative rows:
  - `GVT-0007` `src/main/java/qupath/ext/astra/PipelineLauncher.java:297` `java-visual-literal` `2.0` -> `raw-literal-needs-token`
  - `GVT-0008` `src/main/java/qupath/ext/astra/PipelineLauncher.java:358` `java-visual-literal` `5.0 2.0` -> `formula-needs-semantic-name`
  - `GVT-0010` `src/main/java/qupath/ext/astra/PipelineLauncher.java:365` `java-visual-literal` `2.0` -> `raw-literal-needs-token`
  - `GVT-0011` `src/main/java/qupath/ext/astra/PipelineLauncher.java:368` `java-visual-literal` `2.0` -> `raw-literal-needs-token`
  - `GVT-0013` `src/main/java/qupath/ext/astra/PipelineLauncher.java:372` `java-visual-literal` `2.0` -> `raw-literal-needs-token`

### opacity-translucency-disabled
- Findings: 79
- Proof required later: pixel alpha sampling across states + contrast/readability review
- Representative rows:
  - `GVT-0042` `src/main/java/qupath/ext/astra/PipelineLauncher.java:4174` `opacity` `1.0d 0.48d` -> `raw-literal-needs-token`
  - `GVT-0111` `src/main/resources/qupath/ext/astra/astra-launcher.css:45` `-fx-effect` `innershadow(gaussian, rgba(0, 0, 0, 0.18), 4, 0.2, 0, 1)` -> `named-but-semantics-unclear`
  - `GVT-0112` `src/main/resources/qupath/ext/astra/astra-launcher.css:49` `-fx-opacity` `0.88` -> `named-but-semantics-unclear`
  - `GVT-0119` `src/main/resources/qupath/ext/astra/astra-launcher.css:77` `-fx-background-color` `rgba(255, 255, 255, 0.92)` -> `named-but-semantics-unclear`
  - `GVT-0121` `src/main/resources/qupath/ext/astra/astra-launcher.css:79` `-fx-border-color` `rgba(255, 255, 255, 0.95)` -> `named-but-semantics-unclear`

### control-geometry
- Findings: 45
- Proof required later: rendered body bounds + same-family width/height deltas
- Representative rows:
  - `GVT-0002` `src/main/java/qupath/ext/astra/PipelineLauncher.java:166` `java-visual-literal` `3.0` -> `named-but-semantics-unclear`
  - `GVT-0003` `src/main/java/qupath/ext/astra/PipelineLauncher.java:168` `java-visual-literal` `2.0` -> `raw-literal-needs-token`
  - `GVT-0005` `src/main/java/qupath/ext/astra/PipelineLauncher.java:256` `java-visual-literal` `2.0` -> `raw-literal-needs-token`
  - `GVT-0009` `src/main/java/qupath/ext/astra/PipelineLauncher.java:364` `java-visual-literal` `3.0` -> `raw-literal-needs-token`
  - `GVT-0026` `src/main/java/qupath/ext/astra/PipelineLauncher.java:557` `java-visual-literal` `7.0 5.0` -> `raw-literal-needs-token`

### borders-strokes
- Findings: 22
- Proof required later: rendered stroke alpha bounds + border width pixel scans
- Representative rows:
  - `GVT-0004` `src/main/java/qupath/ext/astra/PipelineLauncher.java:244` `java-visual-literal` `2.0` -> `raw-literal-needs-token`
  - `GVT-0012` `src/main/java/qupath/ext/astra/PipelineLauncher.java:369` `java-visual-literal` `2.0` -> `raw-literal-needs-token`
  - `GVT-0014` `src/main/java/qupath/ext/astra/PipelineLauncher.java:373` `java-visual-literal` `2.0` -> `raw-literal-needs-token`
  - `GVT-0021` `src/main/java/qupath/ext/astra/PipelineLauncher.java:409` `java-visual-literal` `2.0` -> `raw-literal-needs-token`
  - `GVT-0030` `src/main/java/qupath/ext/astra/PipelineLauncher.java:589` `java-visual-literal` `2.0` -> `raw-literal-needs-token`

### motion-animation-gradient-speed
- Findings: 22
- Proof required later: source token checks + active surface count + seam/speed diagnostics
- Representative rows:
  - `GVT-0045` `src/main/java/qupath/ext/astra/PipelineLauncher.java:6273` `java-visual-literal` `-1.0d` -> `formula-needs-semantic-name`
  - `GVT-0046` `src/main/java/qupath/ext/astra/PipelineLauncher.java:6347` `java-visual-literal` `-1.0d` -> `formula-needs-semantic-name`
  - `GVT-0047` `src/main/java/qupath/ext/astra/PipelineLauncher.java:6616` `motion/duration` `1.0` -> `raw-literal-needs-token`
  - `GVT-0048` `src/main/java/qupath/ext/astra/PipelineLauncher.java:7067` `motion/duration` `350.0` -> `raw-literal-needs-token`
  - `GVT-0058` `src/main/java/qupath/ext/astra/RuntimeInstaller.java:84` `motion/duration` `45` -> `named-but-semantics-unclear`

### header-footer-tab-shape-metrics
- Findings: 20
- Proof required later: rendered button alpha + live path segment zero-delta table
- Representative rows:
  - `GVT-0019` `src/main/java/qupath/ext/astra/PipelineLauncher.java:391` `java-visual-literal` `2.0` -> `raw-literal-needs-token`
  - `GVT-0020` `src/main/java/qupath/ext/astra/PipelineLauncher.java:408` `java-visual-literal` `2.0` -> `raw-literal-needs-token`
  - `GVT-0023` `src/main/java/qupath/ext/astra/PipelineLauncher.java:443` `java-visual-literal` `2.0` -> `raw-literal-needs-token`
  - `GVT-0040` `src/main/java/qupath/ext/astra/PipelineLauncher.java:714` `java-visual-literal` `2.0` -> `already-tokenized`
  - `GVT-0920` `src/test/java/qupath/ext/astra/LauncherPreviewApp.java:4314` `java-visual-literal` `2.0d` -> `needs-human-review`

### css-java-uncategorized-visual-literals
- Findings: 7
- Proof required later: family-specific rendered proof to be defined
- Representative rows:
  - `GVT-1055` `src/main/java/qupath/ext/astra/PipelineLauncher.java:3287` `setStyle` `arrow.setStyle("");` -> `raw-literal-needs-token`
  - `GVT-1057` `src/main/java/qupath/ext/astra/PipelineLauncher.java:3297` `setStyle` `selectedCell.setStyle("");` -> `raw-literal-needs-token`
  - `GVT-1058` `src/main/java/qupath/ext/astra/PipelineLauncher.java:3322` `setStyle` `cell.setStyle("");` -> `raw-literal-needs-token`
  - `GVT-1059` `src/main/java/qupath/ext/astra/PipelineLauncher.java:3334` `setStyle` `setStyle("");` -> `raw-literal-needs-token`
  - `GVT-1062` `src/main/java/qupath/ext/astra/PipelineLauncher.java:5573` `setStyle` `box.setStyle("");` -> `raw-literal-needs-token`

### dialogs-popups-menus-lists
- Findings: 5
- Proof required later: actual popup/window captures + rendered bounds tables
- Representative rows:
  - `GVT-0833` `src/test/java/qupath/ext/astra/LauncherPreviewApp.java:278` `java-visual-literal` `2.8` -> `needs-human-review`
  - `GVT-0834` `src/test/java/qupath/ext/astra/LauncherPreviewApp.java:281` `java-visual-literal` `4.2` -> `needs-human-review`
  - `GVT-0835` `src/test/java/qupath/ext/astra/LauncherPreviewApp.java:284` `java-visual-literal` `5.6` -> `needs-human-review`
  - `GVT-0838` `src/test/java/qupath/ext/astra/LauncherPreviewApp.java:519` `java-visual-literal` `4.1` -> `needs-human-review`
  - `GVT-0839` `src/test/java/qupath/ext/astra/LauncherPreviewApp.java:526` `java-visual-literal` `3.6` -> `needs-human-review`

### fades-overlays-masks
- Findings: 3
- Proof required later: rendered overlay bounds + alpha gradient sampling
- Representative rows:
  - `GVT-0075` `src/main/java/qupath/ext/astra/AnimatedGradientSurface.java:37` `java-visual-literal` `0.18d` -> `named-but-semantics-unclear`
  - `GVT-0092` `src/main/java/qupath/ext/astra/AnimatedGradientSurface.java:277` `java-visual-literal` `1.0d` -> `raw-literal-needs-token`
  - `GVT-0096` `src/main/java/qupath/ext/astra/StyledLogView.java:140` `java-visual-literal` `0.0d 0.0d` -> `raw-literal-needs-token`

### z-layer-clipping-edge-artifacts
- Findings: 2
- Proof required later: raw-vs-normalized edge audit + clip bounds proof
- Representative rows:
  - `GVT-0163` `src/main/resources/qupath/ext/astra/astra-launcher.css:187` `-fx-translate-y` `0` -> `named-but-semantics-unclear`
  - `GVT-0222` `src/main/resources/qupath/ext/astra/astra-launcher.css:420` `-fx-translate-y` `-1px` -> `named-but-semantics-unclear`

## Recommended Implementation Order
1. `bevels-radii-arcs-tab-curvature` - Raw CSS radius/arc spread is large and visually central.
2. `margins-padding-gaps-spacing` - Mostly tokenized but residual ratios need semantic names/proof.
3. `control-geometry` - Button/control families rely on rendered equality and interact with margins/bevels.
4. `typography-text-ink` - Known JavaFX rendered ink offset problem requires alpha proof.
5. `colors-theme-semantic-roles + opacity-translucency-disabled` - Many raw hex/rgba and state colors need theme ownership.
6. `borders-strokes + shadows-effects` - Surface polish and perceived bevel/margin depend on these.
7. `motion-animation-gradient-speed + gradient-geometry-texture-tiling` - Performance/seam/direction proof must follow static geometry.
8. `fades-overlays-masks + z-layer-clipping-edge-artifacts` - Run-log fade, clipping, edge artifacts need rendered bounds proof.
9. `dialogs-popups-menus-lists` - Actual popup-window captures and shared visual families.
10. `preview-diagnostic-proof-literals` - Ensure diagnostics derive from live nodes/tokens, not stale constants.

## Known Exemptions / Likely Exemptions To Confirm
- Native OS/file chooser surfaces should remain exempt if encountered.
- Dynamic data colors, such as true source/severity values, may be exempt only if documented and not layout-defining.
- Test fixture literals can remain only when they verify behavior without becoming stale visual truth; live-node diagnostics are preferred.
- Scientific/runtime values found in adjacent files are out of scope and should not be normalized as GUI tokens.

## Unresolved Human Design Choices
- Exact bevel family ratios below the header/footer tab benchmark.
- Whether CSS variables or generated CSS classes should own visual tokens; JavaFX CSS support may constrain this.
- Which color/opacity values are brand/theme tokens versus local state colors.
- Whether typography should move to a full rendered-ink contract for every text role or only rail-critical/control text first.

## Stopping Status
Audit-only stopping point reached. The next authorized phase should choose one family, starting with bevels/radii/arcs/tab curvature, and perform tokenization plus rendered proof. Do not begin fixes until user approval.
