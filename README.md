# Automated Structural Tissue Research and Analysis (ASTRA) Extension Legacy Branch

This branch is the single archive surface for non-active comparison files,
implementation snapshots, and failed experiments from the QuPath extension.

Files here are retained for provenance only. They are not part of the active
Gradle build, QuPath extension registration, ASTRA menu resources, or release
workflow.

The active `dev` branch intentionally does not carry an `_archive` directory.
The branch boundary is the archive boundary.

## Archive Map

### `00-upstream-biop-reference/`

Reference files copied from the upstream BIOP extension for comparison while
maintaining the extension fork.

### `01-prior-java-snapshots/`

Earlier Java snapshots retained to document implementation chronology.

### `02-broken-java-experiments/`

Broken or superseded Java experiments retained only to explain decisions that
led to the current implementation.

## Audit Rule

Do not add active code, resources, tests, or release workflow dependencies on
files in this branch. If an old idea is needed, reimplement it in the active
source tree on `dev` and document the behavior there.
