package qupath.ext.astra;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused tests for the generic ASTRA pipeline launcher contract.
 */
class AstraPipelineLauncherTest {

    @Test
    void helpConstantsAttachToTargetVariables() {
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants("""
                final String DETECTION_TARGET_HELP = "Choose the segmentation target."
                final List DETECTION_TARGET_OPTIONS = ["NUCLEUS", "CELL", "BOTH"]
                final String DETECTION_TARGET = "NUCLEUS"
                final Map cfg = [:]
                """);

        assertEquals(1, constants.size());
        assertEquals("DETECTION_TARGET", constants.get(0).name());
        assertEquals("Choose the segmentation target.", constants.get(0).helpText());
    }

    @Test
    void launcherDoesNotHardcodeScientificTooltipDefinitions() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/AstraPipelineLauncher.java"));

        assertFalse(source.contains("private static String helpFor"));
        assertFalse(source.contains("CELLPOSE_CELL_CHANNELS\" ->"));
        assertTrue(source.contains("ASTRA did not provide help metadata for this script constant."));
    }

    @Test
    void schemaIdentityIsStableAndChangesWithEditableContract() {
        String script = """
                final List MODE_OPTIONS = ["A", "B"]
                final String MODE_HELP = "Mode help."
                final String MODE = "A"
                final Map cfg = [:]
                """;
        String changed = script.replace("[\"A\", \"B\"]", "[\"A\", \"B\", \"C\"]");

        String first = AstraPipelineLauncher.schemaIdentity(AstraPipelineLauncher.extractEditableConstants(script));
        String second = AstraPipelineLauncher.schemaIdentity(AstraPipelineLauncher.extractEditableConstants(script));
        String third = AstraPipelineLauncher.schemaIdentity(AstraPipelineLauncher.extractEditableConstants(changed));

        assertEquals(first, second);
        assertNotEquals(first, third);
    }

    @Test
    void oldSettingsWithoutSchemaIdAreCleared() {
        String scriptName = "launcher-test-old-settings";
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants("""
                final String MODE = "A"
                final Map cfg = [:]
                """);
        Preferences node = AstraPipelineLauncher.settingsNode(scriptName);
        node.put("MODE", "\"B\"");

        AstraPipelineLauncher.PersistentApplyResult result =
                AstraPipelineLauncher.applyPersistentSettings(scriptName, AstraPipelineLauncher.schemaIdentity(constants), constants);

        assertFalse(result.restored());
        assertTrue(result.schemaReset());
        assertEquals(null, node.get("MODE", null));
    }

    @Test
    void mismatchedSchemaSettingsAreCleared() {
        String scriptName = "launcher-test-schema-mismatch";
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants("""
                final String MODE = "A"
                final Map cfg = [:]
                """);
        Preferences node = AstraPipelineLauncher.settingsNode(scriptName);
        node.put("__schema_id", "stale");
        node.put("MODE", "\"B\"");

        AstraPipelineLauncher.PersistentApplyResult result =
                AstraPipelineLauncher.applyPersistentSettings(scriptName, AstraPipelineLauncher.schemaIdentity(constants), constants);

        assertFalse(result.restored());
        assertTrue(result.schemaReset());
        assertEquals(null, node.get("MODE", null));
    }

    @Test
    void imageChannelDefaultsUseTargetSegmentationListsWithoutFillingGenericCellLists() {
        String script = """
                final List CHANNELS_FOR_NUCLEUS = ["DAPI"]
                final List CHANNELS_FOR_CELL = ["AF555"]
                final List NUCLEUS_SEGMENTATION_CHANNELS = []
                final List CELL_SEGMENTATION_CHANNELS = []
                final Map cfg = [:]
                """;
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants(script);

        AstraPipelineLauncher.applyImageChannelDefaultNames(constants, List.of("DAPI", "AF488", "AF555", "AF647"));
        String configured = AstraPipelineLauncher.applyConstants(script, constants);

        assertTrue(configured.contains("final List CHANNELS_FOR_CELL = [\"AF555\"]"));
        assertTrue(configured.contains("final List NUCLEUS_SEGMENTATION_CHANNELS = [\"DAPI\"]"));
        assertTrue(configured.contains("final List CELL_SEGMENTATION_CHANNELS = [\"AF488\"]"));
    }

    @Test
    void cellSegmentationDefaultsBlankWithoutSecondChannel() {
        String script = """
                final List NUCLEUS_SEGMENTATION_CHANNELS = []
                final List CELL_SEGMENTATION_CHANNELS = []
                final Map cfg = [:]
                """;
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants(script);

        AstraPipelineLauncher.applyImageChannelDefaultNames(constants, List.of("Only"));
        String configured = AstraPipelineLauncher.applyConstants(script, constants);

        assertTrue(configured.contains("final List NUCLEUS_SEGMENTATION_CHANNELS = [\"Only\"]"));
        assertTrue(configured.contains("final List CELL_SEGMENTATION_CHANNELS = []"));
    }

    @Test
    void detectionTargetAndThresholdExclusionsAreEditable() {
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants("""
                final List DETECTION_TARGET_OPTIONS = ["NUCLEUS", "CELL", "BOTH"]
                final String DETECTION_TARGET = "NUCLEUS"
                final List THRESHOLD_EXCLUDE_MARKERS = ["DAPI|Nucleus"]
                final Map cfg = [:]
                """);

        assertTrue(constants.stream().anyMatch(c -> c.name().equals("DETECTION_TARGET")));
        assertTrue(constants.stream().anyMatch(c -> c.name().equals("THRESHOLD_EXCLUDE_MARKERS")));
    }

    @Test
    void targetSpecificModelControlsRemainAvailableForBothTarget() {
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants(colocalizationModelScript("BOTH"));

        assertEquals(List.of("NUC_MODEL_SOURCE", "NUC_MODEL_NAME", "NUC_MODEL_FILE"),
                AstraPipelineLauncher.targetModelControlNames("NUCLEUS"));
        assertEquals(List.of("CELL_MODEL_SOURCE", "CELL_MODEL_NAME", "CELL_MODEL_FILE"),
                AstraPipelineLauncher.targetModelControlNames("CELL"));
        assertEquals(List.of("NUC_MODEL_SOURCE", "NUC_MODEL_NAME", "NUC_MODEL_FILE",
                        "CELL_MODEL_SOURCE", "CELL_MODEL_NAME", "CELL_MODEL_FILE"),
                AstraPipelineLauncher.targetModelControlNames("BOTH"));
        assertTrue(constants.stream().anyMatch(c -> c.name().equals("NUC_MODEL_SOURCE")));
        assertTrue(constants.stream().anyMatch(c -> c.name().equals("NUC_MODEL_NAME")));
        assertTrue(constants.stream().anyMatch(c -> c.name().equals("NUC_MODEL_FILE")));
        assertTrue(constants.stream().anyMatch(c -> c.name().equals("CELL_MODEL_SOURCE")));
        assertTrue(constants.stream().anyMatch(c -> c.name().equals("CELL_MODEL_NAME")));
        assertTrue(constants.stream().anyMatch(c -> c.name().equals("CELL_MODEL_FILE")));
    }

    @Test
    void markerExclusionKeysComeOnlyFromColocalizationChecks() {
        List<AstraPipelineLauncher.ColocalizationCheck> checks = List.of(
                new AstraPipelineLauncher.ColocalizationCheck("DAPI_AF488", "Nucleus", List.of("DAPI", "AF488"))
        );

        List<String> keys = AstraPipelineLauncher.markerKeysFromChecks(checks);

        assertTrue(keys.contains("DAPI|Nucleus"));
        assertTrue(keys.contains("AF488|Nucleus"));
        assertFalse(keys.contains("AF647|Nucleus"));
        assertEquals("[\"DAPI|Nucleus\"]", AstraPipelineLauncher.renderStringList(List.of("DAPI|Nucleus")));
    }

    @Test
    void colocalizationCheckBuilderRendersGroovyMapList() {
        String rendered = AstraPipelineLauncher.renderColocalizationChecks(List.of(
                new AstraPipelineLauncher.ColocalizationCheck("A_B_nucleus", "Nucleus", List.of("A", "B")),
                new AstraPipelineLauncher.ColocalizationCheck("C_cell", "Cell", List.of("C"))
        ));

        assertTrue(rendered.contains("LABEL      : \"A_B_nucleus\""));
        assertTrue(rendered.contains("COMPARTMENT: \"Cell\""));
        assertTrue(rendered.contains("CHANNELS   : [\"A\", \"B\"]"));
    }

    @Test
    void allImagesScopeRequiresConfirmationPath() {
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants("""
                final List IMAGE_SCOPE_OPTIONS = ["ALL_IMAGES", "CURRENT_IMAGE"]
                final String IMAGE_SCOPE = "ALL_IMAGES"
                final Map cfg = [:]
                """);

        assertTrue(AstraPipelineLauncher.requiresAllImagesConfirmation(constants));
    }

    @Test
    void provisionalVascularModesRequireOneConfirmation() {
        assertFalse(AstraPipelineLauncher.requiresProvisionalVascularConfirmation(extractModes("[\"GENERATE_REGIONS\", \"DETECT_CELLS\"]")));
        assertTrue(AstraPipelineLauncher.requiresProvisionalVascularConfirmation(extractModes("[\"AUTO_BUILD_CLASSIFIERS\"]")));
        assertTrue(AstraPipelineLauncher.requiresProvisionalVascularConfirmation(extractModes("[\"AUTO_SELECT_ROIS\"]")));
        assertTrue(AstraPipelineLauncher.requiresProvisionalVascularConfirmation(extractModes("[\"AUTO_BUILD_CLASSIFIERS\", \"AUTO_SELECT_ROIS\"]")));
    }

    @Test
    void runLogCaptureForwardsLogTextAndStopsAfterClose() {
        List<String> lines = new ArrayList<>();
        // QuPath's root LogManager depends on the full QuPath runtime/logback stack.
        // Unit tests exercise the run-scoped bridge deterministically; live QuPath
        // uses RunLogCapture.attach(...) to register the same bridge with LogManager.
        AstraPipelineLauncher.RunLogCapture capture = AstraPipelineLauncher.RunLogCapture.forTest(lines::add);
        capture.appendText("INFO  astra test info\n");
        capture.appendText("WARN  astra test warn\n");
        capture.appendText("ERROR astra test error\n");
        capture.close();
        capture.appendText("INFO ignored\n");

        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("INFO"));
        assertTrue(lines.get(1).contains("WARN"));
        assertTrue(lines.get(2).contains("ERROR"));
    }

    @Test
    void cancellationMessageDocumentsNativeProcessLimitationAndAvoidsSuccess() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/AstraPipelineLauncher.java"));

        assertTrue(source.contains("Cancellation requested. Java/Groovy task interruption was requested."));
        assertTrue(source.contains("Native Cellpose process may continue until the current operation exits."));
        assertTrue(source.contains("status.setText(\"Run cancelled.\")"));
        assertFalse(source.contains("Cancellation requested.") && source.contains("[DONE] Cancellation"));
    }

    @Test
    void logCaptureTextDocumentsCellposeSubprocessRoute() throws Exception {
        String launcher = Files.readString(Path.of("src/main/java/qupath/ext/astra/AstraPipelineLauncher.java"));
        String runner = Files.readString(Path.of("src/main/java/qupath/ext/biop/cmd/VirtualEnvironmentRunner.java"));

        assertTrue(launcher.contains("Cellpose subprocess stdout/stderr is captured when it is emitted through QuPath logging."));
        assertTrue(runner.contains("redirectErrorStream(true)"));
        assertTrue(runner.contains("logger.info(\"{}: {}\", name, line);"));
    }

    private static List<AstraPipelineLauncher.EditableConstant> extractModes(String modes) {
        return AstraPipelineLauncher.extractEditableConstants("""
                final List MODES_TO_RUN = %s
                final Map cfg = [:]
                """.formatted(modes));
    }

    private static String colocalizationModelScript(String target) {
        return """
                final List DETECTION_TARGET_OPTIONS = ["NUCLEUS", "CELL", "BOTH"]
                final String DETECTION_TARGET = "%s"
                final List MODEL_SOURCE_OPTIONS = ["MODEL_NAME", "SAVED", "FILE"]
                final String MODEL_SOURCE = "MODEL_NAME"
                final String MODEL_NAME = "cpsam"
                final String MODEL_FILE = ""
                final List NUC_MODEL_SOURCE_OPTIONS = ["", "MODEL_NAME", "SAVED", "FILE"]
                final String NUC_MODEL_SOURCE = ""
                final String NUC_MODEL_NAME = ""
                final String NUC_MODEL_FILE = ""
                final List CELL_MODEL_SOURCE_OPTIONS = ["", "MODEL_NAME", "SAVED", "FILE"]
                final String CELL_MODEL_SOURCE = ""
                final String CELL_MODEL_NAME = ""
                final String CELL_MODEL_FILE = ""
                final List COLOCALIZATION_CHECKS = []
                final Map cfg = [:]
                """.formatted(target);
    }
}
