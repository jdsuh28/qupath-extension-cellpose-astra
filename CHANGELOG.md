# CHANGELOG

## ASTRA integration

### Added
- `src/main/java/qupath/ext/astra/AstraHooks.java`
  - Centralized ASTRA policy for directory resolution, model resolution, QC script lookup, QC results routing, and training artifact naming.
- `src/main/java/qupath/ext/astra/AstraCellposeExtension.java`
  - ASTRA-specific QuPath extension entrypoint.
- `src/main/resources/astra/astra.properties`
  - Optional ASTRA configuration defaults.

### Changed
- `src/main/java/qupath/ext/biop/cellpose/Cellpose2D.java`
  - Added ASTRA hook imports and fields for `qcDirectory` and `resultsDirectory`.
  - Delegated training/validation directory resolution to `AstraHooks`.
  - Separated explicit `runQC()` from `train()`.
  - Prevented automatic QC when ASTRA requests it.
  - Allowed model-promotion skip and deterministic training artifact return.
  - Routed training results and training graph outputs into `results/training`.
  - Routed QC outputs into `results/qc/qc_results.csv`.
  - Resolved QC model identity using explicit model first, `modelFile` second, and fail-fast otherwise.
  - Looked up the QC Python script through `AstraHooks`.
- `src/main/java/qupath/ext/biop/cellpose/CellposeBuilder.java`
  - Added explicit `qcDirectory(File)` and `resultsDirectory(File)` support.
  - Ensured model, training, QC, and results directories exist.
  - Propagated resolved QC/results directories into `Cellpose2D`.
- `QC/run-cellpose-qc.py`
  - Added optional `out_dir` argument.
  - Wrote deterministic `qc_results.csv` when `out_dir` is provided.
  - Refused overwrite of an existing deterministic QC output.
  - Preserved legacy behavior when `out_dir` is absent.
- `resources/META-INF/services/qupath.lib.gui.extensions.QuPathExtension`
  - Updated service entry to `qupath.ext.astra.AstraCellposeExtension`.
- `build.gradle.kts`
  - Updated extension metadata for ASTRA identity and ASTRA-controlled versioning.
