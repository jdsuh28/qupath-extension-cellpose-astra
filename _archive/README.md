# ASTRA Extension Archive

This directory is the single archive root for non-active comparison and failed
experiment files in the ASTRA extension fork.

Files here are retained for provenance only. They are not part of the active
Gradle build, QuPath extension registration, ASTRA menu resources, or release
workflow.

Active extension code lives under:

- `src/main/java/qupath/ext/astra/`
- `src/main/java/qupath/ext/biop/`
- `src/main/resources/`
- `QC/`

## Archive Map

### `v00_upstream_biop_reference/`

Reference files copied from the upstream BIOP extension for comparison while
maintaining the ASTRA fork.

### `v01_prior_astra_java_snapshots/`

Earlier ASTRA Java snapshots retained to document implementation chronology.

### `v02_broken_astra_java_experiments/`

Broken or superseded ASTRA Java experiments retained only to explain decisions
that led to the current implementation.

## Audit Rule

Do not add active code, resources, tests, or release workflow dependencies on
files in this archive. If an old idea is needed, reimplement it in the active
source tree and document the behavior there.
