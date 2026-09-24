# ASTRA GUI Motion / Gradient Token Proof

Status: `Tentatively complete / awaiting manual visual review`

Implementation date: 2026-07-07

## Source Contract

- `LauncherMotionTokens` owns launcher motion and gradient constants.
- `AnimatedGradientSurface` preserves the pre-generated texture strategy and
  consumes `LauncherMotionTokens` for texture scale, span multiplier, seam
  overlap, texture pixel bounds, dither amplitude, and overlay alpha.
- `PipelineLauncher` consumes motion tokens for elapsed run-log refresh and
  autosave debounce timing.
- `StyledLogView` consumes motion tokens for copy feedback timing and keeps the
  run-log fade as a static viewport overlay.
- `RuntimeInstaller` consumes motion tokens for command, bootstrap, probe,
  network, validation, cancellation, and elapsed timer durations.
- JavaFX computed width sentinel values use `Region.USE_COMPUTED_SIZE` rather
  than local negative numeric literals.

## Ledger Result

Inventory families covered by this pass:

| Family | Result |
| --- | --- |
| `motion-animation-gradient-speed` | `22 tokenized-pass-6`, `5 generated/test-only` |
| `fades-overlays-masks` | `3 tokenized-pass-6` |

The remaining `css-java-uncategorized-visual-literals` rows are not part of the
motion source-of-truth result. They remain a later cleanup family because they
represent JavaFX reset-style paths rather than timing, speed, gradient motion,
or fade geometry.

## Proof Artifacts

Generated preview proof directory:

`/private/tmp/astra-motion-pass-proof`

Targeted proof files:

- `text-contract-sweep.md`
- `text-contract-sweep.csv`
- `run-progress-geometry.md`
- `run-progress-geometry.csv`
- `run-progress-geometry.png`
- `run-progress-geometry-edge-audit.md`
- `run-progress-geometry-edge-audit.csv`

Observed proof summary:

| Proof | Result |
| --- | --- |
| Text contract sweep | `1173` text rows, `1173 PASS`, `0 FAIL` |
| Gradient sweep | `214` gradient rows, `203 PASS`, `0 FAIL` |
| Run progress lane heights | `0.00` delta |
| Run progress text heights | `0.00` delta |
| Run progress bar heights | `0.00` delta |
| Run progress lane gaps | `0.00` delta |

The gradient sweep includes non-applicable diagnostic rows; applicable gradient
rows report no failures.

## Focused Tests

Focused commands run during the pass:

```bash
./gradlew test --tests qupath.ext.astra.PipelineLauncherTest
./gradlew test --tests qupath.ext.astra.ExtensionContractTest.pipelineHeaderUsesFluidGradientAnimation
```

Both focused checks passed during implementation.

The full `ExtensionContractTest` class was not used as the pass gate because an
unrelated repository hygiene assertion currently rejects tracked GUI proof docs.
The gradient-specific contract test passed.

## Manual Review Remaining

- Manual visual approval of gradient motion polish remains pending.
- Future changes to animated surfaces must keep the pre-generated texture
  strategy and expose direction, mode, speed, bounds, and repaint eligibility to
  diagnostics.
- The run-log fade remains static and viewport-bounded; it must not be tied to
  the dynamic header gradient controls without a new explicit design goal.
