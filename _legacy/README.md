# ASTRA Cellpose Extension Legacy Archive

This directory is the single archive root for stale extension code and upstream
reference material.

Files here are retained for provenance only. They are not part of the active
Gradle source set, are not bundled as QuPath menu scripts, and must not be used
as runtime dependencies.

## Archive Map

- `astra_superseded/`
  - Earlier ASTRA-specific Java classes kept for comparison with the active
    `src/main/java/qupath/ext/astra/` implementation.
- `broken_experiments/`
  - Known-broken experimental Java classes. These are intentionally segregated
    from active source.
- `original_biop_snapshot/`
  - Small upstream BIOP reference snapshot retained for provenance.
- `upstream_resources/`
  - Upstream BIOP template scripts and notebook material no longer registered
    or packaged as active ASTRA extension resources.

## Active Source

Active Java source lives under:

- `src/main/java/qupath/ext/astra/`
- `src/main/java/qupath/ext/biop/`

Active bundled resources live under:

- `src/main/resources/`

The ASTRA umbrella release workflow vendors the current ASTRA pipeline folders
into `src/main/resources/astra/` before building release JARs.
