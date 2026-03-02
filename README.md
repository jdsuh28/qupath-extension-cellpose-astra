# QuPath Cellpose Extension — ASTRA (Deterministic TRAIN/QC Separation)

This repository is the ASTRA fork of the QuPath Cellpose extension. It is a *sub-repo* within the broader **astra** tool, and it is designed to remain self-contained, deterministic, and minimal while enforcing a clean, non-overlapping separation between TRAIN and QC inside the extension.

## What this repo is (and is not)

- This repo *is* the QuPath extension code (Java + bundled scripts) implementing deterministic TRAIN/QC behavior.
- This repo is *not* responsible for installing Cellpose or creating Python environments.
- This repo is *not* published through the upstream BIOP catalog.

ASTRA provides a separate fork/repo for the Python side (**cellpose-astra**). Installation and environment provisioning is handled there (or by the umbrella **astra** tool).

## Locked behavioral guarantees

- Training and QC use separate builders
- QC does NOT create models
- QC only consumes the model explicitly passed to the QC builder
- No runtime model discovery (no filename globbing)
- No extension-side model movement
- No training-time QC artifacts
- No QC-time model artifacts

## Locked directory architecture

All results go under a single deterministic root:

ProjectRoot/
    results/
        training/
        qc/

- TRAIN results: `results/training`
- QC results: `results/qc`
- QC metrics file (deterministic): `results/qc/qc_results.csv`
- `modelDirectory` is for final/promoted models only, and QC never touches it
- The legacy `/training/test` concept is removed

## Installation (ASTRA)

Because this fork is not distributed through the BIOP catalog, installation is performed via an ASTRA-owned catalog.

High-level flow:

1) Install/provision the Python environment via **cellpose-astra** (or via the umbrella **astra** tool).
2) Add the ASTRA catalog to QuPath (the catalog is maintained and versioned by ASTRA, not BIOP).
3) Install this extension from the ASTRA catalog inside QuPath.
4) In QuPath preferences, point the extension to the Python executable from the **cellpose-astra** environment (and any other required paths as defined by ASTRA’s docs).

Notes:
- This extension assumes the required Python dependencies are already present in the target environment (including `scikit-image` for QC).
- This extension intentionally avoids enforcing ASTRA pipeline policies (preflight checks, overwrite policies, promotion rules, etc.). Those belong in ASTRA’s pipeline layer, not in the extension.

## Building from source

gradlew clean build

The output JAR will be placed under `build/libs`.

## License

This fork remains under the Apache License 2.0 (see LICENSE).
