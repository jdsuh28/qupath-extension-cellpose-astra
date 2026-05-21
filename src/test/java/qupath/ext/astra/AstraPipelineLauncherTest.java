package qupath.ext.astra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused tests for the generic ASTRA pipeline launcher contract.
 */
class AstraPipelineLauncherTest {

    private static final Path LOCAL_BASE_ASTRA_ROOT = Path.of("..", "astra");
    private static final Path VENDORED_BASE_ASTRA_ROOT = Path.of("src", "main", "resources", "astra");

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
    void extractionIgnoresBootstrapConstantsBeforeUserEditSection() {
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants("""
                final File localRunnerFile =
                    new File("training/src/main/groovy/TrainingRunner.groovy")
                final boolean USE_LOCAL_CLASSES =
                    localRunnerFile.exists() && localRunnerFile.isFile()
                final ClassLoader loader = this.class.classLoader
                File __astraCellposeDefaultsLocalFile = new File("shared/src/main/groovy/CellposeInferenceDefaults.groovy")
                if (USE_LOCAL_CLASSES) {
                    loader.parseClass(__astraCellposeDefaultsLocalFile)
                }

                // -----------------------------------------------------------------------------
                // USER EDIT SECTION
                // -----------------------------------------------------------------------------

                final String NUC_MODEL_NAME_HELP = "Default nucleus model name."
                final String NUC_MODEL_NAME = "cpsam"
                final double NUC_CELLPROB = CellposeInferenceDefaults.CELLPROB_THRESHOLD

                // -----------------------------------------------------------------------------
                // CONFIG BUILD
                // -----------------------------------------------------------------------------

                final boolean USE_LOCAL_CLASSES = false
                final Map cfg = [:]
                """);

        List<String> names = constants.stream().map(AstraPipelineLauncher.EditableConstant::name).toList();
        assertEquals(List.of("NUC_MODEL_NAME", "NUC_CELLPROB"), names);
        assertFalse(names.contains("USE_LOCAL_CLASSES"));
        assertFalse(names.contains("localRunnerFile"));
    }

    @Test
    void realCurrentAstraScriptsExposeEditableConstantsAfterDefaultsBootstrap() throws Exception {
        Map<String, List<String>> requiredByScript = Map.of(
                "training/src/main/groovy/training.groovy",
                List.of("TRAIN_TARGET", "TRAINING_MODE", "NUC_MODEL_NAME", "CHANNELS_FOR_NUCLEUS"),
                "tuning/src/main/groovy/tuning.groovy",
                List.of("TUNE_TARGET", "SEARCH_MODE", "NUC_MODEL_NAME", "PARAM_DEFAULTS_BY_TARGET"),
                "validation/src/main/groovy/validation.groovy",
                List.of("VALIDATE_TARGET", "VALIDATION_MODE", "NUC_MODEL_NAME", "PARAM_DEFAULTS_BY_TARGET"),
                "analysis/src/main/groovy/vascular/vascular.groovy",
                List.of("NUC_MODEL_NAME", "NUC_CELLPROB", "CELL_CELLPROB", "RESULTS_FOLDER"),
                "analysis/src/main/groovy/colocalization/colocalization.groovy",
                List.of("DETECTION_TARGET", "NUC_MODEL_NAME", "NUC_CELLPROB", "COLOCALIZATION_CHECKS")
        );

        for (Map.Entry<String, List<String>> entry : requiredByScript.entrySet()) {
            String source = realBaseScript(entry.getKey());
            List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants(source);
            List<String> names = constants.stream().map(AstraPipelineLauncher.EditableConstant::name).toList();

            assertFalse(constants.isEmpty(), entry.getKey() + " must expose editable constants.");
            assertFalse(names.contains("MODEL_SOURCE"), entry.getKey() + " must not expose generic MODEL_SOURCE.");
            assertFalse(names.contains("MODEL_NAME"), entry.getKey() + " must not expose generic MODEL_NAME.");
            assertFalse(names.contains("MODEL_FILE"), entry.getKey() + " must not expose generic MODEL_FILE.");
            for (String required : entry.getValue()) {
                assertTrue(names.contains(required), entry.getKey() + " must expose " + required + ".");
            }
            assertFalse(names.contains("USE_LOCAL_CLASSES"), entry.getKey() + " must not expose bootstrap source mode.");
            assertFalse(names.stream().anyMatch(name -> name.startsWith("__")), entry.getKey() + " must not expose bootstrap internals.");
            assertTrue(source.indexOf("if (USE_LOCAL_CLASSES)") < source.indexOf("// USER EDIT SECTION"),
                    entry.getKey() + " must load CellposeInferenceDefaults before editable constants.");
            assertFalse(source.contains("loadClass(\"CellposeInferenceDefaults\")"), entry.getKey() + " must not probe loaded defaults.");
            assertFalse(source.contains("catch (ClassNotFoundException"), entry.getKey() + " must not reintroduce fallback-chain loading.");
        }
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
    void launcherDoesNotAutoRestoreOrSaveScientificConstantsThroughJavaPreferences() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/AstraPipelineLauncher.java"));

        assertFalse(source.contains("java.util.prefs.Preferences"));
        assertFalse(source.contains("applyPersistentSettings("));
        assertFalse(source.contains("savePersistentSettings("));
        assertFalse(source.contains("settingsNode("));
    }

    @Test
    void settingsProfilesRoundTripConstants(@TempDir Path tempDir) throws Exception {
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants("""
                final String MODE = "A"
                final String NUC_MODEL_NAME = "cpsam"
                final Map cfg = [:]
                """);
        String schema = AstraPipelineLauncher.schemaIdentity(constants);
        String sourceHash = AstraPipelineLauncher.sha256Hex("script");
        constants.get(0).setDisplayValue("\"B\"");
        File file = tempDir.resolve("profile.json").toFile();

        AstraPipelineLauncher.writeSettingsProfile(
                file,
                AstraPipelineLauncher.createSettingsProfile("Training", schema, sourceHash, constants)
        );

        List<AstraPipelineLauncher.EditableConstant> fresh = AstraPipelineLauncher.extractEditableConstants("""
                final String MODE = "A"
                final String NUC_MODEL_NAME = "cpsam"
                final Map cfg = [:]
                """);
        AstraPipelineLauncher.applySettingsProfile(
                AstraPipelineLauncher.readSettingsProfile(file),
                "Training",
                AstraPipelineLauncher.schemaIdentity(fresh),
                sourceHash,
                fresh
        );

        assertEquals("\"B\"", fresh.get(0).currentDisplayValue());
        assertTrue(Files.readString(file.toPath()).contains("\"model_references\""));
    }

