package qupath.ext.astra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for ASTRA extension registration and active-source hygiene.
 */
class ExtensionContractTest {

    private static final File ROOT = new File(".").getAbsoluteFile();

    private static final Set<String> ASTRA_PREFIXED_CLASS_ALLOWLIST = Set.of(
            "AstraCellpose2D.java",
            "AstraCellposeBuilder.java",
            "AstraCellposeExtension.java"
    );

    /**
     * Verifies the installed extension service points to the ASTRA entrypoint
     * rather than the upstream BIOP entrypoint.
     *
     * @throws Exception if the service descriptor cannot be read.
     */
    @Test
    void serviceDescriptorRegistersAstraExtensionEntrypoint() throws Exception {
        File service = new File(ROOT,
                "src/main/resources/META-INF/services/qupath.lib.gui.extensions.QuPathExtension");

        assertTrue(service.isFile());
        assertEquals("qupath.ext.astra.AstraCellposeExtension", Files.readString(service.toPath()).trim());
    }

    /**
     * Verifies public documentation presents this repository as the ASTRA
     * QuPath installation surface and does not disclose internal repository
     * topology.
     *
     * @throws Exception if public documentation cannot be read.
     */
    @Test
    void publicDocsDoNotExposeInternalRepositoryTopology() throws Exception {
        List<File> publicDocs = List.of(
                new File(ROOT, "README.md"),
                new File(ROOT, "RELEASE_PACKAGING.md")
        );

        for (File doc : publicDocs) {
            String text = Files.readString(doc.toPath()).toLowerCase();
            assertFalse(text.contains("private repo"), doc.getPath());
            assertFalse(text.contains("private astra"), doc.getPath());
            assertFalse(text.contains("base astra"), doc.getPath());
            assertFalse(text.contains("base repo"), doc.getPath());
            assertFalse(text.contains("base repository"), doc.getPath());
            assertFalse(text.contains("sub-repository"), doc.getPath());
            assertFalse(text.contains("broader astra"), doc.getPath());
        }
    }

    /**
     * Verifies generated, local, and release-vendored files are not tracked as
     * active source in the public extension repository.
     *
     * @throws Exception if tracked files cannot be listed.
     */
    @Test
    void trackedFilesExcludeGeneratedLocalAndVendoredRuntimeArtifacts() throws Exception {
        List<String> violations = trackedFiles().stream()
                .filter(path -> path.equals(".DS_Store")
                        || path.contains("/.DS_Store")
                        || path.contains("/._")
                        || path.startsWith("._")
                        || path.startsWith(".gradle/")
                        || path.startsWith("build/")
                        || path.startsWith("bin/")
                        || path.startsWith("target/")
                        || path.startsWith("out/")
                        || path.startsWith("docs/")
                        || path.startsWith("files/")
                        || path.startsWith("QC/")
                        || path.startsWith("src/main/resources/astra/"))
                .toList();

        assertTrue(violations.isEmpty(), "Forbidden tracked repository files: " + violations);
    }

    /**
     * Verifies the validation helper is packaged as a classpath resource rather
     * than resolved from a root-level QC folder next to an installed JAR.
     *
     * @throws Exception if source or helper resources cannot be inspected.
     */
    @Test
    void validationMetricsHelperIsPackagedResource() throws Exception {
        File helperResource = new File(ROOT,
                "src/main/resources/qupath/ext/astra/qc/run-cellpose-qc.py");
        String source = Files.readString(new File(ROOT,
                "src/main/java/qupath/ext/astra/AstraCellpose2D.java").toPath());

        assertTrue(helperResource.isFile());
        assertFalse(new File(ROOT, "QC/run-cellpose-qc.py").exists());
        assertTrue(source.contains("VALIDATION_METRICS_HELPER_RESOURCE = \"qupath/ext/astra/qc/run-cellpose-qc.py\""));
        assertTrue(source.contains("extractValidationMetricsHelperFile()"));
        assertFalse(source.contains("resolveInstalledExtensionRoot"));
        assertFalse(source.contains("QC/run-cellpose-qc.py"));

        File extracted = AstraCellpose2D.extractValidationMetricsHelperFile();
        assertTrue(extracted.isFile());
        assertTrue(Files.readString(extracted.toPath()).contains("Compute validation metrics"));
    }

