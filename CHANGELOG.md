# CHANGELOG

## ASTRA integration

### Added
- `src/main/java/qupath/ext/astra/AstraHooks.java`
  - Centralizes ASTRA policy for directory resolution, model resolution, QC script lookup, QC output routing, installed-jar matching, and deterministic artifact naming.
- `src/main/java/qupath/ext/astra/AstraCellposeExtension.java`
  - Provides the ASTRA-specific QuPath extension entrypoint and metadata.
- `src/main/resources/astra/astra.properties`
  - Exposes ASTRA hook booleans and path defaults, including deterministic fail-fast controls for QC and training-result routing.

### Changed
- `src/main/java/qupath/ext/biop/cellpose/Cellpose2D.java`
  - Keeps all upstream edits inside `ASTRA START` / `ASTRA END` gates.
  - Separates training, model promotion, and QC decisions deterministically.
  - Fails fast if ASTRA automatic QC is requested while model promotion is disabled.
  - Saves `results/training/training_results.csv` after training.
  - Saves `results/training/training_graph.png` automatically after training.
  - Resolves QC/training artifact identity with ASTRA precedence: explicit model, then `modelFile`, otherwise fail.
  - Resolves installed extension jars for both ASTRA and BIOP naming patterns.
- `src/main/java/qupath/ext/biop/cellpose/CellposeBuilder.java`
  - Keeps all upstream edits inside `ASTRA START` / `ASTRA END` gates.
  - Ensures model, training, QC, and results directories exist.
  - Uses `<root>/qc` as the ASTRA default QC directory.
  - Propagates resolved ASTRA QC/results directories into `Cellpose2D`.
- `QC/run-cellpose-qc.py`
  - Keeps all upstream edits inside `ASTRA START` / `ASTRA END` gates.
  - Accepts an optional deterministic output directory.
  - Writes `qc_results.csv` when an ASTRA output directory is provided.
  - Refuses to overwrite an existing deterministic QC results file.
  - Preserves baseline behavior when no ASTRA output directory is supplied, while refusing silent overwrite in deterministic ASTRA mode.
- `resources/META-INF/services/qupath.lib.gui.extensions.QuPathExtension`
  - Points QuPath to `qupath.ext.astra.AstraCellposeExtension`.
- `build.gradle.kts`
  - Uses ASTRA-specific extension metadata with a placeholder development version.