    @Test
    void autosaveUsesProjectLocalSettingsFileAndRestoresMatchingState(@TempDir Path tempDir) throws Exception {
        File autosave = AstraPipelineLauncher.autosaveSettingsFile(tempDir.toFile(), "Training");
        assertEquals(tempDir.resolve("astra/settings/training/_autosave.json").normalize(), autosave.toPath().normalize());

        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants("""
                final String MODE = "A"
                final String NUC_MODEL_NAME = "cpsam"
                final Map cfg = [:]
                """);
        String schema = AstraPipelineLauncher.schemaIdentity(constants);
        String sourceHash = AstraPipelineLauncher.sha256Hex("script");
        constants.get(0).setDisplayValue("\"B\"");

        AstraPipelineLauncher.writeAutosaveSettings(autosave, "Training", schema, sourceHash, constants);
        assertTrue(autosave.isFile());

        List<AstraPipelineLauncher.EditableConstant> fresh = AstraPipelineLauncher.extractEditableConstants("""
                final String MODE = "A"
                final String NUC_MODEL_NAME = "cpsam"
                final Map cfg = [:]
                """);
        AstraPipelineLauncher.SettingsProfileState state = AstraPipelineLauncher.SettingsProfileState.scriptDefaults();
        List<String> info = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        assertTrue(AstraPipelineLauncher.restoreAutosaveSettings(
                autosave,
                "Training",
                AstraPipelineLauncher.schemaIdentity(fresh),
                sourceHash,
                fresh,
                state,
                info::add,
                warnings::add
        ));

        assertEquals("\"B\"", fresh.get(0).currentDisplayValue());
        assertTrue(state.summary().contains("autosaved settings"));
        assertTrue(info.stream().anyMatch(line -> line.contains("_autosave.json")));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void autosaveIgnoresSchemaAndSourceMismatches(@TempDir Path tempDir) throws Exception {
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants("""
                final List MODE_OPTIONS = ["A", "B"]
                final String MODE = "A"
                final Map cfg = [:]
                """);
        String schema = AstraPipelineLauncher.schemaIdentity(constants);
        String sourceHash = AstraPipelineLauncher.sha256Hex("script");
        constants.get(0).setDisplayValue("\"B\"");
        File autosave = AstraPipelineLauncher.autosaveSettingsFile(tempDir.toFile(), "Training");
        AstraPipelineLauncher.writeAutosaveSettings(autosave, "Training", schema, sourceHash, constants);

        List<AstraPipelineLauncher.EditableConstant> schemaChanged = AstraPipelineLauncher.extractEditableConstants("""
                final List MODE_OPTIONS = ["A", "B", "C"]
                final String MODE = "A"
                final Map cfg = [:]
                """);
        AstraPipelineLauncher.SettingsProfileState schemaState = AstraPipelineLauncher.SettingsProfileState.scriptDefaults();
        List<String> schemaWarnings = new ArrayList<>();
        assertFalse(AstraPipelineLauncher.restoreAutosaveSettings(
                autosave,
                "Training",
                AstraPipelineLauncher.schemaIdentity(schemaChanged),
                sourceHash,
                schemaChanged,
                schemaState,
                ignored -> {
                },
                schemaWarnings::add
        ));
        assertEquals("\"A\"", schemaChanged.get(0).currentDisplayValue());
        assertTrue(schemaWarnings.stream().anyMatch(line -> line.contains("schema")));

        List<AstraPipelineLauncher.EditableConstant> sourceChanged = AstraPipelineLauncher.extractEditableConstants("""
                final List MODE_OPTIONS = ["A", "B"]
                final String MODE = "A"
                final Map cfg = [:]
                """);
        List<String> sourceWarnings = new ArrayList<>();
        assertFalse(AstraPipelineLauncher.restoreAutosaveSettings(
                autosave,
                "Training",
                AstraPipelineLauncher.schemaIdentity(sourceChanged),
                AstraPipelineLauncher.sha256Hex("changed script"),
                sourceChanged,
                AstraPipelineLauncher.SettingsProfileState.scriptDefaults(),
                ignored -> {
                },
                sourceWarnings::add
        ));
        assertEquals("\"A\"", sourceChanged.get(0).currentDisplayValue());
        assertTrue(sourceWarnings.stream().anyMatch(line -> line.contains("source script hash")));
    }

    @Test
    void resetCanClearAutosaveDeterministically(@TempDir Path tempDir) throws Exception {
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants("""
                final String MODE = "A"
                final Map cfg = [:]
                """);
        File autosave = AstraPipelineLauncher.autosaveSettingsFile(tempDir.toFile(), "Training");
        AstraPipelineLauncher.writeAutosaveSettings(autosave, "Training", AstraPipelineLauncher.schemaIdentity(constants), "abc", constants);
        assertTrue(autosave.isFile());

        AstraPipelineLauncher.clearAutosaveSettings(autosave);

        assertFalse(autosave.exists());
    }

    @Test
    void manualGuiEditsUseProjectLocalAutosaveNotJavaPreferences() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/AstraPipelineLauncher.java"));

        assertTrue(source.contains("markManualEditAndSave"));
        assertTrue(source.contains("_autosave.json"));
        assertFalse(source.contains("java.util.prefs.Preferences"));
    }

    @Test
    void profileLoadReportsMissingImageChannelsWithoutChangingDefaults() {
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants("""
                final List CHANNELS_FOR_NUCLEUS = ["DAPI"]
                final List CHANNELS_FOR_CELL = ["FITC"]
                final Map cfg = [:]
                """);
        String schema = AstraPipelineLauncher.schemaIdentity(constants);
        AstraPipelineLauncher.SettingsProfile profile = AstraPipelineLauncher.createSettingsProfile("Training", schema, "abc", constants);

        List<String> missing = AstraPipelineLauncher.missingProfileChannels(profile, List.of("DAPI"));

        assertEquals(List.of("FITC"), missing);
        assertTrue(constants.get(0).currentDisplayValue().contains("DAPI"));
    }

    @Test
    void resetToScriptDefaultsClearsLoadedProfileState() {
        AstraPipelineLauncher.SettingsProfileState state = AstraPipelineLauncher.SettingsProfileState.scriptDefaults();
        state.loadedProfile("profile.json", "/tmp/profile.json", "1234567890abcdef");
        assertTrue(state.summary().contains("loaded settings profile"));

        state.resetToScriptDefaults();

        assertEquals("script defaults or manual GUI values", state.summary());
    }

    @Test
    void applyConstantsInjectsSettingsProvenanceForRunExports() {
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants("""
                final String NUC_MODEL_NAME = "cpsam"
                final Map cfg = [:]
                """);
        constants.get(0).setDisplayValue("\"nuc-special\"");
        AstraPipelineLauncher.SettingsProfileState state = AstraPipelineLauncher.SettingsProfileState.scriptDefaults();
        state.loadedAutosave("_autosave.json", "/project/astra/settings/training/_autosave.json", "1234567890abcdef");
        state.markManualEdit();

        String configured = AstraPipelineLauncher.applySettingsProvenanceConstants("""
                final String SETTINGS_SOURCE = "script defaults or manual GUI values"
                final String SETTINGS_PROFILE_NAME = ""
                final String SETTINGS_PROFILE_PATH = ""
                final String SETTINGS_PROFILE_SHA256 = ""
                final String CONFIGURED_CONSTANTS_SHA256 = ""
                final boolean MANUAL_EDIT_AFTER_PROFILE_LOAD = false
                final String NUC_MODEL_NAME = "nuc-special"
                final Map cfg = [:]
                """, constants, state);

        assertTrue(configured.contains("final String SETTINGS_SOURCE = \"autosaved settings\""));
        assertTrue(configured.contains("final String SETTINGS_PROFILE_NAME = \"_autosave.json\""));
        assertTrue(configured.contains("final String SETTINGS_PROFILE_PATH = \"/project/astra/settings/training/_autosave.json\""));
        assertTrue(configured.contains("final String SETTINGS_PROFILE_SHA256 = \"1234567890abcdef\""));
        assertTrue(configured.contains("final boolean MANUAL_EDIT_AFTER_PROFILE_LOAD = true"));
        assertTrue(configured.matches("(?s).*final String CONFIGURED_CONSTANTS_SHA256 = \"[0-9a-f]{64}\".*"));
    }

    @Test
    void applySettingsProvenanceFailsWhenOneRequiredConstantIsMissing() {
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants("""
                final String NUC_MODEL_NAME = "cpsam"
                final Map cfg = [:]
                """);

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                AstraPipelineLauncher.applySettingsProvenanceConstants("""
                        final String SETTINGS_SOURCE = ""
                        final String SETTINGS_PROFILE_NAME = ""
                        final String SETTINGS_PROFILE_PATH = ""
                        final String SETTINGS_PROFILE_SHA256 = ""
                        final String CONFIGURED_CONSTANTS_SHA256 = ""
                        final Map cfg = [:]
                        """, constants, AstraPipelineLauncher.SettingsProfileState.scriptDefaults()));

