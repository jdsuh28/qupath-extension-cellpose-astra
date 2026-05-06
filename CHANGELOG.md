# CHANGELOG

## ASTRA integration

### Added
- `src/main/java/qupath/ext/astra/AstraCellposeExtension.java`
  - Provides the ASTRA-specific QuPath extension entrypoint and menu
    registration.
  - Registers the single ASTRA runtime Python executable preference.
- `src/main/java/qupath/ext/astra/AstraCellposeBuilder.java`
  - Provides the ASTRA builder surface used by base ASTRA Groovy pipelines.
- `src/main/java/qupath/ext/astra/AstraCellpose2D.java`
  - Provides ASTRA-owned Cellpose runtime behavior while preserving the BIOP
    fork layout.
  - Supports deterministic training export, validation metrics, and multi-entry
    batch inference used by training, tuning, validation, and analysis
    pipelines.
- `src/main/java/qupath/ext/astra/AstraQcFigures.java`
  - Renders ASTRA training, tuning, and validation QC figures as PNG artifacts.

### Changed
- `src/main/resources/META-INF/services/qupath.lib.gui.extensions.QuPathExtension`
  - Points QuPath to `qupath.ext.astra.AstraCellposeExtension`.
- `build.gradle.kts`
  - Uses ASTRA-specific extension metadata.

### Architecture
- The base `astra` repository owns pipeline orchestration, biological logic,
  model/parameter source policy, and publication-facing analysis decisions.
- This extension owns QuPath registration, Cellpose runtime integration, batch
  inference, validation metrics, and QC figure rendering.
- Non-active comparison files are archived under `_archive/`.
