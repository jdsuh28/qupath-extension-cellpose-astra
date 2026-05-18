# QuPath Cellpose Extension — ASTRA

This repository is the ASTRA fork of the QuPath Cellpose extension. It is a
sub-repository within the broader **astra** project and provides the Java-side
QuPath interface used by ASTRA's Groovy pipeline scripts.

## What this repo is (and is not)

- This repo **is** the QuPath extension code that exposes ASTRA scripts in the
  QuPath menu and provides the ASTRA-owned `AstraCellpose2D`,
  `AstraCellposeBuilder`, and QC figure-rendering surfaces.
- This repo **is** responsible for Cellpose-adjacent runtime behavior:
  training image export, batch inference, validation metrics, and QC figure
  rendering.
- This repo **is not** responsible for biological pipeline orchestration,
  vascular/colocalization logic, model/parameter source policy, or
  manuscript-facing analysis decisions. Those live in the base `astra` repo.
- This repo **is not** published through the upstream BIOP catalog.

ASTRA provides a separate fork/repo for the Python side
(**cellpose-astra**). The extension includes an ASTRA-owned installer that
creates and validates the runtime before registering it with QuPath.

## Active ASTRA Surfaces

- `src/main/java/qupath/ext/astra/AstraCellposeExtension.java`
  - Registers the ASTRA menu entries and the single ASTRA runtime Python
    preference.
- `src/main/java/qupath/ext/astra/AstraCellposeBuilder.java`
  - Provides the ASTRA builder surface used by base pipeline scripts.
- `src/main/java/qupath/ext/astra/AstraCellpose2D.java`
  - Owns ASTRA Cellpose runtime behavior, including batch inference,
    training export, validation metrics, and deterministic result routing.
- `src/main/java/qupath/ext/astra/AstraQcFigures.java`
  - Renders publication-oriented training, tuning, and validation QC figures.

The upstream BIOP Java package remains present to preserve fork structure and
future mergeability. ASTRA-specific behavior is implemented in the ASTRA
package where possible.

## QuPath Menu Contract

The installed ASTRA extension registers only ASTRA scripts:

- `Extensions > ASTRA > ASTRA Training`
- `Extensions > ASTRA > ASTRA Validation`
- `Extensions > ASTRA > ASTRA Tuning`
- `Extensions > ASTRA > Analysis > Vascular`
- `Extensions > ASTRA > Analysis > Colocalization`
- `Extensions > ASTRA > ASTRA Generate Regions`

BIOP example scripts remain in `src/main/resources/scripts/` as upstream
resources, but the ASTRA extension entrypoint does not expose them in the ASTRA
menu.

## Installation (ASTRA)

Because this fork is not distributed through the BIOP catalog, installation is performed via an ASTRA-owned catalog.

High-level flow:

1) Add the ASTRA catalog to QuPath (the catalog is maintained and versioned by ASTRA, not BIOP).
2) Install this extension from the ASTRA catalog inside QuPath.
3) Run `Extensions > ASTRA > Install/Repair Python Runtime`.
4) Confirm the installer reports validation success before running ASTRA pipelines.

Notes:
- The installer uses conda/miniforge first and creates a deterministic prefix
  at `~/.astra/cellpose-astra` with Python 3.10.
- Set `ASTRA_CONDA` to a conda-compatible executable if `conda`, `mamba`, or
  `micromamba` is not on `PATH`.
- A venv install path exists only as an explicit advanced path:
  `ASTRA_RUNTIME_INSTALL_STRATEGY=venv`.
- Installation is not considered complete until validation commands pass:
  Python, NumPy, torch, Cellpose-ASTRA fork marker, combined imports, and a
  Cellpose startup/version check.
- Installer logs are written under `~/.astra/logs/install/`.
- Failed installs show the command, exit code, and recent stdout/stderr. Fix
  the environment issue and rerun `Install/Repair Python Runtime`; the
  extension does not mark a failed runtime as installed.
- Base ASTRA pipeline scripts enforce model/parameter source policy,
  overwrite policy, biological analysis logic, and publication-facing
  preflight checks.

## Building from source

```bash
./gradlew clean build
```

The output JAR will be placed under `build/libs`.

## Archive

Non-active comparison files live under `_archive/`. They are retained for
provenance only and are not referenced by the active build or QuPath menu.

## License

This fork remains under the Apache License 2.0 (see LICENSE).