        assertTrue(error.getMessage().contains("MANUAL_EDIT_AFTER_PROFILE_LOAD"));
    }

    @Test
    void applySettingsProvenanceFailsWithAllMissingConstantsListed() {
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants("""
                final String NUC_MODEL_NAME = "cpsam"
                final Map cfg = [:]
                """);

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                AstraPipelineLauncher.applySettingsProvenanceConstants("""
                        final String SETTINGS_SOURCE = ""
                        final String SETTINGS_PROFILE_SHA256 = ""
                        final Map cfg = [:]
                        """, constants, AstraPipelineLauncher.SettingsProfileState.scriptDefaults()));

        assertTrue(error.getMessage().contains("SETTINGS_PROFILE_NAME"));
        assertTrue(error.getMessage().contains("SETTINGS_PROFILE_PATH"));
        assertTrue(error.getMessage().contains("CONFIGURED_CONSTANTS_SHA256"));
        assertTrue(error.getMessage().contains("MANUAL_EDIT_AFTER_PROFILE_LOAD"));
    }

    @Test
    void savedModelDiscoveryHandlesZeroOneMultipleAndMalformed(@TempDir Path tempDir) throws Exception {
        File projectBase = tempDir.toFile();

        assertEquals(List.of(), AstraPipelineLauncher.discoverSavedModelIds(projectBase, "nucleus").validIds());

        writeModelMetadata(projectBase, "nucleus", "nuc_a", "NUCLEUS");
        AstraPipelineLauncher.SavedModelDiscovery one = AstraPipelineLauncher.discoverSavedModelIds(projectBase, "nucleus");
        assertEquals(List.of("nuc_a"), one.validIds());
        assertTrue(one.invalidModels().isEmpty());

        writeModelMetadata(projectBase, "nucleus", "nuc_b", "NUCLEUS");
        AstraPipelineLauncher.SavedModelDiscovery multiple = AstraPipelineLauncher.discoverSavedModelIds(projectBase, "nucleus");
        assertEquals(List.of("nuc_a", "nuc_b"), multiple.validIds());

        Files.createDirectories(tempDir.resolve("astra/models/nucleus/bad"));
        AstraPipelineLauncher.SavedModelDiscovery malformed = AstraPipelineLauncher.discoverSavedModelIds(projectBase, "nucleus");
        assertTrue(malformed.invalidModels().containsKey("bad"));
    }

    @Test
    void savedModelDiscoveryDoesNotAutoselectWhenMultipleModelsExist(@TempDir Path tempDir) throws Exception {
        writeModelMetadata(tempDir.toFile(), "cell", "cell_a", "CELL");
        writeModelMetadata(tempDir.toFile(), "cell", "cell_b", "CELL");
        AstraPipelineLauncher.SavedModelDiscovery discovery = AstraPipelineLauncher.discoverSavedModelIds(tempDir.toFile(), "cell");
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants("""
                final String CELL_SAVED_MODEL_ID = ""
                final Map cfg = [:]
                """);

        assertEquals(List.of("cell_a", "cell_b"), discovery.validIds());
        assertFalse(Files.readString(Path.of("src/main/java/qupath/ext/astra/AstraPipelineLauncher.java")).contains("prefillSingleSavedModelId"));
        assertEquals("\"\"", constants.get(0).currentDisplayValue());
    }

    @Test
    void savedModelDiscoveryDoesNotAutoselectWhenExactlyOneModelExists(@TempDir Path tempDir) throws Exception {
        writeModelMetadata(tempDir.toFile(), "nucleus", "nuc_only", "NUCLEUS");
        AstraPipelineLauncher.SavedModelDiscovery discovery = AstraPipelineLauncher.discoverSavedModelIds(tempDir.toFile(), "nucleus");
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants("""
                final String NUC_SAVED_MODEL_ID = ""
                final Map cfg = [:]
                """);

        assertEquals(List.of("nuc_only"), discovery.validIds());
        assertFalse(Files.readString(Path.of("src/main/java/qupath/ext/astra/AstraPipelineLauncher.java")).contains("prefillSingleSavedModelId"));
        assertEquals("\"\"", constants.get(0).currentDisplayValue());
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

        assertTrue(configured.contains("final List CHANNELS_FOR_NUCLEUS = [\"DAPI\"]"));
        assertTrue(configured.contains("final List CHANNELS_FOR_CELL = [\"AF488\", \"AF555\", \"AF647\"]"));
        assertTrue(configured.contains("final List NUCLEUS_SEGMENTATION_CHANNELS = [\"DAPI\"]"));
        assertTrue(configured.contains("final List CELL_SEGMENTATION_CHANNELS = [\"AF488\", \"AF555\", \"AF647\"]"));
    }

    @Test
    void singleNonNuclearChannelDoesNotBecomeNucleusOrCellByPosition() {
        String script = """
                final List NUCLEUS_SEGMENTATION_CHANNELS = []
                final List CELL_SEGMENTATION_CHANNELS = []
                final Map cfg = [:]
                """;
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants(script);

        AstraPipelineLauncher.applyImageChannelDefaultNames(constants, List.of("Only"));
        String configured = AstraPipelineLauncher.applyConstants(script, constants);

        assertTrue(configured.contains("final List NUCLEUS_SEGMENTATION_CHANNELS = []"));
        assertTrue(configured.contains("final List CELL_SEGMENTATION_CHANNELS = []"));
    }

    @Test
    void openedChannelsWithoutDapiOrHoechstDoNotAutofillNucleus() {
        String script = """
                final List CHANNELS_FOR_NUCLEUS = ["DAPI"]
                final List CHANNELS_FOR_CELL = ["AF555", "AF488", "DAPI"]
                final Map cfg = [:]
                """;
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants(script);

        AstraPipelineLauncher.applyImageChannelDefaultNames(constants, List.of("Channel 1", "Channel 2"));
        String configured = AstraPipelineLauncher.applyConstants(script, constants);

        assertTrue(configured.contains("final List CHANNELS_FOR_NUCLEUS = []"));
        assertTrue(configured.contains("final List CHANNELS_FOR_CELL = []"));
        assertFalse(configured.contains("DAPI"));
        assertFalse(configured.contains("AF555"));
        assertFalse(configured.contains("AF488"));
    }

    @Test
    void singleNuclearChannelDoesNotAutofillCell() {
        String script = """
                final List CHANNELS_FOR_NUCLEUS = []
                final List CHANNELS_FOR_CELL = ["AF555"]
                final Map cfg = [:]
                """;
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants(script);

        AstraPipelineLauncher.applyImageChannelDefaultNames(constants, List.of("Hoechst"));
        String configured = AstraPipelineLauncher.applyConstants(script, constants);

        assertTrue(configured.contains("final List CHANNELS_FOR_NUCLEUS = [\"Hoechst\"]"));
        assertTrue(configured.contains("final List CHANNELS_FOR_CELL = []"));
        assertFalse(configured.contains("AF555"));
    }

    @Test
    void dapiAndGenericSecondChannelUsesGenericSecondForCell() {
        String script = """
                final List CHANNELS_FOR_NUCLEUS = []
                final List CHANNELS_FOR_CELL = []
                final List NUCLEUS_SEGMENTATION_CHANNELS = []
                final List CELL_SEGMENTATION_CHANNELS = []
                final Map cfg = [:]
                """;
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants(script);

        AstraPipelineLauncher.applyImageChannelDefaultNames(constants, List.of("DAPI", "Channel 2"));
        String configured = AstraPipelineLauncher.applyConstants(script, constants);

        assertTrue(configured.contains("final List CHANNELS_FOR_NUCLEUS = [\"DAPI\"]"));
        assertTrue(configured.contains("final List CHANNELS_FOR_CELL = [\"Channel 2\"]"));
        assertTrue(configured.contains("final List NUCLEUS_SEGMENTATION_CHANNELS = [\"DAPI\"]"));
        assertTrue(configured.contains("final List CELL_SEGMENTATION_CHANNELS = [\"Channel 2\"]"));
    }

    @Test
    void hoechstAndGenericSecondChannelUsesGenericSecondForCell() {
        String script = """
                final List CHANNELS_FOR_NUCLEUS = []
                final List CHANNELS_FOR_CELL = []
                final List NUCLEUS_SEGMENTATION_CHANNELS = []
                final List CELL_SEGMENTATION_CHANNELS = []
                final Map cfg = [:]
                """;
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants(script);

        AstraPipelineLauncher.applyImageChannelDefaultNames(constants, List.of("Hoechst", "Membrane"));
        String configured = AstraPipelineLauncher.applyConstants(script, constants);

        assertTrue(configured.contains("final List CHANNELS_FOR_NUCLEUS = [\"Hoechst\"]"));
        assertTrue(configured.contains("final List CHANNELS_FOR_CELL = [\"Membrane\"]"));
        assertTrue(configured.contains("final List NUCLEUS_SEGMENTATION_CHANNELS = [\"Hoechst\"]"));
        assertTrue(configured.contains("final List CELL_SEGMENTATION_CHANNELS = [\"Membrane\"]"));
    }

    @Test
    void genericChannelsWithoutNuclearMarkerStillDoNothing() {
        String script = """
                final List CHANNELS_FOR_NUCLEUS = ["DAPI"]
                final List CHANNELS_FOR_CELL = ["AF555"]
                final List NUCLEUS_SEGMENTATION_CHANNELS = ["DAPI"]
                final List CELL_SEGMENTATION_CHANNELS = ["AF555"]
                final Map cfg = [:]
                """;
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants(script);

        AstraPipelineLauncher.applyImageChannelDefaultNames(constants, List.of("Channel 1", "Channel 2"));
        String configured = AstraPipelineLauncher.applyConstants(script, constants);

        assertTrue(configured.contains("final List CHANNELS_FOR_NUCLEUS = []"));
        assertTrue(configured.contains("final List CHANNELS_FOR_CELL = []"));
        assertTrue(configured.contains("final List NUCLEUS_SEGMENTATION_CHANNELS = []"));
        assertTrue(configured.contains("final List CELL_SEGMENTATION_CHANNELS = []"));
    }

    @Test
    void twoOpenedChannelsOnlyPopulateColocalizationSegmentationChannels() {
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants(colocalizationDefaultsScript());

        AstraPipelineLauncher.applyImageChannelDefaultNames(constants, List.of("DAPI", "FITC"));
        String configured = AstraPipelineLauncher.applyConstants(colocalizationDefaultsScript(), constants);

        assertTrue(configured.contains("final List NUCLEUS_SEGMENTATION_CHANNELS = [\"DAPI\"]"));
        assertTrue(configured.contains("final List CELL_SEGMENTATION_CHANNELS = [\"FITC\"]"));
        assertFalse(configured.contains("LABEL      : \"DAPI_AND_FITC_nucleus\""));
        assertFalse(configured.contains("\"FITC|Nucleus\"     : 100.0d"));
        assertTrue(configured.contains("LABEL      : \"DAPI_AND_AF488_nucleus\""));
    }

    @Test
    void hoechstAndAf555DoNotGenerateColocalizationChecks() {
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants(colocalizationDefaultsScript());

        AstraPipelineLauncher.applyImageChannelDefaultNames(constants, List.of("Hoechst", "AF555"));
        String configured = AstraPipelineLauncher.applyConstants(colocalizationDefaultsScript(), constants);

        assertTrue(configured.contains("final List NUCLEUS_SEGMENTATION_CHANNELS = [\"Hoechst\"]"));
        assertTrue(configured.contains("final List CELL_SEGMENTATION_CHANNELS = [\"AF555\"]"));
        assertFalse(configured.contains("LABEL      : \"Hoechst_AND_AF555_nucleus\""));
        assertFalse(configured.contains("final List THRESHOLD_EXCLUDE_MARKERS = [\"Hoechst|Nucleus\"]"));
        assertFalse(configured.contains("\"AF555|Nucleus\"     : 100.0d"));
    }

    @Test
    void arbitraryOpenedChannelsDoNotRewriteScientificColocalizationDefaults() {
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants(colocalizationDefaultsScript());

        AstraPipelineLauncher.applyImageChannelDefaultNames(constants, List.of("Channel 1", "Channel 2"));
        String configured = AstraPipelineLauncher.applyConstants(colocalizationDefaultsScript(), constants);

        assertTrue(configured.contains("final List NUCLEUS_SEGMENTATION_CHANNELS = []"));
        assertTrue(configured.contains("final List CELL_SEGMENTATION_CHANNELS = []"));
        assertFalse(configured.contains("Channel_1"));
        assertFalse(configured.contains("Channel 1|Nucleus"));
        assertTrue(configured.contains("DAPI_AND_AF488"));
        assertTrue(configured.contains("\"AF488|Nucleus\""));
    }

    @Test
    void oneNonNuclearOpenedChannelDoesNotCreateNucleusCheck() {
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants(colocalizationDefaultsScript());

        AstraPipelineLauncher.applyImageChannelDefaultNames(constants, List.of("Only"));
        String configured = AstraPipelineLauncher.applyConstants(colocalizationDefaultsScript(), constants);

        assertTrue(configured.contains("final List NUCLEUS_SEGMENTATION_CHANNELS = []"));
        assertTrue(configured.contains("final List CELL_SEGMENTATION_CHANNELS = []"));
        assertFalse(configured.contains("Only|Nucleus"));
        assertTrue(configured.contains("AF488"));
    }

    @Test
    void colocalizationPanelOwnsDetectionTarget() {
        assertTrue(AstraPipelineLauncher.isHandledByColocalizationPanel("DETECTION_TARGET", true));
        assertFalse(AstraPipelineLauncher.isHandledByColocalizationPanel("MODEL_SOURCE", true));
        assertFalse(AstraPipelineLauncher.isHandledByColocalizationPanel("MODEL_NAME", true));
        assertFalse(AstraPipelineLauncher.isHandledByColocalizationPanel("MODEL_FILE", true));
        assertFalse(AstraPipelineLauncher.isHandledByColocalizationPanel("DETECTION_TARGET", false));
        assertFalse(AstraPipelineLauncher.isHandledByColocalizationPanel("MODEL_SOURCE", false));
    }

    @Test
    void launcherSourceUsesOneContentRailAndSharedRowHeight() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/AstraPipelineLauncher.java"));

        assertTrue(source.contains("private static final double CONTENT_HORIZONTAL_MARGIN = 24.0;"));
        assertTrue(source.contains("private static final double PARAMETER_ROW_HEIGHT = 34.0;"));
        assertTrue(source.contains("private static final double PARAMETER_ROW_GAP = 8.0;"));
        assertTrue(source.contains("header.setPadding(new Insets(22.0, CONTENT_HORIZONTAL_MARGIN, 20.0, CONTENT_HORIZONTAL_MARGIN));"));
        assertTrue(source.contains("body.setPadding(new Insets(0, 0, 18.0, 0));"));
        assertTrue(source.contains("workspace.setPadding(new Insets(0, CONTENT_HORIZONTAL_MARGIN, 18.0, CONTENT_HORIZONTAL_MARGIN));"));
        assertFalse(source.contains("BODY_HORIZONTAL_MARGIN"));
        assertFalse(source.contains("BODY_LEFT_MARGIN"));
        assertFalse(source.contains("BODY_RIGHT_MARGIN"));
    }

    @Test
    void launcherArchitectureUsesDeclaredSectionsAndNoDuplicatedSetupControls() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/AstraPipelineLauncher.java"));

        assertTrue(source.contains("VBox header = new VBox(12.0);"));
        assertTrue(source.contains("VBox body = new VBox(14.0);"));
        assertTrue(source.contains("VBox basic = sectionShell(\"Basic\""));
        assertTrue(source.contains("VBox advanced = sectionShell(\"Advanced\""));
        assertTrue(source.contains("Node feedbackNode = feedback.node();"));
        assertTrue(source.contains("if (colocalization) {\n            body.getChildren().add(createColocalizationPanel"));
        assertTrue(source.contains(".filter(c -> !isHandledByColocalizationPanel(c.name, colocalization))"));
        assertTrue(source.contains("private static VBox createColocalizationPanel("));
        assertTrue(source.contains("static boolean isHandledByColocalizationPanel(String name, boolean colocalization)"));
        assertFalse(source.contains("createVascularPanel("));
        assertFalse(source.contains("createTrainingPanel("));
        assertFalse(source.contains("createTuningPanel("));
        assertFalse(source.contains("createValidationPanel("));
    }

    @Test
    void launcherSourceUsesSharedLabeledRowsForConsistentVerticalSpacing() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/AstraPipelineLauncher.java"));

        assertTrue(source.contains("private static HBox labeledRow(String labelText, Node editor, double labelWidth)"));
        assertTrue(source.contains("row.setMinHeight(PARAMETER_ROW_HEIGHT);"));
        assertTrue(source.contains("grid.setVgap(PARAMETER_ROW_GAP);"));
        assertTrue(source.contains("VBox group = new VBox(PARAMETER_ROW_GAP);"));
        assertTrue(source.contains("private final VBox rows = new VBox(PARAMETER_ROW_GAP);"));
        assertTrue(source.contains("HBox row = labeledRow(displayLabel(constant.name), editor, 160.0);"));
        assertTrue(source.contains("HBox row = labeledRow(\"Detection target\", editor, 160.0);"));
        assertTrue(source.contains("private static final double SECTION_CONTENT_GAP = 9.0;"));
        assertTrue(source.contains("private static final double CARD_CONTENT_GAP = 8.0;"));
    }

    @Test
    void launcherUsesProfessionalLabelsForPipelineControls() {
        assertEquals("Nucleus Model Source", AstraPipelineLauncher.displayLabel("NUC_MODEL_SOURCE"));
        assertEquals("Nucleus Saved Model ID", AstraPipelineLauncher.displayLabel("NUC_SAVED_MODEL_ID"));
        assertEquals("Cell Saved Model ID", AstraPipelineLauncher.displayLabel("CELL_SAVED_MODEL_ID"));
        assertEquals("Threshold Scope", AstraPipelineLauncher.displayLabel("THRESHOLD_SCOPE"));
        assertEquals("Manual Background Offsets", AstraPipelineLauncher.displayLabel("BACKGROUND_SUBTRACTION_BY_CHANNEL"));
        assertEquals("Use GPU", AstraPipelineLauncher.displayLabel("USE_GPU"));
        assertEquals("QC Filename", AstraPipelineLauncher.displayLabel("QC_FILENAME"));
        assertEquals("Selected Image Names", AstraPipelineLauncher.displayLabel("SELECTED_IMAGE_NAMES"));
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

        assertEquals(List.of("NUC_MODEL_SOURCE", "NUC_MODEL_NAME", "NUC_MODEL_FILE", "NUC_SAVED_MODEL_ID"),
                AstraPipelineLauncher.targetModelControlNames("NUCLEUS"));
        assertEquals(List.of("CELL_MODEL_SOURCE", "CELL_MODEL_NAME", "CELL_MODEL_FILE", "CELL_SAVED_MODEL_ID"),
                AstraPipelineLauncher.targetModelControlNames("CELL"));
        assertEquals(List.of("NUC_MODEL_SOURCE", "NUC_MODEL_NAME", "NUC_MODEL_FILE", "NUC_SAVED_MODEL_ID",
                        "CELL_MODEL_SOURCE", "CELL_MODEL_NAME", "CELL_MODEL_FILE", "CELL_SAVED_MODEL_ID"),
                AstraPipelineLauncher.targetModelControlNames("BOTH"));
        assertFalse(AstraPipelineLauncher.targetModelControlNames("BOTH").contains("MODEL_SOURCE"));
        assertFalse(AstraPipelineLauncher.targetModelControlNames("BOTH").contains("MODEL_NAME"));
        assertFalse(AstraPipelineLauncher.targetModelControlNames("BOTH").contains("MODEL_FILE"));
        assertTrue(constants.stream().anyMatch(c -> c.name().equals("NUC_MODEL_SOURCE")));
        assertTrue(constants.stream().anyMatch(c -> c.name().equals("NUC_MODEL_NAME")));
        assertTrue(constants.stream().anyMatch(c -> c.name().equals("NUC_MODEL_FILE")));
        assertTrue(constants.stream().anyMatch(c -> c.name().equals("CELL_MODEL_SOURCE")));
        assertTrue(constants.stream().anyMatch(c -> c.name().equals("CELL_MODEL_NAME")));
        assertTrue(constants.stream().anyMatch(c -> c.name().equals("CELL_MODEL_FILE")));
    }

    @Test
    void colocalizationModelDefaultsAreTargetSpecificValues() {
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants(colocalizationModelScript("BOTH"));

        Map<String, String> values = new java.util.LinkedHashMap<>();
        constants.forEach(c -> values.put(c.name(), c.currentDisplayValue()));

        assertEquals("\"MODEL_NAME\"", values.get("NUC_MODEL_SOURCE"));
        assertEquals("\"cpsam\"", values.get("NUC_MODEL_NAME"));
        assertEquals("\"MODEL_NAME\"", values.get("CELL_MODEL_SOURCE"));
        assertEquals("\"cpsam\"", values.get("CELL_MODEL_NAME"));
    }

    @Test
    void colocalizationPanelStateFollowsDetectionTarget() {
        assertEquals(new AstraPipelineLauncher.ColocalizationPanelState(true, false, true, false),
                AstraPipelineLauncher.colocalizationPanelState("NUCLEUS"));
        assertEquals(new AstraPipelineLauncher.ColocalizationPanelState(false, true, false, true),
                AstraPipelineLauncher.colocalizationPanelState("CELL"));
        assertEquals(new AstraPipelineLauncher.ColocalizationPanelState(true, true, true, true),
                AstraPipelineLauncher.colocalizationPanelState("BOTH"));
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
    void staleThresholdExclusionsAreRemovedWhenChecksChange() {
        List<String> synchronizedKeys = AstraPipelineLauncher.synchronizedThresholdExclusions(
                List.of("DAPI|Nucleus", "FITC|Cell"),
                List.of(new AstraPipelineLauncher.ColocalizationCheck("fitc", "Nucleus", List.of("FITC")))
        );

        assertEquals(List.of("FITC|Nucleus"), AstraPipelineLauncher.markerKeysFromChecks(List.of(
                new AstraPipelineLauncher.ColocalizationCheck("fitc", "Nucleus", List.of("FITC"))
        )));
        assertEquals(List.of(), synchronizedKeys);
    }

    @Test
    void finalSummaryShowsEffectiveTargetSpecificModels() {
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants("""
                final List DETECTION_TARGET_OPTIONS = ["NUCLEUS", "CELL", "BOTH"]
                final String DETECTION_TARGET = "BOTH"
                final List NUC_MODEL_SOURCE_OPTIONS = ["MODEL_NAME", "SAVED", "FILE"]
                final String NUC_MODEL_SOURCE = "MODEL_NAME"
                final String NUC_MODEL_NAME = "nuc-special"
                final String NUC_MODEL_FILE = ""
                final String NUC_SAVED_MODEL_ID = ""
                final List CELL_MODEL_SOURCE_OPTIONS = ["MODEL_NAME", "SAVED", "FILE"]
                final String CELL_MODEL_SOURCE = "MODEL_NAME"
                final String CELL_MODEL_NAME = "cpsam"
                final String CELL_MODEL_FILE = ""
                final String CELL_SAVED_MODEL_ID = ""
                final Map cfg = [:]
                """);

        String summary = AstraPipelineLauncher.finalConfigSummary("Colocalization", "123456789012", constants);

        assertTrue(summary.contains("effective nucleus model source: MODEL_NAME"));
        assertTrue(summary.contains("effective nucleus model: nuc-special"));
        assertTrue(summary.contains("effective cell model source: MODEL_NAME"));
        assertTrue(summary.contains("effective cell model: cpsam"));
        assertFalse(summary.contains("(inherited)"));
    }

    @Test
    void finalSummaryShowsEffectiveSavedModelIds() {
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants("""
                final List DETECTION_TARGET_OPTIONS = ["NUCLEUS", "CELL", "BOTH"]
                final String DETECTION_TARGET = "NUCLEUS"
                final List NUC_MODEL_SOURCE_OPTIONS = ["MODEL_NAME", "SAVED", "FILE"]
                final String NUC_MODEL_SOURCE = "SAVED"
                final String NUC_MODEL_NAME = "cpsam"
                final String NUC_MODEL_FILE = ""
                final String NUC_SAVED_MODEL_ID = "nuc_v1"
                final Map cfg = [:]
                """);

        String summary = AstraPipelineLauncher.finalConfigSummary("Colocalization", "123456789012", constants);

        assertTrue(summary.contains("effective nucleus model source: SAVED"));
        assertTrue(summary.contains("effective nucleus model: nuc_v1"));
        assertTrue(summary.contains("effective cell model: not used for DETECTION_TARGET=NUCLEUS"));
    }

    @Test
    void finalSummaryUsesTrainingTargetForEffectiveModels() {
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants("""
                final List TRAIN_TARGET_OPTIONS = ["NUCLEUS", "CELL", "BOTH"]
                final String TRAIN_TARGET = "NUCLEUS"
                final List NUC_MODEL_SOURCE_OPTIONS = ["MODEL_NAME", "SAVED", "FILE"]
                final String NUC_MODEL_SOURCE = "MODEL_NAME"
                final String NUC_MODEL_NAME = "nuc-train"
                final String NUC_MODEL_FILE = ""
                final String NUC_SAVED_MODEL_ID = ""
                final List CELL_MODEL_SOURCE_OPTIONS = ["MODEL_NAME", "SAVED", "FILE"]
                final String CELL_MODEL_SOURCE = "MODEL_NAME"
                final String CELL_MODEL_NAME = "cell-train"
                final String CELL_MODEL_FILE = ""
                final String CELL_SAVED_MODEL_ID = ""
                final Map cfg = [:]
                """);

        String summary = AstraPipelineLauncher.finalConfigSummary("Training", "123456789012", constants);

        assertTrue(summary.contains("training target: \"NUCLEUS\""));
        assertTrue(summary.contains("effective nucleus model: nuc-train"));
        assertTrue(summary.contains("effective cell model: not used for TRAIN_TARGET=NUCLEUS"));
        assertFalse(summary.contains("not used for DETECTION_TARGET"));
    }

    @Test
    void finalSummaryUsesTuningAndValidationTargetsForEffectiveModels() {
        List<AstraPipelineLauncher.EditableConstant> tuning = AstraPipelineLauncher.extractEditableConstants("""
                final List TUNE_TARGET_OPTIONS = ["NUCLEUS", "CELL", "BOTH"]
                final String TUNE_TARGET = "CELL"
                final List NUC_MODEL_SOURCE_OPTIONS = ["MODEL_NAME", "SAVED", "FILE"]
                final String NUC_MODEL_SOURCE = "MODEL_NAME"
                final String NUC_MODEL_NAME = "nuc-tune"
                final String NUC_MODEL_FILE = ""
                final String NUC_SAVED_MODEL_ID = ""
                final List CELL_MODEL_SOURCE_OPTIONS = ["MODEL_NAME", "SAVED", "FILE"]
                final String CELL_MODEL_SOURCE = "MODEL_NAME"
                final String CELL_MODEL_NAME = "cell-tune"
                final String CELL_MODEL_FILE = ""
                final String CELL_SAVED_MODEL_ID = ""
                final Map cfg = [:]
                """);
        List<AstraPipelineLauncher.EditableConstant> validation = AstraPipelineLauncher.extractEditableConstants("""
                final List VALIDATE_TARGET_OPTIONS = ["NUCLEUS", "CELL", "BOTH"]
                final String VALIDATE_TARGET = "BOTH"
                final List NUC_MODEL_SOURCE_OPTIONS = ["MODEL_NAME", "SAVED", "FILE"]
                final String NUC_MODEL_SOURCE = "MODEL_NAME"
                final String NUC_MODEL_NAME = "nuc-val"
                final String NUC_MODEL_FILE = ""
                final String NUC_SAVED_MODEL_ID = ""
                final List CELL_MODEL_SOURCE_OPTIONS = ["MODEL_NAME", "SAVED", "FILE"]
                final String CELL_MODEL_SOURCE = "MODEL_NAME"
                final String CELL_MODEL_NAME = "cell-val"
                final String CELL_MODEL_FILE = ""
                final String CELL_SAVED_MODEL_ID = ""
                final Map cfg = [:]
                """);

        String tuningSummary = AstraPipelineLauncher.finalConfigSummary("Tuning", "123456789012", tuning);
        String validationSummary = AstraPipelineLauncher.finalConfigSummary("Validation", "123456789012", validation);

        assertTrue(tuningSummary.contains("effective nucleus model: not used for TUNE_TARGET=CELL"));
        assertTrue(tuningSummary.contains("effective cell model: cell-tune"));
        assertTrue(validationSummary.contains("effective nucleus model: nuc-val"));
        assertTrue(validationSummary.contains("effective cell model: cell-val"));
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
    void colocalizationCheckEditorDoesNotCreateHardcodedFallbackRow() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/AstraPipelineLauncher.java"));

        assertFalse(source.contains("new ColocalizationCheck(\"DAPI_AND_AF488_nucleus\""));
        assertFalse(source.contains("setRawConstant(constants, \"COLOCALIZATION_CHECKS\""));
        assertFalse(source.contains("setRawConstant(constants, \"MANUAL_INTENSITY_THRESHOLDS\""));
    }

    @Test
    void colocalizationCheckRowUsesReadableDeleteControl() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/AstraPipelineLauncher.java"));

        assertTrue(source.contains("Button remove = new Button(\"Delete check\")"));
        assertTrue(source.contains("new Tooltip(\"Delete check\")"));
        assertFalse(source.contains("new Button(\"🗑\")"));
        assertFalse(source.contains("new Button(\"...\")"));
        assertFalse(source.contains("setText(\"...\")"));
        assertTrue(source.contains("nestedField(\"Check name\", label)"));
        assertTrue(source.contains("nestedField(\"Compartment\", compartment)"));
        assertTrue(source.contains("nestedField(\"Channels\", channels)"));
        assertTrue(source.contains("compartment.setMinWidth(120.0)"));
        assertTrue(source.contains("compartment.setPrefWidth(130.0)"));
        assertTrue(source.contains("styleAstraComboBox(compartment)"));
        assertTrue(source.contains("channels.setAlignment(Pos.CENTER_LEFT)"));
        assertTrue(source.contains("private static String nestedLabelStyle()"));
        assertTrue(source.contains("private static String checkBoxStyle()"));
        assertTrue(source.contains("private static void styleCheckBox(CheckBox box)"));
    }

    @Test
    void comboBoxSelectedValueUsesReadableTextColor() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/AstraPipelineLauncher.java"));

        assertTrue(source.contains("private static void styleAstraComboBox(ComboBox<String> combo)"));
        assertTrue(source.contains("combo.getEditor().setStyle(EditableConstant.controlStyle())"));
        assertTrue(source.contains("private static void styleComboBoxSubnodes(ComboBox<String> combo)"));
        assertTrue(source.contains("combo.lookup(\".arrow-button\")"));
        assertTrue(source.contains("arrowButton.setStyle(\"-fx-background-color: transparent;"));
        assertTrue(source.contains("combo.setButtonCell(readableComboCell())"));
        assertTrue(source.contains("combo.setCellFactory(list -> readableComboCell())"));
        assertTrue(source.contains("-fx-text-fill: \" + INK"));
    }

    @Test
    void runFeedbackPaneResizesWithLauncherWidth() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/AstraPipelineLauncher.java"));

        assertTrue(source.contains("box.setMaxWidth(Double.MAX_VALUE);"));
        assertTrue(source.contains("HBox.setHgrow(box, Priority.ALWAYS);"));
        assertFalse(source.contains("HBox.setHgrow(box, Priority.NEVER);"));
        assertFalse(source.contains("box.setMaxWidth(520.0);"));
    }

    @Test
    void launcherOwnsOneModalErrorDialogPerGuiRun() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/AstraPipelineLauncher.java"));

        assertTrue(source.contains("private static final String GUI_RUN_ACTIVE_PROPERTY = \"ASTRA_GUI_RUN_ACTIVE\";"));
        assertTrue(source.contains("System.setProperty(GUI_RUN_ACTIVE_PROPERTY, \"true\");"));
        assertTrue(source.contains("restoreGuiRunActiveProperty(previousGuiRunActive);"));
        assertTrue(source.contains("private static void showRunFailureDialog(String scriptName, RunFeedback feedback, String message)"));
        assertTrue(source.contains("if (!feedback.markErrorDialogShown())"));
        assertTrue(source.contains("private final AtomicBoolean errorDialogShown = new AtomicBoolean(false);"));
        assertTrue(source.contains("errorDialogShown.set(false);"));
        assertTrue(source.contains("return errorDialogShown.compareAndSet(false, true);"));
        assertTrue(source.contains("See the ASTRA run log for full details."));
    }

    @Test
    void colocalizationThresholdAndBackgroundScopesAreInSetupPanel() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/AstraPipelineLauncher.java"));

        assertTrue(source.contains("addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get(\"THRESHOLD_SCOPE\")"));
        assertTrue(source.contains("addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get(\"BACKGROUND_SCOPE\")"));
        assertTrue(source.contains("addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get(\"THRESHOLD_PROVENANCE_BY_MARKER\")"));
        assertTrue(source.contains("thresholdRows.put(\"THRESHOLD_EXCLUDE_MARKERS\""));
        assertTrue(source.contains("installColocalizationThresholdVisibility(byName, thresholdRows, thresholdPanel)"));
        assertTrue(source.contains("static Set<String> colocalizationThresholdVisibilityState("));
        assertTrue(source.contains("List.of(\"THRESHOLD_MODE\", \"BACKGROUND_MODE\")"));
        assertFalse(source.contains("List.of(\"THRESHOLD_MODE\", \"THRESHOLD_SCOPE\", \"BACKGROUND_MODE\", \"BACKGROUND_SCOPE\")"));
        assertTrue(source.contains("panel.requestLayout()"));
        assertTrue(source.contains("row.editor.setDisable(!visible)"));
    }

    @Test
    void colocalizationThresholdVisibilityDefaultProjectStateIsCompact() {
        Set<String> visible = AstraPipelineLauncher.colocalizationThresholdVisibilityState("LOG_GAUSSIAN_MIXTURE", "PROJECT", "NONE", "PROJECT");

        assertEquals(Set.of("THRESHOLD_MODE", "THRESHOLD_SCOPE", "THRESHOLD_EXCLUDE_MARKERS", "BACKGROUND_MODE"), visible);
    }

    @Test
    void colocalizationThresholdScopeTogglesDoNotChangeVisibility() {
        Set<String> project = AstraPipelineLauncher.colocalizationThresholdVisibilityState("LOG_GAUSSIAN_MIXTURE", "PROJECT", "NONE", "PROJECT");
        Set<String> image = AstraPipelineLauncher.colocalizationThresholdVisibilityState("LOG_GAUSSIAN_MIXTURE", "IMAGE", "NONE", "PROJECT");
        Set<String> region = AstraPipelineLauncher.colocalizationThresholdVisibilityState("LOG_GAUSSIAN_MIXTURE", "REGION", "NONE", "PROJECT");

        assertEquals(project, image);
        assertEquals(project, region);
    }

    @Test
    void colocalizationThresholdVisibilityIsModeDriven() {
        assertTrue(AstraPipelineLauncher.colocalizationThresholdVisibilityState("MANUAL", "PROJECT", "NONE", "PROJECT")
                .contains("MANUAL_INTENSITY_THRESHOLDS"));
        assertTrue(AstraPipelineLauncher.colocalizationThresholdVisibilityState("MANUAL", "PROJECT", "NONE", "PROJECT")
                .contains("THRESHOLD_PROVENANCE_BY_MARKER"));
        assertTrue(AstraPipelineLauncher.colocalizationThresholdVisibilityState("RANGE_PERCENT", "PROJECT", "NONE", "PROJECT")
                .contains("RANGE_THRESHOLD_FRACTION_BY_MARKER"));
        assertTrue(AstraPipelineLauncher.colocalizationThresholdVisibilityState("LOG_GAUSSIAN_MIXTURE", "PROJECT", "MANUAL_OFFSET", "PROJECT")
                .contains("BACKGROUND_SUBTRACTION_BY_CHANNEL"));

        Set<String> local = AstraPipelineLauncher.colocalizationThresholdVisibilityState("LOG_GAUSSIAN_MIXTURE", "PROJECT", "LOCAL_ROI_PERCENTILE", "REGION");
        assertTrue(local.contains("BACKGROUND_SCOPE"));
        assertTrue(local.contains("LOCAL_BACKGROUND_PERCENTILE"));
    }

    @Test
    void launcherDoesNotPreserveRemovedAnalysisAliases() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/AstraPipelineLauncher.java"));

        assertFalse(source.contains("RESET_BASELINE"));
        assertFalse(source.contains("EXPORT_ON_QUANTIFY"));
    }

    @Test
    void colocalizationMarkerKeyMapSettingsUseSpecializedEditors() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/AstraPipelineLauncher.java"));

        assertTrue(source.contains("static final class MarkerKeyMapEditor extends VBox"));
        assertTrue(source.contains("installMarkerKeyMapEditor(byName.get(\"MANUAL_INTENSITY_THRESHOLDS\"), MarkerMapValueType.NUMERIC"));
        assertTrue(source.contains("installMarkerKeyMapEditor(byName.get(\"RANGE_THRESHOLD_FRACTION_BY_MARKER\"), MarkerMapValueType.NUMERIC"));
        assertTrue(source.contains("installMarkerKeyMapEditor(byName.get(\"THRESHOLD_PROVENANCE_BY_MARKER\"), MarkerMapValueType.TEXT"));
        assertTrue(source.contains("installMarkerKeyMapEditor(byName.get(\"BACKGROUND_SUBTRACTION_BY_CHANNEL\"), MarkerMapValueType.NUMERIC"));
        assertTrue(source.contains("editor instanceof MarkerKeyMapEditor"));
        assertTrue(source.contains("labeledVariableBlock(label, editor)"));
        assertTrue(source.contains("checksEditor.addChangeListener(() -> {\n            refreshMarkerKeyEditors.run();"));
    }

    @Test
    void markerKeyMapRenderingProducesGroovyMapValues() {
        Map<String, String> numeric = new java.util.LinkedHashMap<>();
        numeric.put("AF647|Nucleus", "12.5");
        numeric.put("DAPI|Nucleus", "");
        String renderedNumeric = AstraPipelineLauncher.renderMarkerKeyMapValues(numeric, AstraPipelineLauncher.MarkerMapValueType.NUMERIC);

        assertTrue(renderedNumeric.contains("\"AF647|Nucleus\": 12.5d"));
        assertFalse(renderedNumeric.contains("DAPI|Nucleus"));

        Map<String, String> text = new java.util.LinkedHashMap<>();
        text.put("AF647|Nucleus", "manual \"publication\" threshold");
        String renderedText = AstraPipelineLauncher.renderMarkerKeyMapValues(text, AstraPipelineLauncher.MarkerMapValueType.TEXT);

        assertTrue(renderedText.contains("\"AF647|Nucleus\": \"manual \\\"publication\\\" threshold\""));
    }

    @Test
    void markerKeyMapParsingReadsExistingGroovyMaps() {
        Map<String, String> numeric = AstraPipelineLauncher.parseMarkerKeyMapValues("""
                [
                    "AF488|Nucleus": 100.0d,
                    "AF647|Cell": 2.5
                ]
                """, AstraPipelineLauncher.MarkerMapValueType.NUMERIC);
        Map<String, String> text = AstraPipelineLauncher.parseMarkerKeyMapValues("""
                [
                    "AF488|Nucleus": "manual source"
                ]
                """, AstraPipelineLauncher.MarkerMapValueType.TEXT);

        assertEquals("100.0", numeric.get("AF488|Nucleus"));
        assertEquals("2.5", numeric.get("AF647|Cell"));
        assertEquals("manual source", text.get("AF488|Nucleus"));
    }

    @Test
    void selectedImageNamesUsesScalableProjectPicker() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/AstraPipelineLauncher.java"));

        assertTrue(source.contains("private static final class SelectedImageNamesEditor extends VBox"));
        assertTrue(source.contains("ListView<String> list"));
        assertTrue(source.contains("filter.setPromptText(\"Filter project image names\")"));
        assertTrue(source.contains("list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE)"));
        assertTrue(source.contains("smallButton(\"Select filtered\")"));
        assertTrue(source.contains("smallButton(\"Clear\")"));
        assertTrue(source.contains("smallButton(\"Invert filtered\")"));
        assertTrue(source.contains("smallButton(\"Paste names\")"));
        assertTrue(source.contains("renderStringList(selectedNames())"));
    }

    @Test
    void vascularMarkerDefaultsDoNotUseUnrelatedFirstChannel() {
        String script = """
                final String CHANNEL_DAPI = "DAPI"
                final String CHANNEL_WGA = "AF488"
                final String CHANNEL_ASMA = "AF555"
                final String CHANNEL_CD31 = "AF647"
                final Map cfg = [:]
                """;
        List<AstraPipelineLauncher.EditableConstant> constants = AstraPipelineLauncher.extractEditableConstants(script);

        AstraPipelineLauncher.applyImageChannelDefaultNames(constants, List.of("Channel 1", "Channel 2"));
        String configured = AstraPipelineLauncher.applyConstants(script, constants);

        assertTrue(configured.contains("final String CHANNEL_DAPI = \"\""));
        assertTrue(configured.contains("final String CHANNEL_WGA = \"\""));
        assertTrue(configured.contains("final String CHANNEL_ASMA = \"\""));
        assertTrue(configured.contains("final String CHANNEL_CD31 = \"\""));
        assertFalse(configured.contains("Channel 1"));
        assertFalse(configured.contains("AF488"));
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
    void guiLogFormatterRemovesLauncherClutterWithoutChangingCellposeLines() {
        String formatted = AstraPipelineLauncher.formatGuiLogText("""
                [LOG] COLOCALIZATION COLOCALIZATION [PREFLIGHT] Runtime Python synchronized.
                ERROR COLOCALIZATION [QUANTIFY] Non-finite AF647|Nucleus measurements detected.
                cellpose: 34 tile images processed
                """);

        assertTrue(formatted.contains("Preflight: Runtime Python synchronized."));
        assertTrue(formatted.contains("ERROR: Quantify: Non-finite AF647|Nucleus measurements detected."));
        assertTrue(formatted.contains("cellpose: 34 tile images processed"));
        assertFalse(formatted.contains("[LOG]"));
        assertFalse(formatted.contains("COLOCALIZATION COLOCALIZATION"));
    }

    @Test
    void launcherSourceAvoidsGuiLogAndErrDoublePrefixes() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/AstraPipelineLauncher.java"));

        assertTrue(source.contains("static String formatGuiLogText(String text)"));
        assertTrue(source.contains("feedback.append(formatGuiLogText(text));"));
        assertFalse(source.contains("append(\"[LOG] \" + text)"));
        assertFalse(source.contains("feedback.append(\"[ERR] \" + text)"));
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

    private static String realBaseScript(String relativePath) throws Exception {
        Path path = currentBaseAstraRoot(relativePath).resolve(relativePath).normalize();
        assertTrue(Files.isRegularFile(path), "Missing current ASTRA script fixture: " + path);
        return Files.readString(path);
    }

    private static Path currentBaseAstraRoot(String relativePath) {
        Path localPath = LOCAL_BASE_ASTRA_ROOT.resolve(relativePath).normalize();
        if (Files.isRegularFile(localPath)) {
            return LOCAL_BASE_ASTRA_ROOT;
        }
        Path vendoredPath = VENDORED_BASE_ASTRA_ROOT.resolve(relativePath).normalize();
        if (Files.isRegularFile(vendoredPath)) {
            return VENDORED_BASE_ASTRA_ROOT;
        }
        throw new AssertionError("Missing current ASTRA script fixture. Checked local source "
                + localPath + " and vendored release resource " + vendoredPath + ".");
    }

    private static String colocalizationModelScript(String target) {
        return """
                final List DETECTION_TARGET_OPTIONS = ["NUCLEUS", "CELL", "BOTH"]
                final String DETECTION_TARGET = "%s"
                final List NUC_MODEL_SOURCE_OPTIONS = ["MODEL_NAME", "SAVED", "FILE"]
                final String NUC_MODEL_SOURCE = "MODEL_NAME"
                final String NUC_MODEL_NAME = "cpsam"
                final String NUC_MODEL_FILE = ""
                final String NUC_SAVED_MODEL_ID = ""
                final List CELL_MODEL_SOURCE_OPTIONS = ["MODEL_NAME", "SAVED", "FILE"]
                final String CELL_MODEL_SOURCE = "MODEL_NAME"
                final String CELL_MODEL_NAME = "cpsam"
                final String CELL_MODEL_FILE = ""
                final String CELL_SAVED_MODEL_ID = ""
                final List COLOCALIZATION_CHECKS = []
                final Map cfg = [:]
                """.formatted(target);
    }

    private static void writeModelMetadata(File projectBase, String targetFolder, String modelId, String target) throws Exception {
        Path modelDir = projectBase.toPath().resolve("astra/models").resolve(targetFolder).resolve(modelId);
        Files.createDirectories(modelDir);
        Files.writeString(modelDir.resolve("model_metadata.json"), """
                {
                  "schema_version": "1",
                  "model_id": "%s",
                  "target": "%s",
                  "model_file": "model.cpm",
                  "model_sha256": "abc"
                }
                """.formatted(modelId, target));
    }

    private static String colocalizationDefaultsScript() {
        return """
                final String DETECTION_TARGET = "NUCLEUS"
                final List NUCLEUS_SEGMENTATION_CHANNELS = ["DAPI"]
                final List CELL_SEGMENTATION_CHANNELS = []
                final List COLOCALIZATION_CHECKS = [
                    [
                        LABEL      : "DAPI_AND_AF488_nucleus",
                        COMPARTMENT: "Nucleus",
                        CHANNELS   : ["DAPI", "AF488"]
                    ]
                ]
                final List THRESHOLD_EXCLUDE_MARKERS = ["DAPI|Nucleus"]
                final Map MANUAL_INTENSITY_THRESHOLDS = [
                    "AF488|Nucleus": 100.0d
                ]
                final Map RANGE_THRESHOLD_FRACTION_BY_MARKER = [
                    "AF488|Nucleus": 0.50d
                ]
                final Map THRESHOLD_PROVENANCE_BY_MARKER = [
                    "AF488|Nucleus": "EDIT: describe threshold source before publication use"
                ]
                final String BACKGROUND_MODE = "NONE"
                final Map BACKGROUND_SUBTRACTION_BY_CHANNEL = [:]
                final Map cfg = [:]
                """;
    }
}