    /**
     * Verifies internal extension implementation classes do not carry an
     * unnecessary ASTRA prefix when the package and menu branding already
     * provide that identity.
     *
     * @throws Exception if source files cannot be inspected.
     */
    @Test
    void internalImplementationClassesDoNotUseAstraPrefix() throws Exception {
        File sourceDir = new File(ROOT, "src/main/java/qupath/ext/astra");
        List<File> sourceFiles = new ArrayList<>();
        Set<String> violations = new HashSet<>();

        collectFiles(sourceDir, sourceFiles);
        sourceFiles.stream()
                .filter(file -> file.getName().endsWith(".java"))
                .filter(file -> file.getName().startsWith("Astra"))
                .filter(file -> !ASTRA_PREFIXED_CLASS_ALLOWLIST.contains(file.getName()))
                .map(File::getName)
                .forEach(violations::add);

        assertTrue(violations.isEmpty(), "Internal extension classes must not use pointless Astra prefixes: " + violations);
    }

    /**
     * Verifies the main pipeline header uses a continuous JavaFX gradient animation
     * rather than swapping static gradient images.
     *
     * @throws Exception if source files cannot be inspected.
     */
    @Test
    void pipelineHeaderUsesFluidGradientAnimation() throws Exception {
        String header = Files.readString(new File(ROOT,
                "src/main/java/qupath/ext/astra/AnimatedGradientHeader.java").toPath());
        String launcher = Files.readString(new File(ROOT,
                "src/main/java/qupath/ext/astra/PipelineLauncher.java").toPath());
        String logView = Files.readString(new File(ROOT,
                "src/main/java/qupath/ext/astra/StyledLogView.java").toPath());

        assertTrue(header.contains("AnimationTimer"));
        assertTrue(header.contains("CycleMethod.REPEAT"));
        assertTrue(header.contains("FRAME_INTERVAL_NANOS = 33_333_333L"));
        assertTrue(header.contains("CYCLE_SECONDS = 24.0d"));
        assertTrue(header.contains("canvas.setManaged(false)"));
        assertTrue(launcher.contains("new AnimatedGradientHeader(header)"));
        assertFalse(logView.contains("new AnimatedGradientHeader"));
        assertFalse(launcher.contains("installDynamicHeaderGradient"));
        assertFalse(launcher.contains("Timeline timeline = new Timeline"));
        assertFalse(header.contains("ImageView"));
    }

    /**
     * Verifies ASTRA menu registration exposes only ASTRA pipeline scripts.
     *
     * @throws Exception if the private script map cannot be inspected.
     */
    @Test
    @SuppressWarnings("unchecked")
    void scriptResourceMapRegistersOnlyAstraMenuScripts() throws Exception {
        Method method = AstraCellposeExtension.class.getDeclaredMethod("createScriptResources");
        method.setAccessible(true);

        Map<String, String> scripts = (Map<String, String>) method.invoke(null);

        assertEquals(List.of("Training", "Tuning", "Validation", "Analysis>Vascular", "Analysis>Colocalization", "Analysis>One-Shot SMA AF647", "Analysis>Generate Regions"),
                new ArrayList<>(scripts.keySet()));
        assertEquals("astra/modules/pipelines/training/src/main/groovy/training.groovy", scripts.get("Training"));
        assertEquals("astra/modules/pipelines/tuning/src/main/groovy/tuning.groovy", scripts.get("Tuning"));
        assertEquals("astra/modules/pipelines/validation/src/main/groovy/validation.groovy", scripts.get("Validation"));
        assertEquals("astra/modules/pipelines/analysis/vascular/src/main/groovy/vascular.groovy", scripts.get("Analysis>Vascular"));
        assertEquals("astra/modules/pipelines/analysis/colocalization/src/main/groovy/colocalization.groovy", scripts.get("Analysis>Colocalization"));
        assertEquals("astra/modules/tools/sma-af647-oneshot/src/main/groovy/smaAf647Oneshot.groovy", scripts.get("Analysis>One-Shot SMA AF647"));
        assertEquals("astra/modules/tools/generate-regions/src/main/groovy/generateRegions.groovy", scripts.get("Analysis>Generate Regions"));

        scripts.values().forEach(path -> assertTrue(path.startsWith("astra/"), path));
        scripts.values().forEach(path -> assertFalse(path.contains("Cellpose_"), path));
    }

