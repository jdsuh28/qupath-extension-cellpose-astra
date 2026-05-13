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
    void imageChannelDefaultsDoNotFillCellSegmentationLists() {
        String script = """
                final List CHANNELS_FOR_NUCLEUS = ["DAPI"]
                final List CHANNELS_FOR_CELL = ["AF555"]
                final List CELLPOSE_CELL_CHANNELS = ["AF488"]
                final Map cfg = [:]
                """;
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants(script);

        AstraPipelineLauncher.applyImageChannelDefaultNames(constants, List.of("DAPI", "AF488", "AF555", "AF647"));
        String configured = AstraPipelineLauncher.applyConstants(script, constants);

        assertTrue(configured.contains("final List CHANNELS_FOR_CELL = [\"AF555\"]"));
        assertTrue(configured.contains("final List CELLPOSE_CELL_CHANNELS = [\"AF488\"]"));
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

    private static List<AstraPipelineLauncher.EditableConstant> extractModes(String modes) {
        return AstraPipelineLauncher.extractEditableConstants("""
                final List MODES_TO_RUN = %s
                final Map cfg = [:]
                """.formatted(modes));
    }
}
