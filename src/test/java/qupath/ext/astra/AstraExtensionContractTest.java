package qupath.ext.astra;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
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
        assertEquals("cellpose-runtime", AstraRuntimeInstaller.runtimeDirectory().getName());
        assertTrue(AstraRuntimeInstaller.runtimePythonExecutable(new File("runtime")).getPath().contains("runtime"));
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
                final List MODEL_SOURCE_OPTIONS = ["MODEL_NAME", "SAVED", "FILE"]
                final String MODEL_SOURCE = "MODEL_NAME"
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
        assertTrue(isAdvanced(constants, "NUC_DIAMETER_UM"));
        assertTrue(isAdvanced(constants, "LOG_VIEWER_PROGRESS_FAILURES"));
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
                final List MODEL_SOURCE_OPTIONS = ["MODEL_NAME", "SAVED", "FILE"]
                final String MODEL_SOURCE = "MODEL_NAME"
                final List CUSTOM_MODE_OPTIONS = ["ALPHA", "BETA"]
                final String CUSTOM_MODE = "ALPHA"
                final Map cfg = [:]
                """;

        List<?> constants = AstraPipelineLauncher.extractEditableConstants(script);

        assertEquals(2, constants.size());
        assertFalse(hasConstant(constants, "MODEL_SOURCE_OPTIONS"));
        assertEquals(List.of("MODEL_NAME", "SAVED", "FILE"), optionsFor(constants, "MODEL_SOURCE"));
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
