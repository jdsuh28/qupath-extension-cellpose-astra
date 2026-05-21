package qupath.ext.astra;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for ASTRA extension registration and archive hygiene.
 */
class AstraExtensionContractTest {

    private static final File ROOT = new File(".").getAbsoluteFile();

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

        assertEquals(List.of("Training", "Tuning", "Validation", "Analysis>Vascular", "Analysis>Colocalization", "Analysis>Generate Regions"),
                new ArrayList<>(scripts.keySet()));
        assertEquals("astra/training/src/main/groovy/training.groovy", scripts.get("Training"));
        assertEquals("astra/tuning/src/main/groovy/tuning.groovy", scripts.get("Tuning"));
        assertEquals("astra/validation/src/main/groovy/validation.groovy", scripts.get("Validation"));
        assertEquals("astra/analysis/src/main/groovy/vascular/vascular.groovy", scripts.get("Analysis>Vascular"));
        assertEquals("astra/analysis/src/main/groovy/colocalization/colocalization.groovy", scripts.get("Analysis>Colocalization"));
        assertEquals("astra/tools/src/main/groovy/generateRegions.groovy", scripts.get("Analysis>Generate Regions"));

        scripts.values().forEach(path -> assertTrue(path.startsWith("astra/"), path));
        scripts.values().forEach(path -> assertFalse(path.contains("Cellpose_"), path));
    }

    /**
     * Verifies the ASTRA runtime installer uses the pinned public fork and a
     * deterministic user-local runtime path.
     */
    @Test
    void runtimeInstallerUsesDeterministicAstraRuntime() {
        assertEquals("v4.0.8+astra.2", AstraRuntimeInstaller.DEFAULT_CELLPOSE_REF);
        assertEquals("git+https://github.com/jdsuh28/cellpose-astra.git@v4.0.8+astra.2",
                AstraRuntimeInstaller.cellposePackageSpec());
        assertEquals("cellpose-astra", AstraRuntimeInstaller.runtimeDirectory().getName());
        assertTrue(AstraRuntimeInstaller.runtimePythonExecutable(new File("runtime")).getPath().contains("runtime"));
    }

    /**
     * Verifies the default installer builds a conda-prefix runtime rather than
     * silently creating a venv.
     */
    @Test
    void runtimeInstallerBuildsCondaPrefixCommand() {
        List<String> command = AstraRuntimeInstaller.condaCreateCommand("conda", new File("/tmp/cellpose-astra"));

        assertEquals(List.of("conda", "create", "-y", "-p", "/tmp/cellpose-astra", "python=3.10"), command);
    }

    /**
     * Verifies runtime validation checks Python, NumPy, torch, Cellpose, the
     * ASTRA fork marker, and Cellpose startup before reporting success.
     */
    @Test
    void runtimeInstallerValidationCommandsCoverRequiredImports() {
        List<List<String>> commands = AstraRuntimeInstaller.validationCommands(new File("/runtime/bin/python"));
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
        String message = AstraRuntimeInstaller.formatCommandFailure(
                List.of("python", "-c", "import cellpose"),
                new AstraRuntimeInstaller.CommandResult(7, "line1\nline2\nline3")
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

        assertTrue(AstraRuntimeInstaller.terminateProcessForCancellation(process, Duration.ofMillis(1)));
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
        String source = Files.readString(new File(ROOT, "src/main/java/qupath/ext/astra/AstraRuntimeInstaller.java").toPath());
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
        String source = Files.readString(new File(ROOT, "src/main/java/qupath/ext/astra/AstraRuntimeInstaller.java").toPath());

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
    void cellposeRunnerSyncsAstraRuntimePreferenceDirectly() throws Exception {
        String source = Files.readString(new File(ROOT, "src/main/java/qupath/ext/astra/AstraCellpose2D.java").toPath());

        assertTrue(source.contains("ASTRA_RUNTIME_PYTHON_PATH_KEY = \"astraRuntimePythonPath\""));
        assertTrue(source.contains("syncAstraRuntimePythonPreference()"));
        assertTrue(source.contains("PathPrefs.createPersistentPreference(ASTRA_RUNTIME_PYTHON_PATH_KEY, \"\")"));
        assertTrue(source.contains("pythonExecutable.isAbsolute()"));
        assertTrue(source.contains("pythonExecutable.isFile()"));
        assertTrue(source.contains("cellposeSetup.setCellposePythonPath(absolutePath);"));
        assertTrue(source.contains("CellposeSetup may not have been synchronized yet"));
        assertTrue(source.contains("ASTRA/Cellpose > ASTRA runtime Python executable"));
        assertFalse(source.contains("cellposeSAMPythonPath"));
    }

    /**
     * Verifies ASTRA-owned Cellpose detection cannot fall through to BIOP's
     * legacy runtime preference selection.
     *
     * @throws Exception if source cannot be read.
     */
    @Test
    void astraCellposeOverridesBiopRuntimeSelection() throws Exception {
        String base = Files.readString(new File(ROOT, "src/main/java/qupath/ext/biop/cellpose/Cellpose2D.java").toPath());
        String astra = Files.readString(new File(ROOT, "src/main/java/qupath/ext/astra/AstraCellpose2D.java").toPath());

        assertTrue(base.contains("protected VirtualEnvironmentRunner getVirtualEnvironmentRunner()"));
        assertTrue(astra.contains("protected VirtualEnvironmentRunner getVirtualEnvironmentRunner()"));
        assertTrue(astra.contains("return createRuntimeRunner();"));
        assertTrue(astra.contains("VirtualEnvironmentRunner.EnvType.EXE"));
        assertFalse(astra.contains("EnvType.CONDA"));
        assertFalse(astra.contains("\"CALL\""));
        assertFalse(astra.contains("\"activate\""));
    }

    /**
     * Verifies ASTRA-owned default folders are rooted under projectRoot/astra
     * when callers do not provide explicit directories.
     */
    @Test
    void astraDefaultDirectoriesResolveUnderAstraRoot() {
        File project = new File("/tmp/astra-extension-project");

        assertEquals(new File(project, "astra"), AstraCellpose2D.resolveAstraRootDirectory(project));
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
    void astraTrainingUsesThreeFolderRuntimeContract() throws Exception {
        File root = new File("/tmp/astra-training-root");
        String runtime = Files.readString(new File(ROOT, "src/main/java/qupath/ext/astra/AstraCellpose2D.java").toPath());
        String builder = Files.readString(new File(ROOT, "src/main/java/qupath/ext/astra/AstraCellposeBuilder.java").toPath());

        assertEquals(new File(root, "train"), AstraCellpose2D.resolveTrainingDirectory(root));
        assertEquals(new File(root, "models"), AstraCellpose2D.trainingArtifactReturnValue(root));
        assertTrue(runtime.contains("cellposeArguments.add(\"--astra_model_save_root\")"));
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
        String astra = Files.readString(new File(ROOT, "src/main/java/qupath/ext/astra/AstraCellpose2D.java").toPath());

        assertTrue(base.contains("requireSuccessfulProcessExit(veRunner, \"Cellpose process\")"));
        assertTrue(base.contains("throw e;"));
        assertTrue(base.contains("exited with value"));
        assertFalse(base.contains("catch (IOException | InterruptedException e) {\n            logger.error(\"Failed to Run Cellpose\", e);\n            return;"));
        assertTrue(base.contains("throw new IllegalStateException(\"Cellpose detection failed before masks could be read.\", e);"));
        assertTrue(base.contains("Thread.currentThread().interrupt();"));
        assertTrue(base.contains("pool.submit(runnable).get();"));
        assertTrue(base.contains("throw new IllegalStateException(\"Cellpose detection was interrupted.\", e);"));
        assertTrue(astra.contains("requireSuccessfulProcessExit(veRunner, \"Cellpose process\")"));
        assertTrue(astra.contains("exited with value"));
        assertTrue(astra.contains("Process log:"));
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

        List<?> constants = AstraPipelineLauncher.extractEditableConstants(script);

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
        String source = Files.readString(new File(ROOT, "src/main/java/qupath/ext/astra/AstraPipelineLauncher.java").toPath());

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

        List<?> constants = AstraPipelineLauncher.extractEditableConstants(script);

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

        List<?> constants = AstraPipelineLauncher.extractEditableConstants(script);

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

        String firstSchema = AstraPipelineLauncher.schemaIdentity(AstraPipelineLauncher.extractEditableConstants(first));
        String secondSchema = AstraPipelineLauncher.schemaIdentity(AstraPipelineLauncher.extractEditableConstants(second));

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

        String configured = AstraPipelineLauncher.applyConstants(script, AstraPipelineLauncher.extractEditableConstants(script));

        assertTrue(configured.contains("\"ROI\"\nfinal boolean USE_WHOLE_IMAGE_IF_NO_REGION"));
        assertFalse(configured.contains("\"ROI\"final boolean"));
    }

    /**
     * Verifies the extension archive is quarantined away from active source and
     * resource roots.
     */
    @Test
    void archiveRootDoesNotLeakIntoActiveSources() {
        assertFalse(new File(ROOT, "_legacy").exists());
        assertFalse(new File(ROOT, "_broken").exists());
        assertFalse(new File(ROOT, "_original").exists());
        assertTrue(new File(ROOT, "_archive/README.md").isFile());

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
