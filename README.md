# Automated Structural Tissue Research and Analysis (ASTRA) for QuPath

This repository is the public QuPath extension for Automated Structural Tissue
Research and Analysis (ASTRA). It publishes the installable extension JARs used
by QuPath and exposes its workflows through the QuPath extension menu.

ASTRA is installed through the ASTRA QuPath catalog. The companion
`cellpose-astra` repository supplies the Python Cellpose runtime used by the
extension installer.

## What This Repository Provides

- A QuPath extension that registers workflow commands under
  `Extensions > ASTRA`.
- Cellpose runtime integration for training export, batch
  inference, validation metrics, and quality-control figures.
- A runtime installer that creates and validates the local `cellpose-astra`
  Python environment.
- Release JARs that bundle the resources needed by QuPath at runtime.

The extension remains based on the QuPath Cellpose-SAM extension lineage, but
Extension behavior is exposed through menu entries, preferences, logs,
and release artifacts.

## Installation

1. Add the ASTRA QuPath catalog:
   `https://github.com/jdsuh28/qupath-astra-catalog`
2. Install `Cellpose-SAM (ASTRA)` from the QuPath extension manager.
3. Restart QuPath when prompted.
4. Run `Extensions > ASTRA > Install/Repair Python Runtime`.
5. Wait for the installer to report validation success before running a
   workflow.

The installer creates a deterministic user-local runtime at
`~/.astra/cellpose-astra` with Python 3.10. Set `ASTRA_CONDA` to a
conda-compatible executable if `conda`, `mamba`, or `micromamba` is not on
`PATH`.

## QuPath Menu

The installed extension exposes workflows through:

- `Extensions > ASTRA > Training`
- `Extensions > ASTRA > Tuning`
- `Extensions > ASTRA > Validation`
- `Extensions > ASTRA > Analysis > Vascular`
- `Extensions > ASTRA > Analysis > Colocalization`
- `Extensions > ASTRA > Analysis > One-Shot SMA AF647`
- `Extensions > ASTRA > Analysis > Generate Regions`

## Runtime Notes

- Release JARs are the installable QuPath artifacts.
- Runtime resources are bundled into release JARs and loaded by the
  extension at runtime.
- Installer logs are written under `~/.astra/logs/install/`.
- Failed runtime installation is not marked as successful; fix the environment
  issue and rerun `Install/Repair Python Runtime`.
- Bytecode obfuscation is not enabled. Java bytecode obfuscation would only
  protect compiled extension classes and would not hide text resources bundled
  inside an installable JAR.

## Building From Source

```bash
./gradlew clean build -Ptoolchain=21
```

The development JAR is written under `build/libs`. Generated build outputs and
Javadocs are not source files and should remain untracked.

## Previewing the Launcher From Source

The launcher can be opened directly from working-tree classes for visual QA
without creating a release or reinstalling the extension in QuPath.

```bash
./gradlew previewLauncher -PastraPreviewScript=vascular
```

To capture representative launcher panels for review:

```bash
./gradlew previewLauncher \
  -PastraPreviewScript=vascular \
  -PastraPreviewSnapshots=true
```

Snapshots are written to `/private/tmp/astra-gui-snapshots` by default. The
preview uses a temporary QuPath user path at `/private/tmp/astra-qupath-dev-user`
so development GUI checks do not modify installed settings.

If JavaFX is not found automatically, point the preview at a QuPath app's
bundled JavaFX jars:

```bash
./gradlew previewLauncher \
  -PastraPreviewScript=vascular \
  -PastraJavafxModulePath=/Applications/QuPath-0.6.0-x64.app/Contents/app
```

The preview also adds non-JavaFX jars from
`/Applications/QuPath-0.6.0-x64.app/Contents/app` by default. Override that
with `-PastraQuPathAppPath=...` if QuPath is installed somewhere else.

On Apple Silicon, QuPath's x64 JavaFX bundle requires an x64 Java executable.
If one is installed, pass it explicitly:

```bash
./gradlew previewLauncher \
  -PastraPreviewScript=vascular \
  -PastraPreviewJavaExecutable=/path/to/x64/jdk/bin/java \
  -PastraJavafxModulePath=/Applications/QuPath-0.6.0-x64.app/Contents/app
```

## License

This project is distributed under the Apache License 2.0. See `LICENSE`.
