# ASTRA_PATCHES.md

Purpose
-------
This file records the files intentionally modified for the ASTRA fork so future
merges against upstream BIOP/qupath-extension-cellpose can be performed
surgically and predictably.

Guiding rule
------------
ASTRA-specific behavior should live in `src/main/java/qupath/ext/astra/` whenever
possible. Upstream-owned BIOP files should contain only small, explicit hook calls
or narrowly-scoped ASTRA modifications.

How to locate ASTRA edits quickly
---------------------------------
For preexisting upstream-owned files edited by ASTRA, every added or changed ASTRA
line is marked with the prefix `ASTRA:` so future review can begin with:

    git grep "ASTRA:"

Patched upstream-owned files
----------------------------

1. `src/main/java/qupath/ext/biop/cellpose/Cellpose2D.java`
   Why it changed:
   - imports and uses `AstraHooks`
   - delegates training/validation directory policy to `AstraHooks`
   - skips legacy post-training model promotion when ASTRA mode is enabled
   - skips implicit QC inside `train()` when ASTRA mode is enabled
   - resolves QC model path/name deterministically for explicit ASTRA QC runs
   - resolves QC python script path through `AstraHooks`
   - resolves training-results naming through `AstraHooks`
   - resolves training-graph naming through `AstraHooks`
   - centralizes QC-folder resolution through `AstraHooks`

   Merge checklist:
   - confirm all `AstraHooks.*` calls still map to valid upstream control points
   - confirm QC logic still prefers explicit model input over legacy `modelFile`
   - confirm training-results and training-graph naming use the same precedence
   - confirm post-train promotion/QC skip block remains intact

2. `src/main/java/qupath/ext/biop/cellpose/CellposeBuilder.java`
   Why it changed:
   - imports and uses `AstraHooks`
   - adds explicit `qcDirectory(...)`
   - resolves QC/results directories through `AstraHooks`
   - passes resolved ASTRA-controlled directories into `Cellpose2D`

   Merge checklist:
   - confirm `qcDirectory` field and builder method still exist
   - confirm resolved QC/results directories are still assigned into `Cellpose2D`

3. `QC/run-cellpose-qc.py`
   Why it changed:
   - adds optional deterministic output directory support
   - writes `qc_results.csv` when ASTRA passes an explicit output folder
   - refuses to overwrite an existing deterministic QC output file

   Merge checklist:
   - confirm optional `out_dir` argument still exists
   - confirm legacy fallback behavior still writes BIOP-style QC CSV when `out_dir` is absent

4. `build.gradle.kts`
   Why it changed:
   - ASTRA-specific extension metadata
   - QuPath-visible version is controlled by the ASTRA workflow
   - default in-repo version is a non-authoritative placeholder until release

   Merge checklist:
   - confirm extension metadata still identifies ASTRA uniquely
   - confirm placeholder version remains clearly non-authoritative

5. `src/main/resources/META-INF/services/qupath.lib.gui.extensions.QuPathExtension`
   Why it changed:
   - points QuPath to the ASTRA extension entrypoint instead of the upstream BIOP one

   Merge checklist:
   - confirm the provider remains `qupath.ext.astra.AstraCellposeExtension`

ASTRA-owned files (not upstream)
--------------------------------

These files do not come from upstream BIOP and should not be removed during merges:

- `src/main/java/qupath/ext/astra/AstraHooks.java`
- `src/main/java/qupath/ext/astra/AstraCellposeExtension.java`
- `src/main/resources/astra/astra.properties`

Current hook/config responsibilities
------------------------------------

`AstraHooks.java` currently provides policy/hooks for:
- `Cellpose2D`
- `CellposeBuilder`

Current responsibilities include:
- training directory resolution
- validation/QC directory resolution
- model-promotion skip policy
- implicit-QC skip policy
- legacy training-results save gating
- QC script discovery
- QC model-path resolution
- QC model-name resolution
- training-results filename resolution
- training-graph filename resolution
- QC/results directory resolution
- QC results destination resolution

`astra.properties` currently controls optional defaults for:
- `enabled`
- `skipModelMove`
- `skipAutomaticQc`
- `allowLegacyTrainingResultsSave`
- `trainingDirMode`
- `qcScriptRelativePath`
- `resultsRelativePath`

Recommended merge procedure
---------------------------

1. Merge or rebase upstream changes into the ASTRA fork.
2. Search for `ASTRA:` markers:
   - `git grep "ASTRA:"`
3. Re-check only the files listed under "Patched upstream-owned files".
4. Verify that each `AstraHooks.*` call still matches the surrounding upstream logic.
5. Rebuild the extension.
6. Run at least one ASTRA training pass and one ASTRA QC-only pass.

Notes
-----

- The ASTRA workflow is the authoritative source of the QuPath-visible version string.
- Extension git tags and ASTRA release tags are intentionally distinct concerns.
- The ASTRA manifest is the source of truth for which extension tag was used in a given ASTRA release.