    /**
     * Verifies extension runtime resources do not vendor repository contract
     * fixtures or private OME-TIFF images.
     */
    @Test
    void runtimeResourcesDoNotVendorContractsOrVesselFixtures() throws Exception {
        List<File> inspected = new ArrayList<>();
        collectFiles(new File(ROOT, "src/main/resources"), inspected);
        inspected.add(new File(ROOT, "build.gradle.kts"));
        inspected.add(new File(ROOT, "settings.gradle.kts"));

        for (File file : inspected) {
            String path = file.getPath().replace(File.separatorChar, '/');
            assertFalse(path.endsWith(".DS_Store"), path);
            assertFalse(path.contains("/._"), path);
            assertFalse(path.contains("/build/"), path);
            assertFalse(path.contains("/.gradle/"), path);
            assertFalse(path.contains("contracts/src/test/resources"), path);
            assertFalse(path.contains("test-fixtures"), path);
            assertFalse(path.endsWith(".ome.tif"), path);
            assertFalse(path.endsWith(".ome.tiff"), path);

            if (!isTextInspectionFile(path)) {
                continue;
            }
            String text = Files.readString(file.toPath());
            if (!isVendoredTestSource(path)) {
                assertFalse(text.contains("contracts/src/test/resources"), file.getPath());
                assertFalse(text.contains("test-fixtures"), file.getPath());
                assertFalse(text.contains(".ome.tif"), file.getPath());
                assertFalse(text.contains(".ome.tiff"), file.getPath());
                assertFalse(text.contains("vessels/manifest.json"), file.getPath());
            }
        }
    }

    /**
     * Returns true for ASTRA test-source files intentionally copied into the
     * release-build workspace by the manifest workflow's existing vendoring
     * strategy.  The runtime guardrail still rejects actual fixture files and
     * production/runtime fixture references.
     */
    private static boolean isVendoredTestSource(String path) {
        return path.contains("/src/main/resources/astra/")
                && path.contains("/src/test/");
    }

    /**
     * Verifies extension-owned script registration cannot expose contract
     * fixtures as runtime menu entries.
     *
     * @throws Exception if private script map cannot be inspected.
     */
    @Test
    @SuppressWarnings("unchecked")
    void scriptResourceMapDoesNotExposeContractFixtures() throws Exception {
        Method method = AstraCellposeExtension.class.getDeclaredMethod("createScriptResources");
        method.setAccessible(true);

        Map<String, String> scripts = (Map<String, String>) method.invoke(null);

        scripts.values().forEach(path -> {
            assertFalse(path.contains("contracts"), path);
            assertFalse(path.contains("test-fixtures"), path);
            assertFalse(path.contains("vessels"), path);
            assertFalse(path.endsWith(".ome.tif"), path);
            assertFalse(path.endsWith(".ome.tiff"), path);
        });
    }

    /**
     * Verifies release builds can load the manifest set from runtime JAR
     * resource paths without the sibling base checkout fallback.
     *
     * @param tempDir temporary classpath root.
     * @throws Exception if the resource fixture cannot be created or loaded.
     */
    @Test
    void manifestSetLoadsBundledClasspathResourcesWithoutLocalFallback(@TempDir Path tempDir) throws Exception {
        Path localRoot = Path.of("../astra/rulebook/manifests");
        for (String name : List.of("index.json", "runtime.json", "modules.json", "inputs.json", "gui.json", "outputs.json", "release.json")) {
            Path resource = tempDir.resolve(ManifestSet.BUNDLED_ROOT).resolve(name);
            Files.createDirectories(resource.getParent());
            Path vendored = Path.of("src/main/resources").resolve(ManifestSet.BUNDLED_ROOT).resolve(name);
            Path localBase = localRoot.resolve(name);
            Files.copy(Files.isRegularFile(vendored) ? vendored : localBase, resource);
        }

        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{tempDir.toUri().toURL()}, null)) {
            ManifestSet manifests = ManifestSet.load(loader, tempDir.resolve("missing/manifests"));

            assertEquals(1.0, manifests.root().get("index") instanceof Map<?, ?> index ? index.get("schemaVersion") : null);
            assertTrue(manifests.runnable("colocalization").isPresent());
            assertEquals("astra/modules/pipelines/training/src/main/groovy/training.groovy",
                    manifests.scriptResources().get("Training"));
        }
    }

    /**
     * Verifies the ASTRA runtime installer uses the pinned public fork and a
     * deterministic user-local runtime path.
     */
    @Test
    void runtimeInstallerUsesDeterministicRuntime() {
        assertEquals("v4.0.8+astra.3", RuntimeInstaller.DEFAULT_CELLPOSE_REF);
        assertEquals("git+https://github.com/jdsuh28/cellpose-astra.git@v4.0.8+astra.3",
                RuntimeInstaller.cellposePackageSpec());
        assertEquals("cellpose-astra", RuntimeInstaller.runtimeDirectory().getName());
        assertTrue(RuntimeInstaller.runtimePythonExecutable(new File("runtime")).getPath().contains("runtime"));
    }

    /**
     * Verifies the default installer builds a conda-prefix runtime rather than
     * silently creating a venv.
     */
    @Test
    void runtimeInstallerBuildsCondaPrefixCommand() {
        List<String> command = RuntimeInstaller.condaCreateCommand("conda", new File("/tmp/cellpose-astra"));

        assertEquals(List.of("conda", "create", "-y", "-p", "/tmp/cellpose-astra", "python=3.10"), command);
    }

    /**
     * Verifies runtime validation checks Python, NumPy, torch, Cellpose, the
     * ASTRA fork marker, and Cellpose startup before reporting success.
     */
    @Test
    void runtimeInstallerValidationCommandsCoverRequiredImports() {
        List<List<String>> commands = RuntimeInstaller.validationCommands(new File("/runtime/bin/python"));
        String joined = commands.toString();

        assertTrue(joined.contains("--version"));
        assertTrue(joined.contains("import numpy"));
        assertTrue(joined.contains("import torch"));
        assertTrue(joined.contains("import cellpose"));
        assertTrue(joined.contains("astra"));
        assertTrue(joined.contains("-m, cellpose, --version"));
        assertFalse(joined.contains("segment_anything"));
    }

    /**
     * Verifies command failures include the command, exit code, and bounded
     * recent output for GUI diagnostics.
     */
    @Test
    void runtimeInstallerFormatsValidationFailureDetails() {
        String message = RuntimeInstaller.formatCommandFailure(
                List.of("python", "-c", "import cellpose"),
                new RuntimeInstaller.CommandResult(7, "line1\nline2\nline3")
        );

        assertTrue(message.contains("exit code 7"));
        assertTrue(message.contains("python -c import cellpose"));
        assertTrue(message.contains("line3"));
    }

    /**
     * Verifies installer cancellation actively destroys a running process.
     */
    @Test
    void runtimeInstallerCancellationTerminatesActiveProcess() {
        FakeProcess process = new FakeProcess();

        assertTrue(RuntimeInstaller.terminateProcessForCancellation(process, Duration.ofMillis(1)));
        assertTrue(process.destroyCalled);
        assertTrue(process.destroyForciblyCalled);
    }

    /**
     * Verifies runtime registration remains ordered after validation.
     *
     * @throws Exception if the installer source cannot be read.
     */
    @Test
    void runtimeInstallerAppliesRuntimePathOnlyAfterValidation() throws Exception {
        String source = Files.readString(new File(ROOT, "src/main/java/qupath/ext/astra/RuntimeInstaller.java").toPath());
        int verify = source.indexOf("verifyRuntime(python, progress, logFile);");
        int apply = source.indexOf("applyRuntimePath(runtimePythonPath, python);");

        assertTrue(verify >= 0);
        assertTrue(apply > verify);
    }

    /**
     * Verifies cancelled installation is reported as cancellation/failure and
     * cannot execute the success branch before validation.
     *
     * @throws Exception if the installer source cannot be read.
     */
    @Test
    void runtimeInstallerCancellationCannotReachSuccessPath() throws Exception {
        String source = Files.readString(new File(ROOT, "src/main/java/qupath/ext/astra/RuntimeInstaller.java").toPath());

        assertTrue(source.contains("throw new CancellationException(\"ASTRA runtime installation cancelled by user"));
        assertTrue(source.contains("progress.failed(t, logFile);"));
        assertTrue(source.contains("progress.done(\"ASTRA runtime is ready:"));
    }

    /**
     * Verifies the Java Cellpose runner reads ASTRA's explicit runtime
     * preference directly before creating a process runner.
     *
     * @throws Exception if source cannot be read.
     */
    @Test
    void cellposeRunnerSyncsRuntimePreferenceDirectly() throws Exception {
        String source = Files.readString(new File(ROOT, "src/main/java/qupath/ext/astra/AstraCellpose2D.java").toPath());

        assertTrue(source.contains("RUNTIME_PYTHON_PATH_KEY = \"qupath.ext.astra.runtimePythonPath\""));
        assertTrue(source.contains("syncRuntimePythonPreference()"));
        assertTrue(source.contains("PathPrefs.createPersistentPreference(RUNTIME_PYTHON_PATH_KEY, \"\")"));
        assertTrue(source.contains("pythonExecutable.isAbsolute()"));
        assertTrue(source.contains("pythonExecutable.isFile()"));
        assertTrue(source.contains("cellposeSetup.setCellposePythonPath(absolutePath);"));
        assertTrue(source.contains("CellposeSetup may not have been synchronized yet"));
        assertTrue(source.contains("Automated Structural Tissue Research and Analysis (ASTRA) > Cellpose Runtime Python Executable"));
        assertFalse(source.contains("cellposeSAMPythonPath"));
    }

    /**
     * Verifies ASTRA-owned Cellpose detection cannot fall through to BIOP's
     * legacy runtime preference selection.
     *
     * @throws Exception if source cannot be read.
     */
    @Test
    void cellposeOverridesBiopRuntimeSelection() throws Exception {
        String base = Files.readString(new File(ROOT, "src/main/java/qupath/ext/biop/cellpose/Cellpose2D.java").toPath());
        String runtimeSource = Files.readString(new File(ROOT, "src/main/java/qupath/ext/astra/AstraCellpose2D.java").toPath());

        assertTrue(base.contains("protected VirtualEnvironmentRunner getVirtualEnvironmentRunner()"));
        assertTrue(runtimeSource.contains("protected VirtualEnvironmentRunner getVirtualEnvironmentRunner()"));
        assertTrue(runtimeSource.contains("return createRuntimeRunner();"));
        assertTrue(runtimeSource.contains("VirtualEnvironmentRunner.EnvType.EXE"));
        assertFalse(runtimeSource.contains("EnvType.CONDA"));
        assertFalse(runtimeSource.contains("\"CALL\""));
        assertFalse(runtimeSource.contains("\"activate\""));
    }

    /**
     * Verifies ASTRA-owned default folders are rooted under projectRoot/astra
     * when callers do not provide explicit directories.
     */
    @Test
    void defaultDirectoriesResolveUnderArtifactRoot() {
        File project = new File("/tmp/astra-extension-project");

        assertEquals(new File(project, "astra"), AstraCellpose2D.resolveArtifactRootDirectory(project));
        assertEquals(new File(project, "astra/models"), AstraCellpose2D.resolveModelDirectory(project, null));
        assertEquals(new File(project, "astra/training"), AstraCellpose2D.resolveTrainingRootDirectory(project, null));
        assertEquals(new File(project, "astra/validation"), AstraCellpose2D.resolveValidationInputDirectory(project, null));
        assertEquals(new File(project, "astra/results"), AstraCellpose2D.resolveResultsDirectory(project, null));
    }

    /**
     * Verifies ASTRA training uses the explicit train/test/models runtime
     * contract and passes the ASTRA save-root hook to Cellpose-ASTRA.
     *
     * @throws Exception if source cannot be read.
     */
    @Test
    void trainingUsesThreeFolderRuntimeContract() throws Exception {
        File root = new File("/tmp/astra-training-root");
        String runtime = Files.readString(new File(ROOT, "src/main/java/qupath/ext/astra/AstraCellpose2D.java").toPath());
        String builder = Files.readString(new File(ROOT, "src/main/java/qupath/ext/astra/AstraCellposeBuilder.java").toPath());

        assertEquals(new File(root, "train"), AstraCellpose2D.resolveTrainingDirectory(root));
        assertEquals(new File(root, "models"), AstraCellpose2D.trainingArtifactReturnValue(root));
        assertTrue(runtime.contains("cellposeArguments.add(\"--model_save_root\")"));
        assertTrue(runtime.contains("cellposeArguments.add(this.groundTruthDirectory.getAbsolutePath())"));
        assertTrue(runtime.contains("return trainingArtifactReturnValue(this.groundTruthDirectory);"));
        assertTrue(builder.contains("persistTrainingArtifacts(boolean persist)"));
        assertTrue(builder.contains("runtime.setPersistTrainingArtifacts(persistTrainingArtifacts);"));
    }

    /**
     * Verifies Cellpose subprocess failure cannot be reported as a successful
     * zero-cell detection.
     *
     * @throws Exception if source cannot be read.
     */
    @Test
    void cellposeDetectionFailureIsFatal() throws Exception {
        String base = Files.readString(new File(ROOT, "src/main/java/qupath/ext/biop/cellpose/Cellpose2D.java").toPath());
        String runtimeSource = Files.readString(new File(ROOT, "src/main/java/qupath/ext/astra/AstraCellpose2D.java").toPath());

        assertTrue(base.contains("requireSuccessfulProcessExit(veRunner, \"Cellpose process\")"));
        assertTrue(base.contains("throw e;"));
        assertTrue(base.contains("exited with value"));
        assertFalse(base.contains("catch (IOException | InterruptedException e) {\n            logger.error(\"Failed to Run Cellpose\", e);\n            return;"));
        assertTrue(base.contains("throw new IllegalStateException(\"Cellpose detection failed before masks could be read.\", e);"));
        assertTrue(base.contains("Thread.currentThread().interrupt();"));
        assertTrue(base.contains("pool.submit(runnable).get();"));
        assertTrue(base.contains("throw new IllegalStateException(\"Cellpose detection was interrupted.\", e);"));
        assertTrue(runtimeSource.contains("requireSuccessfulProcessExit(veRunner, \"Cellpose process\")"));
        assertTrue(runtimeSource.contains("exited with value"));
        assertTrue(runtimeSource.contains("Process log:"));
    }

    private static final class FakeProcess extends Process {
        boolean destroyCalled;
        boolean destroyForciblyCalled;

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 1;
        }

        @Override
        public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) {
            return false;
        }

        @Override
        public int exitValue() {
            throw new IllegalThreadStateException("still running");
        }

        @Override
        public void destroy() {
            destroyCalled = true;
        }

        @Override
        public Process destroyForcibly() {
            destroyForciblyCalled = true;
            return this;
        }

        @Override
        public boolean isAlive() {
            return !destroyForciblyCalled;
        }
    }

    /**
     * Verifies the launcher keeps common setup fields in the default view while
     * moving specialist tuning controls behind the advanced section.
     *
     * @throws Exception if launcher internals cannot be inspected.
     */
    @Test
    void launcherSeparatesBasicAndAdvancedConfiguration() throws Exception {
        String script = """
                final String CLASS_ANALYSIS_REGION = "ROI"
                final List NUC_MODEL_SOURCE_OPTIONS = ["MODEL_NAME", "SAVED", "FILE"]
                final String NUC_MODEL_SOURCE = "MODEL_NAME"
                final String CHANNEL_DAPI = "DAPI"
                final List CELLPOSE_CELL_CHANNELS = ["AF488", "DAPI"]
                final List COLOCALIZATION_CHECKS = []
                final List THRESHOLD_MODE_OPTIONS = ["LOG_GAUSSIAN_MIXTURE", "MANUAL"]
                final String THRESHOLD_MODE = "LOG_GAUSSIAN_MIXTURE"
                final List TRAIN_TARGET_OPTIONS = ["NUCLEUS", "CELL", "BOTH"]
                final String TRAIN_TARGET = "NUCLEUS"
                final String SEARCH_MODE = "TEST"
                final String VALIDATION_MODE = "FULL"
                final List CHANNELS_FOR_NUCLEUS = ["DAPI"]
                final boolean USE_BATCH_MODE = true
                final boolean USE_PIXEL_SCALING = true
                final double NUC_DIAMETER_UM = 2.5d
                final boolean LOG_VIEWER_PROGRESS_FAILURES = false
                final Map cfg = [:]
                """;

        List<?> constants = PipelineLauncher.extractEditableConstants(script);

        assertFalse(isAdvanced(constants, "CLASS_ANALYSIS_REGION"));
        assertFalse(isAdvanced(constants, "CHANNEL_DAPI"));
        assertFalse(isAdvanced(constants, "CELLPOSE_CELL_CHANNELS"));
        assertFalse(isAdvanced(constants, "COLOCALIZATION_CHECKS"));
        assertFalse(isAdvanced(constants, "THRESHOLD_MODE"));
        assertFalse(isAdvanced(constants, "TRAIN_TARGET"));
        assertFalse(isAdvanced(constants, "SEARCH_MODE"));
        assertFalse(isAdvanced(constants, "VALIDATION_MODE"));
        assertFalse(isAdvanced(constants, "CHANNELS_FOR_NUCLEUS"));
        assertFalse(isAdvanced(constants, "USE_BATCH_MODE"));
        assertFalse(isAdvanced(constants, "USE_PIXEL_SCALING"));
        assertTrue(isAdvanced(constants, "NUC_DIAMETER_UM"));
        assertTrue(isAdvanced(constants, "LOG_VIEWER_PROGRESS_FAILURES"));
    }

    /**
     * Verifies pixel scaling is visible only when batch execution is enabled.
     *
     * @throws Exception if source cannot be read.
     */
    @Test
    void launcherGatesPixelScalingByBatchMode() throws Exception {
        String source = Files.readString(new File(ROOT, "src/main/java/qupath/ext/astra/PipelineLauncher.java").toPath());

        assertTrue(source.contains("setVisible(rows, \"USE_PIXEL_SCALING\", isChecked(byName, \"USE_BATCH_MODE\"));"));
    }

    /**
     * Verifies finite choices come from script-owned metadata constants, not a
     * launcher-side variable-name table.
     *
     * @throws Exception if launcher internals cannot be inspected.
     */
    @Test
    void launcherUsesScriptDeclaredOptions() throws Exception {
        String script = """
                final List NUC_MODEL_SOURCE_OPTIONS = ["MODEL_NAME", "SAVED", "FILE"]
                final String NUC_MODEL_SOURCE = "MODEL_NAME"
                final List CUSTOM_MODE_OPTIONS = ["ALPHA", "BETA"]
                final String CUSTOM_MODE = "ALPHA"
                final Map cfg = [:]
                """;

        List<?> constants = PipelineLauncher.extractEditableConstants(script);

        assertEquals(2, constants.size());
        assertFalse(hasConstant(constants, "NUC_MODEL_SOURCE_OPTIONS"));
        assertEquals(List.of("MODEL_NAME", "SAVED", "FILE"), optionsFor(constants, "NUC_MODEL_SOURCE"));
        assertEquals(List.of("ALPHA", "BETA"), optionsFor(constants, "CUSTOM_MODE"));
    }

    @Test
    void launcherReadsScriptDeclaredHelp() throws Exception {
        String script = """
                final String CELLPOSE_CELL_CHANNELS_HELP = "Segmentation channels defined by ASTRA script metadata."
                final List CELLPOSE_CELL_CHANNELS = ["AF488"]
                final String FALLBACK_VALUE = "default"
                final Map cfg = [:]
                """;

        List<?> constants = PipelineLauncher.extractEditableConstants(script);

        assertFalse(hasConstant(constants, "CELLPOSE_CELL_CHANNELS_HELP"));
        assertTrue(helpFor(constants, "CELLPOSE_CELL_CHANNELS").contains("Segmentation channels"));
        assertTrue(helpFor(constants, "FALLBACK_VALUE").contains("did not provide help metadata"));
    }

    @Test
    void launcherSchemaIdentityChangesWithScriptContract() {
        String first = """
                final List MODE_OPTIONS = ["A", "B"]
                final String MODE_HELP = "Choose a mode."
                final String MODE = "A"
                final Map cfg = [:]
                """;
        String second = """
                final List MODE_OPTIONS = ["A", "B", "C"]
                final String MODE_HELP = "Choose a mode."
                final String MODE = "A"
                final Map cfg = [:]
                """;

        String firstSchema = PipelineLauncher.schemaIdentity(PipelineLauncher.extractEditableConstants(first));
        String secondSchema = PipelineLauncher.schemaIdentity(PipelineLauncher.extractEditableConstants(second));

        assertFalse(firstSchema.equals(secondSchema));
    }

    /**
     * Verifies GUI edits do not concatenate adjacent Groovy declarations.
     */
    @Test
    void launcherPreservesDeclarationLineBreaks() {
        String script = """
                final String CLASS_ANALYSIS_REGION = "ROI"
                final boolean USE_WHOLE_IMAGE_IF_NO_REGION = true
                final Map cfg = [:]
                """;

        String configured = PipelineLauncher.applyConstants(script, PipelineLauncher.extractEditableConstants(script));

        assertTrue(configured.contains("\"ROI\"\nfinal boolean USE_WHOLE_IMAGE_IF_NO_REGION"));
        assertFalse(configured.contains("\"ROI\"final boolean"));
    }

    /**
     * Verifies retired implementation snapshots live only on the dedicated
     * legacy branch, not under wrapper folders on dev.
     */
    @Test
    void legacySnapshotsDoNotLiveOnDevBranch() {
        assertFalse(new File(ROOT, "_archive").exists());
        assertFalse(new File(ROOT, "_legacy").exists());
        assertFalse(new File(ROOT, "_broken").exists());
        assertFalse(new File(ROOT, "_original").exists());

        assertActiveTreeDoesNotReferenceArchive(new File(ROOT, "src/main/java"));
        assertActiveTreeDoesNotReferenceArchive(new File(ROOT, "src/main/resources"));
    }

    /**
     * Checks a source tree for active dependencies on archive paths.
     *
     * @param root active tree to inspect.
     */
    private static void assertActiveTreeDoesNotReferenceArchive(File root) {
        if (!root.exists()) {
            return;
        }
        File[] files = root.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                assertActiveTreeDoesNotReferenceArchive(file);
                continue;
            }
            try {
                String path = file.getPath().replace(File.separatorChar, '/');
                assertFalse(path.endsWith(".DS_Store"), path);
                assertFalse(path.contains("/._"), path);
                if (!isTextInspectionFile(path)) {
                    continue;
                }
                String text = Files.readString(file.toPath());
                assertFalse(text.contains("_archive/"), file.getPath());
                assertFalse(text.contains("_legacy/"), file.getPath());
                assertFalse(text.contains("_broken/"), file.getPath());
                assertFalse(text.contains("_original/"), file.getPath());
            } catch (Exception e) {
                throw new AssertionError("Failed to inspect " + file.getPath(), e);
            }
        }
    }

    /**
     * Recursively collects files under a root.
     *
     * @param root directory or file to collect.
     * @param files mutable destination list.
     */
    private static void collectFiles(File root, List<File> files) {
        if (!root.exists()) {
            return;
        }
        if (root.isFile()) {
            files.add(root);
            return;
        }
        File[] children = root.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            collectFiles(child, files);
        }
    }

    /**
     * Returns true for text files that source-hygiene tests should inspect for
     * literal stale-path references.
     *
     * @param path normalized repository path.
     * @return true if the file is a text source/config/resource.
     */
    private static boolean isTextInspectionFile(String path) {
        return path.endsWith(".java")
                || path.endsWith(".groovy")
                || path.endsWith(".kts")
                || path.endsWith(".properties")
                || path.endsWith(".py")
                || path.endsWith(".gitkeep")
                || path.contains("/META-INF/services/");
    }

    /**
     * Lists repository-tracked files using Git.
     *
     * @return tracked repository paths.
     * @throws Exception if Git cannot list tracked files.
     */
    private static List<String> trackedFiles() throws Exception {
        Process process = new ProcessBuilder("git", "ls-files")
                .directory(ROOT)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        assertEquals(0, exitCode, output);
        return output.lines().toList();
    }

    private static boolean hasConstant(List<?> constants, String name) throws Exception {
        for (Object constant : constants) {
            Field field = constant.getClass().getDeclaredField("name");
            field.setAccessible(true);
            if (name.equals(field.get(constant))) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<String> optionsFor(List<?> constants, String name) throws Exception {
        for (Object constant : constants) {
            Field nameField = constant.getClass().getDeclaredField("name");
            nameField.setAccessible(true);
            if (!name.equals(nameField.get(constant))) {
                continue;
            }
            Field optionsField = constant.getClass().getDeclaredField("options");
            optionsField.setAccessible(true);
            return (List<String>) optionsField.get(constant);
        }
        return List.of();
    }

    private static boolean isAdvanced(List<?> constants, String name) throws Exception {
        for (Object constant : constants) {
            var type = constant.getClass();
            var nameField = type.getDeclaredField("name");
            nameField.setAccessible(true);
            if (!name.equals(nameField.get(constant))) {
                continue;
            }
            var advancedField = type.getDeclaredField("advanced");
            advancedField.setAccessible(true);
            return advancedField.getBoolean(constant);
        }
        throw new AssertionError("Missing extracted constant: " + name);
    }

    private static String helpFor(List<?> constants, String name) throws Exception {
        for (Object constant : constants) {
            Field nameField = constant.getClass().getDeclaredField("name");
            nameField.setAccessible(true);
            if (!name.equals(nameField.get(constant))) {
                continue;
            }
            Method method = constant.getClass().getDeclaredMethod("helpText");
            method.setAccessible(true);
            return (String) method.invoke(constant);
        }
        throw new AssertionError("Missing extracted constant: " + name);
    }
}
