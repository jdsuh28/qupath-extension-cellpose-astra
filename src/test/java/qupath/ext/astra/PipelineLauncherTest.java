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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Focused tests for the generic ASTRA pipeline launcher contract.
 */
class PipelineLauncherTest {

    private static final Path LOCAL_BASE_ASTRA_ROOT = Path.of("..", "astra");
    private static final Path VENDORED_BASE_ASTRA_ROOT = Path.of("src", "main", "resources", "astra");

    @Test
    void helpConstantsAttachToTargetVariables() {
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants("""
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
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants("""
                final File localRunnerFile =
                    new File("modules/pipelines/cellpose/training/src/main/groovy/TrainingRunner.groovy")
                final boolean USE_LOCAL_CLASSES =
                    localRunnerFile.exists() && localRunnerFile.isFile()
                final ClassLoader loader = this.class.classLoader
                File __cellposeDefaultsLocalFile = new File("modules/shared/core/src/main/groovy/CellposeInferenceDefaults.groovy")
                if (USE_LOCAL_CLASSES) {
                    loader.parseClass(__cellposeDefaultsLocalFile)
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

        List<String> names = constants.stream().map(PipelineLauncher.EditableConstant::name).toList();
        assertEquals(List.of("NUC_MODEL_NAME", "NUC_CELLPROB"), names);
        assertFalse(names.contains("USE_LOCAL_CLASSES"));
        assertFalse(names.contains("localRunnerFile"));
    }

    @Test
    void realCurrentScriptsExposeEditableConstantsAfterDefaultsBootstrap() throws Exception {
        Map<String, List<String>> requiredByScript = Map.of(
                "modules/pipelines/cellpose/training/src/main/groovy/training.groovy",
                List.of("TRAIN_TARGET", "TRAINING_MODE", "NUC_MODEL_NAME", "CHANNELS_FOR_NUCLEUS"),
                "modules/pipelines/cellpose/tuning/src/main/groovy/tuning.groovy",
                List.of("TUNE_TARGET", "SEARCH_MODE", "NUC_MODEL_NAME", "PARAM_DEFAULTS_BY_TARGET"),
                "modules/pipelines/cellpose/validation/src/main/groovy/validation.groovy",
                List.of("VALIDATE_TARGET", "VALIDATION_MODE", "NUC_MODEL_NAME", "PARAM_DEFAULTS_BY_TARGET"),
                "modules/pipelines/analysis/vascular/src/main/groovy/vascular.groovy",
                List.of("NUC_MODEL_NAME", "NUC_CELLPROB", "CELL_CELLPROB", "RESULTS_FOLDER"),
                "modules/pipelines/analysis/colocalization/src/main/groovy/colocalization.groovy",
                List.of("DETECTION_TARGET", "NUC_MODEL_NAME", "NUC_CELLPROB", "COLOCALIZATION_CHECKS"),
                "modules/tools/sma-af647-oneshot/src/main/groovy/smaAf647Oneshot.groovy",
                List.of("CLASS_ANALYSIS_REGION", "NUC_MODEL_NAME", "NUC_CELLPROB", "EXPORT_TECHNICAL_CSV")
        );

        for (Map.Entry<String, List<String>> entry : requiredByScript.entrySet()) {
            String source = realBaseScript(entry.getKey());
            List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.editableConstantsForScript(entry.getKey(), source);
            List<String> names = constants.stream().map(PipelineLauncher.EditableConstant::name).toList();

            assertFalse(constants.isEmpty(), entry.getKey() + " must expose editable constants.");
            assertFalse(names.contains("MODEL_SOURCE"), entry.getKey() + " must not expose generic MODEL_SOURCE.");
            assertFalse(names.contains("MODEL_NAME"), entry.getKey() + " must not expose generic MODEL_NAME.");
            assertFalse(names.contains("MODEL_FILE"), entry.getKey() + " must not expose generic MODEL_FILE.");
            for (String required : entry.getValue()) {
                assertTrue(names.contains(required), entry.getKey() + " must expose " + required + ".");
            }
            assertFalse(names.contains("USE_LOCAL_CLASSES"), entry.getKey() + " must not expose bootstrap source mode.");
            assertFalse(names.stream().anyMatch(name -> name.startsWith("__")), entry.getKey() + " must not expose bootstrap internals.");
            assertTrue(source.contains("final Map USER_OVERRIDES"), entry.getKey() + " must be a compact override entrypoint.");
            assertTrue(source.contains("PipelineEntrypoint.groovy"), entry.getKey() + " must route through the shared manifest entrypoint.");
            assertFalse(source.contains("catch (ClassNotFoundException"), entry.getKey() + " must not reintroduce fallback-chain loading.");
        }
    }

    @Test
    void launcherDoesNotHardcodeScientificTooltipDefinitions() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertFalse(source.contains("private static String helpFor"));
        assertFalse(source.contains("CELLPOSE_CELL_CHANNELS\" ->"));
        assertTrue(source.contains("ASTRA did not provide help metadata for this script constant."));
    }

    @Test
    void launcherReadsManifestPresentationMetadata() throws Exception {
        String source = realBaseScript("modules/pipelines/cellpose/validation/src/main/groovy/validation.groovy");
        List<PipelineLauncher.EditableConstant> constants =
                PipelineLauncher.editableConstantsForScript("Validation", source);

        PipelineLauncher.EditableConstant preset = constants.stream()
                .filter(constant -> constant.name().equals("SEGMENTATION_PRESET"))
                .findFirst()
                .orElseThrow();

        assertTrue(preset.uiOrder() < Integer.MAX_VALUE);
        assertFalse(preset.helpText().isBlank());
        assertFalse(preset.detailsText().isBlank());
        assertNotEquals(preset.helpText(), preset.detailsText());
    }

    @Test
    void launcherUsesStandardGroupOrderAndUiOrder() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertTrue(source.contains("STANDARD_GROUP_ORDER = List.of("));
        assertTrue(source.contains("\"Run Setup\""));
        assertTrue(source.contains("\"Images & Scope\""));
        assertTrue(source.contains("\"Developer Overrides\""));
        assertTrue(source.contains("STANDARD_GROUP_RANK.getOrDefault(group, Integer.MAX_VALUE)"));
        assertTrue(source.contains("Comparator.comparingInt(EditableConstant::uiOrder)"));
    }

    @Test
    void launcherSeparatesHoverHelpFromDetailedHelpDialog() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertTrue(source.contains("new Tooltip(constant.helpText())"));
        assertTrue(source.contains("info.setOnAction(event -> showParameterHelpDialog(constant))"));
        assertTrue(source.contains("TextArea details = new TextArea(constant.detailsText())"));
        assertTrue(source.contains("addHelpSummaryRow(summary, 0, \"Parameter\", constant.name)"));
        assertTrue(source.contains("addHelpSummaryRow(summary, 1, \"Current value\", safeCurrentDisplayValue(constant))"));
        assertTrue(source.contains("addHelpSummaryRow(summary, 2, \"Default value\", constant.defaultDisplayValue())"));
    }

    @Test
    void launcherDefinesCardDashboardAndSharedStylesheet() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));
        String css = Files.readString(Path.of("src/main/resources/qupath/ext/astra/astra-launcher.css"));

        assertTrue(source.contains("LAUNCHER_STYLESHEET_RESOURCE = \"/qupath/ext/astra/astra-launcher.css\""));
        assertTrue(source.contains("installAstraStyles(dialog.getDialogPane())"));
        assertTrue(source.contains("createSettingsCard(section"));
        assertTrue(source.contains("detachFromParent(section.content())"));
        assertTrue(css.contains(".astra-button-primary"));
        assertTrue(css.contains(".astra-button-help:pressed"));
        assertTrue(css.contains(".astra-settings-card:pressed"));
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

        String first = PipelineLauncher.schemaIdentity(PipelineLauncher.extractEditableConstants(script));
        String second = PipelineLauncher.schemaIdentity(PipelineLauncher.extractEditableConstants(script));
        String third = PipelineLauncher.schemaIdentity(PipelineLauncher.extractEditableConstants(changed));

        assertEquals(first, second);
        assertNotEquals(first, third);
    }

    @Test
    void launcherDoesNotAutoRestoreOrSaveScientificConstantsThroughJavaPreferences() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertFalse(source.contains("java.util.prefs.Preferences"));
        assertFalse(source.contains("applyPersistentSettings("));
        assertFalse(source.contains("savePersistentSettings("));
        assertFalse(source.contains("settingsNode("));
    }

    @Test
    void settingsProfilesRoundTripConstants(@TempDir Path tempDir) throws Exception {
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants("""
                final String MODE = "A"
                final String NUC_MODEL_NAME = "cpsam"
                final Map cfg = [:]
                """);
        String schema = PipelineLauncher.schemaIdentity(constants);
        String sourceHash = PipelineLauncher.sha256Hex("script");
        constants.get(0).setDisplayValue("\"B\"");
        File file = tempDir.resolve("profile.json").toFile();

        PipelineLauncher.writeSettingsProfile(
                file,
                PipelineLauncher.createSettingsProfile("Training", schema, sourceHash, constants)
        );

        List<PipelineLauncher.EditableConstant> fresh = PipelineLauncher.extractEditableConstants("""
                final String MODE = "A"
                final String NUC_MODEL_NAME = "cpsam"
                final Map cfg = [:]
                """);
        PipelineLauncher.applySettingsProfile(
                PipelineLauncher.readSettingsProfile(file),
                "Training",
                PipelineLauncher.schemaIdentity(fresh),
                sourceHash,
                fresh
        );

        assertEquals("\"B\"", fresh.get(0).currentDisplayValue());
        assertTrue(Files.readString(file.toPath()).contains("\"model_references\""));
    }

    @Test
    void autosaveUsesProjectLocalSettingsFileAndRestoresMatchingState(@TempDir Path tempDir) throws Exception {
        File autosave = PipelineLauncher.autosaveSettingsFile(tempDir.toFile(), "Training");
        assertEquals(tempDir.resolve("astra/settings/training/_autosave.json").normalize(), autosave.toPath().normalize());

        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants("""
                final String MODE = "A"
                final String NUC_MODEL_NAME = "cpsam"
                final Map cfg = [:]
                """);
        String schema = PipelineLauncher.schemaIdentity(constants);
        String sourceHash = PipelineLauncher.sha256Hex("script");
        constants.get(0).setDisplayValue("\"B\"");

        PipelineLauncher.writeAutosaveSettings(autosave, "Training", schema, sourceHash, constants);
        assertTrue(autosave.isFile());

        List<PipelineLauncher.EditableConstant> fresh = PipelineLauncher.extractEditableConstants("""
                final String MODE = "A"
                final String NUC_MODEL_NAME = "cpsam"
                final Map cfg = [:]
                """);
        PipelineLauncher.SettingsProfileState state = PipelineLauncher.SettingsProfileState.scriptDefaults();
        List<String> info = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        assertTrue(PipelineLauncher.restoreAutosaveSettings(
                autosave,
                "Training",
                PipelineLauncher.schemaIdentity(fresh),
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
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants("""
                final List MODE_OPTIONS = ["A", "B"]
                final String MODE = "A"
                final Map cfg = [:]
                """);
        String schema = PipelineLauncher.schemaIdentity(constants);
        String sourceHash = PipelineLauncher.sha256Hex("script");
        constants.get(0).setDisplayValue("\"B\"");
        File autosave = PipelineLauncher.autosaveSettingsFile(tempDir.toFile(), "Training");
        PipelineLauncher.writeAutosaveSettings(autosave, "Training", schema, sourceHash, constants);

        List<PipelineLauncher.EditableConstant> schemaChanged = PipelineLauncher.extractEditableConstants("""
                final List MODE_OPTIONS = ["A", "B", "C"]
                final String MODE = "A"
                final Map cfg = [:]
                """);
        PipelineLauncher.SettingsProfileState schemaState = PipelineLauncher.SettingsProfileState.scriptDefaults();
        List<String> schemaWarnings = new ArrayList<>();
        assertFalse(PipelineLauncher.restoreAutosaveSettings(
                autosave,
                "Training",
                PipelineLauncher.schemaIdentity(schemaChanged),
                sourceHash,
                schemaChanged,
                schemaState,
                ignored -> {
                },
                schemaWarnings::add
        ));
        assertEquals("\"A\"", schemaChanged.get(0).currentDisplayValue());
        assertTrue(schemaWarnings.stream().anyMatch(line -> line.contains("schema")));

        List<PipelineLauncher.EditableConstant> sourceChanged = PipelineLauncher.extractEditableConstants("""
                final List MODE_OPTIONS = ["A", "B"]
                final String MODE = "A"
                final Map cfg = [:]
                """);
        List<String> sourceWarnings = new ArrayList<>();
        assertFalse(PipelineLauncher.restoreAutosaveSettings(
                autosave,
                "Training",
                PipelineLauncher.schemaIdentity(sourceChanged),
                PipelineLauncher.sha256Hex("changed script"),
                sourceChanged,
                PipelineLauncher.SettingsProfileState.scriptDefaults(),
                ignored -> {
                },
                sourceWarnings::add
        ));
        assertEquals("\"A\"", sourceChanged.get(0).currentDisplayValue());
        assertTrue(sourceWarnings.stream().anyMatch(line -> line.contains("source script hash")));
    }

    @Test
    void resetCanClearAutosaveDeterministically(@TempDir Path tempDir) throws Exception {
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants("""
                final String MODE = "A"
                final Map cfg = [:]
                """);
        File autosave = PipelineLauncher.autosaveSettingsFile(tempDir.toFile(), "Training");
        PipelineLauncher.writeAutosaveSettings(autosave, "Training", PipelineLauncher.schemaIdentity(constants), "abc", constants);
        assertTrue(autosave.isFile());

        PipelineLauncher.clearAutosaveSettings(autosave);

        assertFalse(autosave.exists());
    }

    @Test
    void manualGuiEditsUseProjectLocalAutosaveNotJavaPreferences() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertTrue(source.contains("markManualEditAndSave"));
        assertTrue(source.contains("_autosave.json"));
        assertFalse(source.contains("java.util.prefs.Preferences"));
    }

    @Test
    void autosaveDebouncesAndIgnoresTransientBlankFields() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertTrue(source.contains("private Timeline pendingSave;"));
        assertTrue(source.contains("scheduleSaveCurrent();"));
        assertTrue(source.contains("Duration.millis(350.0)"));
        assertTrue(source.contains("isTransientAutosaveState(e)"));
        assertTrue(source.contains("message.contains(\" must not be blank.\")"));
        assertTrue(source.contains("catch (RuntimeException e)"));
    }

    @Test
    void profileLoadReportsMissingImageChannelsWithoutChangingDefaults() {
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants("""
                final List CHANNELS_FOR_NUCLEUS = ["DAPI"]
                final List CHANNELS_FOR_CELL = ["FITC"]
                final Map cfg = [:]
                """);
        String schema = PipelineLauncher.schemaIdentity(constants);
        PipelineLauncher.SettingsProfile profile = PipelineLauncher.createSettingsProfile("Training", schema, "abc", constants);

        List<String> missing = PipelineLauncher.missingProfileChannels(profile, List.of("DAPI"));

        assertEquals(List.of("FITC"), missing);
        assertTrue(constants.get(0).currentDisplayValue().contains("DAPI"));
    }

    @Test
    void resetToScriptDefaultsClearsLoadedProfileState() {
        PipelineLauncher.SettingsProfileState state = PipelineLauncher.SettingsProfileState.scriptDefaults();
        state.loadedProfile("profile.json", "/tmp/profile.json", "1234567890abcdef");
        assertTrue(state.summary().contains("loaded settings profile"));

        state.resetToScriptDefaults();

        assertEquals("script defaults or manual GUI values", state.summary());
    }

    @Test
    void applyConstantsInjectsSettingsProvenanceForRunExports() {
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants("""
                final String NUC_MODEL_NAME = "cpsam"
                final Map cfg = [:]
                """);
        constants.get(0).setDisplayValue("\"nuc-special\"");
        PipelineLauncher.SettingsProfileState state = PipelineLauncher.SettingsProfileState.scriptDefaults();
        state.loadedAutosave("_autosave.json", "/project/astra/settings/training/_autosave.json", "1234567890abcdef");
        state.markManualEdit();

        String configured = PipelineLauncher.applySettingsProvenanceConstants("""
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
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants("""
                final String NUC_MODEL_NAME = "cpsam"
                final Map cfg = [:]
                """);

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                PipelineLauncher.applySettingsProvenanceConstants("""
                        final String SETTINGS_SOURCE = ""
                        final String SETTINGS_PROFILE_NAME = ""
                        final String SETTINGS_PROFILE_PATH = ""
                        final String SETTINGS_PROFILE_SHA256 = ""
                        final String CONFIGURED_CONSTANTS_SHA256 = ""
                        final Map cfg = [:]
                        """, constants, PipelineLauncher.SettingsProfileState.scriptDefaults()));

        assertTrue(error.getMessage().contains("MANUAL_EDIT_AFTER_PROFILE_LOAD"));
    }

    @Test
    void applySettingsProvenanceFailsWithAllMissingConstantsListed() {
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants("""
                final String NUC_MODEL_NAME = "cpsam"
                final Map cfg = [:]
                """);

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                PipelineLauncher.applySettingsProvenanceConstants("""
                        final String SETTINGS_SOURCE = ""
                        final String SETTINGS_PROFILE_SHA256 = ""
                        final Map cfg = [:]
                        """, constants, PipelineLauncher.SettingsProfileState.scriptDefaults()));

        assertTrue(error.getMessage().contains("SETTINGS_PROFILE_NAME"));
        assertTrue(error.getMessage().contains("SETTINGS_PROFILE_PATH"));
        assertTrue(error.getMessage().contains("CONFIGURED_CONSTANTS_SHA256"));
        assertTrue(error.getMessage().contains("MANUAL_EDIT_AFTER_PROFILE_LOAD"));
    }

    @Test
    void savedModelDiscoveryHandlesZeroOneMultipleAndMalformed(@TempDir Path tempDir) throws Exception {
        File projectBase = tempDir.toFile();

        assertEquals(List.of(), PipelineLauncher.discoverSavedModelIds(projectBase, "nucleus").validIds());

        writeModelMetadata(projectBase, "nucleus", "nuc_a", "NUCLEUS");
        PipelineLauncher.SavedModelDiscovery one = PipelineLauncher.discoverSavedModelIds(projectBase, "nucleus");
        assertEquals(List.of("nuc_a"), one.validIds());
        assertTrue(one.invalidModels().isEmpty());

        writeModelMetadata(projectBase, "nucleus", "nuc_b", "NUCLEUS");
        PipelineLauncher.SavedModelDiscovery multiple = PipelineLauncher.discoverSavedModelIds(projectBase, "nucleus");
        assertEquals(List.of("nuc_a", "nuc_b"), multiple.validIds());

        Files.createDirectories(tempDir.resolve("astra/models/nucleus/bad"));
        PipelineLauncher.SavedModelDiscovery malformed = PipelineLauncher.discoverSavedModelIds(projectBase, "nucleus");
        assertTrue(malformed.invalidModels().containsKey("bad"));
    }

    @Test
    void savedModelDiscoveryDoesNotAutoselectWhenMultipleModelsExist(@TempDir Path tempDir) throws Exception {
        writeModelMetadata(tempDir.toFile(), "cell", "cell_a", "CELL");
        writeModelMetadata(tempDir.toFile(), "cell", "cell_b", "CELL");
        PipelineLauncher.SavedModelDiscovery discovery = PipelineLauncher.discoverSavedModelIds(tempDir.toFile(), "cell");
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants("""
                final String CELL_SAVED_MODEL_ID = ""
                final Map cfg = [:]
                """);

        assertEquals(List.of("cell_a", "cell_b"), discovery.validIds());
        assertFalse(Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java")).contains("prefillSingleSavedModelId"));
        assertEquals("\"\"", constants.get(0).currentDisplayValue());
    }

    @Test
    void savedModelDiscoveryDoesNotAutoselectWhenExactlyOneModelExists(@TempDir Path tempDir) throws Exception {
        writeModelMetadata(tempDir.toFile(), "nucleus", "nuc_only", "NUCLEUS");
        PipelineLauncher.SavedModelDiscovery discovery = PipelineLauncher.discoverSavedModelIds(tempDir.toFile(), "nucleus");
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants("""
                final String NUC_SAVED_MODEL_ID = ""
                final Map cfg = [:]
                """);

        assertEquals(List.of("nuc_only"), discovery.validIds());
        assertFalse(Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java")).contains("prefillSingleSavedModelId"));
        assertEquals("\"\"", constants.get(0).currentDisplayValue());
    }

    @Test
    void assetDiscoveryFindsOnlyProjectBackedPixelClassifiers(@TempDir Path tempDir) throws Exception {
        Path root = tempDir.resolve("classifiers/pixel_classifiers");
        Files.createDirectories(root);
        Files.writeString(root.resolve("lumen.json"), "{}");
        Files.writeString(root.resolve("smooth_muscle.json"), "{}");
        Files.writeString(root.resolve("notes.txt"), "ignore");

        PipelineLauncher.AssetDiscovery discovery = PipelineLauncher.discoverPixelClassifiers(tempDir.toFile());

        assertEquals(List.of("lumen", "smooth_muscle"), discovery.values());
        assertEquals("lumen", discovery.labelFor("lumen"));
        assertTrue(PipelineLauncher.discoverPixelClassifiers(tempDir.resolve("missing").toFile()).values().isEmpty());
    }

    @Test
    void assetDiscoveryFindsProjectModelFilesAndBestParameters(@TempDir Path tempDir) throws Exception {
        Path modelDir = tempDir.resolve("astra/models/nucleus/nuc_v1");
        Files.createDirectories(modelDir.resolve("tuning"));
        Files.writeString(modelDir.resolve("model.cpm"), "model");
        Files.writeString(modelDir.resolve("tuning/best_params.json"), "{}");
        Files.writeString(modelDir.resolve("readme.txt"), "ignore");

        PipelineLauncher.AssetDiscovery models = PipelineLauncher.discoverModelFiles(tempDir.toFile(), "nucleus");
        PipelineLauncher.AssetDiscovery bestParams = PipelineLauncher.discoverBestParamsFiles(tempDir.toFile(), "nucleus");

        assertEquals(List.of(modelDir.resolve("model.cpm").toFile().getAbsolutePath()), models.values());
        assertEquals("astra/models/nucleus/nuc_v1/model.cpm",
                models.labelFor(modelDir.resolve("model.cpm").toFile().getAbsolutePath()));
        assertEquals(List.of(modelDir.resolve("tuning/best_params.json").toFile().getAbsolutePath()),
                bestParams.values());
        assertEquals("nuc_v1 tuning best parameters",
                bestParams.labelFor(modelDir.resolve("tuning/best_params.json").toFile().getAbsolutePath()));
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
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants(script);

        PipelineLauncher.applyImageChannelDefaultNames(constants, List.of("DAPI", "AF488", "AF555", "AF647"));
        String configured = PipelineLauncher.applyConstants(script, constants);

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
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants(script);

        PipelineLauncher.applyImageChannelDefaultNames(constants, List.of("Only"));
        String configured = PipelineLauncher.applyConstants(script, constants);

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
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants(script);

        PipelineLauncher.applyImageChannelDefaultNames(constants, List.of("Channel 1", "Channel 2"));
        String configured = PipelineLauncher.applyConstants(script, constants);

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
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants(script);

        PipelineLauncher.applyImageChannelDefaultNames(constants, List.of("Hoechst"));
        String configured = PipelineLauncher.applyConstants(script, constants);

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
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants(script);

        PipelineLauncher.applyImageChannelDefaultNames(constants, List.of("DAPI", "Channel 2"));
        String configured = PipelineLauncher.applyConstants(script, constants);

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
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants(script);

        PipelineLauncher.applyImageChannelDefaultNames(constants, List.of("Hoechst", "Membrane"));
        String configured = PipelineLauncher.applyConstants(script, constants);

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
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants(script);

        PipelineLauncher.applyImageChannelDefaultNames(constants, List.of("Channel 1", "Channel 2"));
        String configured = PipelineLauncher.applyConstants(script, constants);

        assertTrue(configured.contains("final List CHANNELS_FOR_NUCLEUS = []"));
        assertTrue(configured.contains("final List CHANNELS_FOR_CELL = []"));
        assertTrue(configured.contains("final List NUCLEUS_SEGMENTATION_CHANNELS = []"));
        assertTrue(configured.contains("final List CELL_SEGMENTATION_CHANNELS = []"));
    }

    @Test
    void twoOpenedChannelsOnlyPopulateColocalizationSegmentationChannels() {
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants(colocalizationDefaultsScript());

        PipelineLauncher.applyImageChannelDefaultNames(constants, List.of("DAPI", "FITC"));
        String configured = PipelineLauncher.applyConstants(colocalizationDefaultsScript(), constants);

        assertTrue(configured.contains("final List NUCLEUS_SEGMENTATION_CHANNELS = [\"DAPI\"]"));
        assertTrue(configured.contains("final List CELL_SEGMENTATION_CHANNELS = [\"FITC\"]"));
        assertFalse(configured.contains("LABEL      : \"DAPI_AND_FITC_nucleus\""));
        assertFalse(configured.contains("\"FITC|Nucleus\"     : 100.0d"));
        assertTrue(configured.contains("LABEL      : \"DAPI_AND_AF488_nucleus\""));
    }

    @Test
    void hoechstAndAf555DoNotGenerateColocalizationChecks() {
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants(colocalizationDefaultsScript());

        PipelineLauncher.applyImageChannelDefaultNames(constants, List.of("Hoechst", "AF555"));
        String configured = PipelineLauncher.applyConstants(colocalizationDefaultsScript(), constants);

        assertTrue(configured.contains("final List NUCLEUS_SEGMENTATION_CHANNELS = [\"Hoechst\"]"));
        assertTrue(configured.contains("final List CELL_SEGMENTATION_CHANNELS = [\"AF555\"]"));
        assertFalse(configured.contains("LABEL      : \"Hoechst_AND_AF555_nucleus\""));
        assertFalse(configured.contains("THRESHOLD_EXCLUDE_MARKERS"));
        assertFalse(configured.contains("\"AF555|Nucleus\"     : 100.0d"));
    }

    @Test
    void arbitraryOpenedChannelsDoNotRewriteScientificColocalizationDefaults() {
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants(colocalizationDefaultsScript());

        PipelineLauncher.applyImageChannelDefaultNames(constants, List.of("Channel 1", "Channel 2"));
        String configured = PipelineLauncher.applyConstants(colocalizationDefaultsScript(), constants);

        assertTrue(configured.contains("final List NUCLEUS_SEGMENTATION_CHANNELS = []"));
        assertTrue(configured.contains("final List CELL_SEGMENTATION_CHANNELS = []"));
        assertFalse(configured.contains("Channel_1"));
        assertFalse(configured.contains("Channel 1|Nucleus"));
        assertTrue(configured.contains("DAPI_AND_AF488"));
        assertTrue(configured.contains("\"AF488|Nucleus\""));
    }

    @Test
    void oneNonNuclearOpenedChannelDoesNotCreateNucleusCheck() {
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants(colocalizationDefaultsScript());

        PipelineLauncher.applyImageChannelDefaultNames(constants, List.of("Only"));
        String configured = PipelineLauncher.applyConstants(colocalizationDefaultsScript(), constants);

        assertTrue(configured.contains("final List NUCLEUS_SEGMENTATION_CHANNELS = []"));
        assertTrue(configured.contains("final List CELL_SEGMENTATION_CHANNELS = []"));
        assertFalse(configured.contains("Only|Nucleus"));
        assertTrue(configured.contains("AF488"));
    }

    @Test
    void colocalizationPanelOwnsDetectionTarget() {
        assertTrue(PipelineLauncher.isHandledByColocalizationPanel("DETECTION_TARGET", true));
        assertTrue(PipelineLauncher.isHandledByColocalizationPanel("EXPRESSION_CLASSIFICATION_MODE", true));
        assertTrue(PipelineLauncher.isHandledByColocalizationPanel("DISPLAY_COLOCALIZATION_CHECK", true));
        assertTrue(PipelineLauncher.isHandledByColocalizationPanel("POSITIVITY_METHOD", true));
        assertTrue(PipelineLauncher.isHandledByColocalizationPanel("PIXEL_POSITIVE_FRACTION_MIN", true));
        assertTrue(PipelineLauncher.isHandledByColocalizationPanel("THRESHOLD_POPULATION", true));
        assertFalse(PipelineLauncher.isHandledByColocalizationPanel("MODEL_SOURCE", true));
        assertFalse(PipelineLauncher.isHandledByColocalizationPanel("MODEL_NAME", true));
        assertFalse(PipelineLauncher.isHandledByColocalizationPanel("MODEL_FILE", true));
        assertFalse(PipelineLauncher.isHandledByColocalizationPanel("DETECTION_TARGET", false));
        assertFalse(PipelineLauncher.isHandledByColocalizationPanel("MODEL_SOURCE", false));
    }

    @Test
    void launcherSourceUsesOneContentRailAndSharedRowHeight() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertTrue(source.contains("private static final double CONTENT_HORIZONTAL_MARGIN = 24.0;"));
        assertTrue(source.contains("private static final double PARAMETER_ROW_HEIGHT = 34.0;"));
        assertTrue(source.contains("private static final double PARAMETER_ROW_GAP = 8.0;"));
        assertTrue(source.contains("header.setPadding(new Insets(22.0, CONTENT_HORIZONTAL_MARGIN, 20.0, CONTENT_HORIZONTAL_MARGIN));"));
        assertTrue(source.contains("body.setPadding(new Insets(0, 0, 18.0, 0));"));
        assertTrue(source.contains("workspace.setPadding(new Insets(CONTENT_HORIZONTAL_MARGIN, CONTENT_HORIZONTAL_MARGIN, 18.0, CONTENT_HORIZONTAL_MARGIN));"));
        assertFalse(source.contains("BODY_HORIZONTAL_MARGIN"));
        assertFalse(source.contains("BODY_LEFT_MARGIN"));
        assertFalse(source.contains("BODY_RIGHT_MARGIN"));
    }

    @Test
    void launcherArchitectureUsesDeclaredSectionsAndNoDuplicatedSetupControls() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertTrue(source.contains("VBox header = new VBox(12.0);"));
        assertTrue(source.contains("VBox body = new VBox(14.0);"));
        assertTrue(source.contains("AnimatedGradientHeader animatedHeader = new AnimatedGradientHeader(header);"));
        assertTrue(source.contains("createHeaderOptionsMenu(animatedHeader)"));
        assertTrue(source.contains("PathPrefs.createPersistentPreference(HEADER_MODE_PREFERENCE_KEY"));
        assertTrue(source.contains("PathPrefs.createPersistentPreference(HEADER_MOTION_PREFERENCE_KEY"));
        assertTrue(source.contains("AnimatedGradientHeader.HeaderMode.DYNAMIC.name()"));
        assertTrue(source.contains("AnimatedGradientHeader.MotionSpeed.SMOOTH.name()"));
        assertTrue(source.contains("createSettingsNavigator(\"Settings Dashboard\""));
        assertTrue(source.contains("createSettingsNavigator(\"Advanced Settings\""));
        assertTrue(source.contains("Button dashboard = new Button(\"Dashboard\")"));
        assertTrue(source.contains("Button allSettings = new Button(\"All Settings\")"));
        assertTrue(source.contains("Node feedbackNode = feedback.node();"));
        assertTrue(source.contains("\"Colocalization Setup\""));
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
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertTrue(source.contains("private static HBox labeledRow(String labelText, Node editor, double labelWidth)"));
        assertTrue(source.contains("row.setMinHeight(PARAMETER_ROW_HEIGHT);"));
        assertTrue(source.contains("grid.setVgap(PARAMETER_ROW_GAP);"));
        assertTrue(source.contains("VBox group = new VBox(PARAMETER_ROW_GAP);"));
        assertTrue(source.contains("private final VBox rows = new VBox(PARAMETER_ROW_GAP);"));
        assertTrue(source.contains("HBox row = labeledRow(displayLabel(constant.name), editor, 160.0);"));
        assertTrue(source.contains("HBox row = labeledRow(displayLabel(\"DETECTION_TARGET\"), editor, 160.0);"));
        assertTrue(source.contains("private static final double SECTION_CONTENT_GAP = 9.0;"));
        assertTrue(source.contains("private static final double CARD_CONTENT_GAP = 8.0;"));
    }

    @Test
    void launcherUsesProfessionalLabelsForPipelineControls() {
        assertEquals("Nucleus Model Source", PipelineLauncher.displayLabel("NUC_MODEL_SOURCE"));
        assertEquals("Saved Nucleus Model", PipelineLauncher.displayLabel("NUC_SAVED_MODEL_ID"));
        assertEquals("Saved Cell Model", PipelineLauncher.displayLabel("CELL_SAVED_MODEL_ID"));
        assertEquals("Threshold Scope", PipelineLauncher.displayLabel("THRESHOLD_SCOPE"));
        assertEquals("Manual Background Offsets", PipelineLauncher.displayLabel("BACKGROUND_SUBTRACTION_BY_CHANNEL"));
        assertEquals("Detection Target", PipelineLauncher.displayLabel("DETECTION_TARGET"));
        assertEquals("Threshold Source Images", PipelineLauncher.displayLabel("THRESHOLD_SELECTED_IMAGE_NAMES"));
        assertEquals("Use GPU Acceleration", PipelineLauncher.displayLabel("USE_GPU"));
        assertEquals("Quality-Control File Name", PipelineLauncher.displayLabel("QC_FILENAME"));
        assertEquals("Selected Images", PipelineLauncher.displayLabel("SELECTED_IMAGE_NAMES"));
    }

    @Test
    void launcherDelegatesGuiTextPolicyToPresentationRouter() throws Exception {
        String launcher = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));
        String presentation = Files.readString(Path.of("src/main/java/qupath/ext/astra/GuiPresentation.java"));

        assertTrue(launcher.contains("return GuiPresentation.displayLabel(name);"));
        assertTrue(presentation.contains("final class GuiPresentation"));
        assertTrue(presentation.contains("static List<String> visibleRunModeOptions"));
        assertTrue(presentation.contains("static boolean supportsHeaderExport"));
        assertTrue(presentation.contains("static String displayOption"));
        assertEquals("Stages to Run", GuiPresentation.displayLabel("MODES_TO_RUN"));
        assertEquals("Use GPU Acceleration", GuiPresentation.displayLabel("USE_GPU"));
        assertEquals("Current Image", GuiPresentation.displayOption("CURRENT_IMAGE"));
        assertEquals("Selected Region", GuiPresentation.displayOption("IMAGE_SCOPE", "SELECTED_ANALYSIS_REGION"));
        assertEquals("Selected Images", GuiPresentation.displayOption("PROJECT_IMAGE_SELECTION"));
        assertEquals("Selected Images", GuiPresentation.displayOption("SELECTED_IMAGES"));
        assertEquals("Selected Region", GuiPresentation.displayOption("IMAGE_SCOPE", "SELECTED_ANALYSIS_REGION"));
        assertEquals("Selected Images", GuiPresentation.displayOption("IMAGE_SCOPE", "PROJECT_IMAGE_SELECTION"));
        assertEquals("Per Image", GuiPresentation.displayOption("THRESHOLD_SCOPE", "IMAGE"));
        assertEquals("Per Region", GuiPresentation.displayOption("THRESHOLD_SCOPE", "REGION"));
        assertEquals("Cell Mean", GuiPresentation.displayOption("THRESHOLD_POPULATION", "CELL_MEAN"));
        assertEquals("Pixel Intensity", GuiPresentation.displayOption("THRESHOLD_POPULATION", "PIXEL_INTENSITY"));
        assertEquals("Pixel Positive Fraction", GuiPresentation.displayOption("POSITIVITY_METHOD", "PIXEL_POSITIVE_FRACTION"));
        assertEquals("Pixel-Level Score", GuiPresentation.displayOption("EXPRESSION_CLASSIFICATION_MODE", "PIXEL_LEVEL_SCORE"));
        assertEquals("Legacy Binary", GuiPresentation.displayOption("EXPRESSION_CLASSIFICATION_MODE", "LEGACY_BINARY"));
        assertEquals("Local Percentile", GuiPresentation.displayOption("LOCAL_PERCENTILE"));
        assertEquals("Detection Target", GuiPresentation.displayLabel("DETECTION_TARGET"));
        assertEquals("Threshold Source Images", GuiPresentation.displayLabel("THRESHOLD_SELECTED_IMAGE_NAMES"));
        assertEquals(List.of("DETECT_CELLS", "QUANTIFY"),
                GuiPresentation.visibleRunModeOptions("Colocalization", List.of("RESET", "DETECT_CELLS", "QUANTIFY", "EXPORT")));
    }

    @Test
    void detectionTargetAndPerCheckThresholdExclusionsAreEditable() {
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants("""
                final List DETECTION_TARGET_OPTIONS = ["NUCLEUS", "CELL", "BOTH"]
                final String DETECTION_TARGET = "NUCLEUS"
                final List COLOCALIZATION_CHECKS = [
                    [LABEL: "one", COMPARTMENT: "Nucleus", CHANNELS: ["DAPI"], EXCLUDED_CHANNELS: ["DAPI"]]
                ]
                final Map cfg = [:]
                """);

        assertTrue(constants.stream().anyMatch(c -> c.name().equals("DETECTION_TARGET")));
        PipelineLauncher.EditableConstant checks = constants.stream()
                .filter(c -> c.name().equals("COLOCALIZATION_CHECKS"))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("DAPI"), PipelineLauncher.parseColocalizationChecks(checks.currentDisplayValue()).get(0).excludedChannels());
    }

    @Test
    void targetSpecificModelControlsRemainAvailableForBothTarget() {
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants(colocalizationModelScript("BOTH"));

        assertEquals(List.of("NUC_MODEL_SOURCE", "NUC_MODEL_NAME", "NUC_MODEL_FILE", "NUC_SAVED_MODEL_ID"),
                PipelineLauncher.targetModelControlNames("NUCLEUS"));
        assertEquals(List.of("CELL_MODEL_SOURCE", "CELL_MODEL_NAME", "CELL_MODEL_FILE", "CELL_SAVED_MODEL_ID"),
                PipelineLauncher.targetModelControlNames("CELL"));
        assertEquals(List.of("NUC_MODEL_SOURCE", "NUC_MODEL_NAME", "NUC_MODEL_FILE", "NUC_SAVED_MODEL_ID",
                        "CELL_MODEL_SOURCE", "CELL_MODEL_NAME", "CELL_MODEL_FILE", "CELL_SAVED_MODEL_ID"),
                PipelineLauncher.targetModelControlNames("BOTH"));
        assertFalse(PipelineLauncher.targetModelControlNames("BOTH").contains("MODEL_SOURCE"));
        assertFalse(PipelineLauncher.targetModelControlNames("BOTH").contains("MODEL_NAME"));
        assertFalse(PipelineLauncher.targetModelControlNames("BOTH").contains("MODEL_FILE"));
        assertTrue(constants.stream().anyMatch(c -> c.name().equals("NUC_MODEL_SOURCE")));
        assertTrue(constants.stream().anyMatch(c -> c.name().equals("NUC_MODEL_NAME")));
        assertTrue(constants.stream().anyMatch(c -> c.name().equals("NUC_MODEL_FILE")));
        assertTrue(constants.stream().anyMatch(c -> c.name().equals("CELL_MODEL_SOURCE")));
        assertTrue(constants.stream().anyMatch(c -> c.name().equals("CELL_MODEL_NAME")));
        assertTrue(constants.stream().anyMatch(c -> c.name().equals("CELL_MODEL_FILE")));
    }

    @Test
    void colocalizationModelDefaultsAreTargetSpecificValues() {
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants(colocalizationModelScript("BOTH"));

        Map<String, String> values = new java.util.LinkedHashMap<>();
        constants.forEach(c -> values.put(c.name(), c.currentDisplayValue()));

        assertEquals("\"MODEL_NAME\"", values.get("NUC_MODEL_SOURCE"));
        assertEquals("\"cpsam\"", values.get("NUC_MODEL_NAME"));
        assertEquals("\"MODEL_NAME\"", values.get("CELL_MODEL_SOURCE"));
        assertEquals("\"cpsam\"", values.get("CELL_MODEL_NAME"));
    }

    @Test
    void colocalizationPanelStateFollowsDetectionTarget() {
        assertEquals(new PipelineLauncher.ColocalizationPanelState(true, false, true, false),
                PipelineLauncher.colocalizationPanelState("NUCLEUS"));
        assertEquals(new PipelineLauncher.ColocalizationPanelState(false, true, false, true),
                PipelineLauncher.colocalizationPanelState("CELL"));
        assertEquals(new PipelineLauncher.ColocalizationPanelState(true, true, true, true),
                PipelineLauncher.colocalizationPanelState("BOTH"));
    }

    @Test
    void markerExclusionKeysComeOnlyFromColocalizationChecks() {
        List<PipelineLauncher.ColocalizationCheck> checks = List.of(
                new PipelineLauncher.ColocalizationCheck("DAPI_AF488", "Nucleus", List.of("DAPI", "AF488"), List.of("DAPI"))
        );

        List<String> keys = PipelineLauncher.markerKeysFromChecks(checks);

        assertTrue(keys.contains("DAPI|Nucleus"));
        assertTrue(keys.contains("AF488|Nucleus"));
        assertFalse(keys.contains("AF647|Nucleus"));
        assertEquals(List.of("AF488|Nucleus"), PipelineLauncher.thresholdedMarkerKeysFromChecks(checks));
        assertEquals("[\"DAPI|Nucleus\"]", PipelineLauncher.renderStringList(List.of("DAPI|Nucleus")));
    }

    @Test
    void markerMapKeysIncludeAllCheckRowsAndCompartments() {
        List<PipelineLauncher.ColocalizationCheck> checks = List.of(
                new PipelineLauncher.ColocalizationCheck("nuc", "Nucleus", List.of("DAPI", "AF488"), List.of("DAPI")),
                new PipelineLauncher.ColocalizationCheck("cyto", "Cytoplasm", List.of("AF555"), List.of()),
                new PipelineLauncher.ColocalizationCheck("cell", "Cell", List.of("AF647"), List.of())
        );

        assertEquals(List.of("AF488|Nucleus", "AF555|Cytoplasm", "AF647|Cell"),
                PipelineLauncher.thresholdedMarkerKeysFromChecks(checks));
    }

    @Test
    void perCheckThresholdExclusionsAreRenderedInsideEachCheck() {
        List<PipelineLauncher.ColocalizationCheck> checks = List.of(
                new PipelineLauncher.ColocalizationCheck("fitc", "Nucleus", List.of("FITC"), List.of("FITC")),
                new PipelineLauncher.ColocalizationCheck("af647", "Cell", List.of("AF647"), List.of())
        );
        String rendered = PipelineLauncher.renderColocalizationChecks(checks);
        List<PipelineLauncher.ColocalizationCheck> parsed = PipelineLauncher.parseColocalizationChecks(rendered);

        assertEquals(List.of("FITC"), parsed.get(0).excludedChannels());
        assertEquals(List.of(), parsed.get(1).excludedChannels());
    }

    @Test
    void finalSummaryShowsEffectiveTargetSpecificModels() {
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants("""
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

        String summary = PipelineLauncher.finalConfigSummary("Colocalization", "123456789012", constants);

        assertTrue(summary.contains("effective nucleus model source: MODEL_NAME"));
        assertTrue(summary.contains("effective nucleus model: nuc-special"));
        assertTrue(summary.contains("effective cell model source: MODEL_NAME"));
        assertTrue(summary.contains("effective cell model: cpsam"));
        assertFalse(summary.contains("(inherited)"));
    }

    @Test
    void finalSummaryShowsEffectiveSavedModelIds() {
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants("""
                final List DETECTION_TARGET_OPTIONS = ["NUCLEUS", "CELL", "BOTH"]
                final String DETECTION_TARGET = "NUCLEUS"
                final List NUC_MODEL_SOURCE_OPTIONS = ["MODEL_NAME", "SAVED", "FILE"]
                final String NUC_MODEL_SOURCE = "SAVED"
                final String NUC_MODEL_NAME = "cpsam"
                final String NUC_MODEL_FILE = ""
                final String NUC_SAVED_MODEL_ID = "nuc_v1"
                final Map cfg = [:]
                """);

        String summary = PipelineLauncher.finalConfigSummary("Colocalization", "123456789012", constants);

        assertTrue(summary.contains("effective nucleus model source: SAVED"));
        assertTrue(summary.contains("effective nucleus model: nuc_v1"));
        assertTrue(summary.contains("effective cell model: not used for DETECTION_TARGET=NUCLEUS"));
    }

    @Test
    void finalSummaryUsesTrainingTargetForEffectiveModels() {
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants("""
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

        String summary = PipelineLauncher.finalConfigSummary("Training", "123456789012", constants);

        assertTrue(summary.contains("training target: \"NUCLEUS\""));
        assertTrue(summary.contains("effective nucleus model: nuc-train"));
        assertTrue(summary.contains("effective cell model: not used for TRAIN_TARGET=NUCLEUS"));
        assertFalse(summary.contains("not used for DETECTION_TARGET"));
    }

    @Test
    void finalSummaryUsesTuningAndValidationTargetsForEffectiveModels() {
        List<PipelineLauncher.EditableConstant> tuning = PipelineLauncher.extractEditableConstants("""
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
        List<PipelineLauncher.EditableConstant> validation = PipelineLauncher.extractEditableConstants("""
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

        String tuningSummary = PipelineLauncher.finalConfigSummary("Tuning", "123456789012", tuning);
        String validationSummary = PipelineLauncher.finalConfigSummary("Validation", "123456789012", validation);

        assertTrue(tuningSummary.contains("effective nucleus model: not used for TUNE_TARGET=CELL"));
        assertTrue(tuningSummary.contains("effective cell model: cell-tune"));
        assertTrue(validationSummary.contains("effective nucleus model: nuc-val"));
        assertTrue(validationSummary.contains("effective cell model: cell-val"));
    }

    @Test
    void colocalizationCheckBuilderRendersGroovyMapList() {
        String rendered = PipelineLauncher.renderColocalizationChecks(List.of(
                new PipelineLauncher.ColocalizationCheck("A_B_nucleus", "Nucleus", List.of("A", "B"), List.of("A")),
                new PipelineLauncher.ColocalizationCheck("C_cell", "Cell", List.of("C"), List.of())
        ));

        assertTrue(rendered.contains("LABEL      : \"A_B_nucleus\""));
        assertTrue(rendered.contains("COMPARTMENT: \"Cell\""));
        assertTrue(rendered.contains("CHANNELS   : [\"A\", \"B\"]"));
        assertTrue(rendered.contains("EXCLUDED_CHANNELS: [\"A\"]"));
    }

    @Test
    void colocalizationCheckEditorDoesNotCreateHardcodedFallbackRow() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertFalse(source.contains("new ColocalizationCheck(\"DAPI_AND_AF488_nucleus\""));
        assertFalse(source.contains("setRawConstant(constants, \"COLOCALIZATION_CHECKS\""));
        assertFalse(source.contains("setRawConstant(constants, \"MANUAL_INTENSITY_THRESHOLDS\""));
    }

    @Test
    void colocalizationCheckRowUsesReadableDeleteControl() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertTrue(source.contains("Button remove = new Button(\"Delete check\")"));
        assertTrue(source.contains("new Tooltip(\"Delete check\")"));
        assertFalse(source.contains("new Button(\"🗑\")"));
        assertFalse(source.contains("new Button(\"...\")"));
        assertFalse(source.contains("setText(\"...\")"));
        assertTrue(source.contains("nestedField(\"Check name\", label)"));
        assertTrue(source.contains("nestedField(\"Compartment\", compartment)"));
        assertTrue(source.contains("new ChannelCheckboxEditor(\"\", imageChannels"));
        assertTrue(source.contains("new ChannelCheckboxEditor(\"\", check.channels(), renderStringList(check.excludedChannels()), \"Choose check channels first.\")"));
        assertTrue(source.contains("nestedField(\"Check channels\", channelSelector)"));
        assertTrue(source.contains("nestedField(\"Threshold exclusions\", exclusionSelector)"));
        assertTrue(source.contains("private static final class MultiSelectListEditor extends VBox"));
        assertTrue(source.contains("list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE)"));
        assertTrue(source.contains("Button selectAll = ProjectImageSelectionEditor.smallButton(\"Select All\")"));
        assertTrue(source.contains("Button clear = ProjectImageSelectionEditor.smallButton(\"Clear\")"));
        assertTrue(source.contains("ButtonType.APPLY"));
        assertFalse(source.contains("FlowPane channels"));
        assertTrue(source.contains("compartment.getItems().addAll(\"Nucleus\", \"Cytoplasm\", \"Cell\")"));
        assertTrue(source.contains("compartment.setMinWidth(120.0)"));
        assertTrue(source.contains("compartment.setPrefWidth(130.0)"));
        assertTrue(source.contains("styleComboBox(compartment)"));
        assertTrue(source.contains("label.setMinWidth(180.0)"));
        assertTrue(source.contains("private static String nestedLabelStyle()"));
        assertTrue(source.contains("private static String checkBoxStyle()"));
        assertTrue(source.contains("private static void styleCheckBox(CheckBox box)"));
    }

    @Test
    void comboBoxSelectedValueUsesReadableTextColor() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertTrue(source.contains("private static void styleComboBox(ComboBox<String> combo)"));
        assertTrue(source.contains("combo.getEditor().setStyle(EditableConstant.controlStyle())"));
        assertTrue(source.contains("private static void styleComboBoxSubnodes(ComboBox<String> combo)"));
        assertTrue(source.contains("combo.lookup(\".arrow-button\")"));
        assertTrue(source.contains("arrowButton.setStyle(\"-fx-background-color: transparent;"));
        assertTrue(source.contains("-fx-text-base-color: \" + INK"));
        assertTrue(source.contains("combo.setButtonCell(readableComboCell())"));
        assertTrue(source.contains("combo.setCellFactory(list -> readableComboCell())"));
        assertTrue(source.contains("-fx-text-fill: \" + INK"));
    }

    @Test
    void runFeedbackPaneResizesWithLauncherWidth() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertTrue(source.contains("box.setMaxWidth(Double.MAX_VALUE);"));
        assertTrue(source.contains("HBox.setHgrow(box, Priority.ALWAYS);"));
        assertFalse(source.contains("HBox.setHgrow(box, Priority.NEVER);"));
        assertFalse(source.contains("box.setMaxWidth(520.0);"));
    }

    @Test
    void launcherOwnsOneModalErrorDialogPerGuiRun() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

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
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertTrue(source.contains("addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get(\"POSITIVITY_METHOD\")"));
        assertTrue(source.contains("addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get(\"EXPRESSION_CLASSIFICATION_MODE\")"));
        assertTrue(source.contains("installDisplayCheckSelector(checksPanel, displayCheckConstant, checksEditor, autosave)"));
        assertTrue(source.contains("checksPanel.getChildren().add(labeledRow(\"Display in QuPath UI\", displayCheck, 160.0))"));
        assertFalse(source.contains("addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get(\"DISPLAY_COLOCALIZATION_CHECK\")"));
        assertTrue(source.contains("addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get(\"PIXEL_POSITIVE_FRACTION_MIN\")"));
        assertTrue(source.contains("addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get(\"THRESHOLD_POPULATION\")"));
        assertTrue(source.contains("addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get(\"GMM_COMPONENTS\")"));
        assertTrue(source.contains("addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get(\"OTSU_CLASS_COUNT\")"));
        assertTrue(source.contains("addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get(\"THRESHOLD_SCOPE\")"));
        assertTrue(source.contains("addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get(\"THRESHOLD_SELECTED_IMAGE_NAMES\")"));
        assertTrue(source.contains("addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get(\"THRESHOLD_PROVENANCE_BY_MARKER\")"));
        assertFalse(source.contains("THRESHOLD_EXCLUDE_MARKERS"));
        assertTrue(source.contains("installColocalizationThresholdVisibility(byName, thresholdRows, thresholdPanel)"));
        assertTrue(source.contains("static Set<String> colocalizationThresholdVisibilityState("));
        assertTrue(source.contains("List.of(\"EXPRESSION_CLASSIFICATION_MODE\", \"POSITIVITY_METHOD\", \"THRESHOLD_MODE\", \"THRESHOLD_SCOPE\", \"BACKGROUND_MODE\")"));
        assertFalse(source.contains("BACKGROUND_SCOPE"));
        assertTrue(source.contains("panel.requestLayout()"));
        assertTrue(source.contains("row.editor.setDisable(!visible)"));
    }

    @Test
    void genericVisibilityOnlyListensToControlsThatDriveVisibility() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertTrue(source.contains("\"IMAGE_SCOPE\","));
        assertTrue(source.contains("\"THRESHOLD_SCOPE\","));
        assertTrue(source.contains("\"USE_BATCH_MODE\""));
        assertFalse(source.contains("byName.values().forEach(c -> c.addChangeListener(update))"));
        assertFalse(source.contains("byName.values().forEach(c -> c.addOptionListener(update))"));
    }

    @Test
    void colocalizationModesUseOrderedMultiSelectAndHeaderExport() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));
        String script = realBaseScript("modules/pipelines/analysis/colocalization/src/main/groovy/colocalization.groovy");

        assertEquals(List.of("DETECT_CELLS", "QUANTIFY"),
                GuiPresentation.visibleRunModeOptions("Colocalization", List.of()));
        assertTrue(script.contains("final Map USER_OVERRIDES"));
        assertTrue(source.contains("private static final class StageModeEditor extends VBox"));
        assertTrue(source.contains("private final MultiSelectListEditor selector;"));
        assertTrue(source.contains("new MultiSelectListEditor(\"\", orderedModes, EditableConstant.csvValues(rawValue)"));
        assertTrue(source.contains("installColocalizationRunModeEditor(scriptName, constants)"));
        assertTrue(source.contains("Choose stages in ASTRA's fixed order. Reset and export are separate script actions."));
        assertTrue(source.contains("notifyListenersAfterModalClose(listeners);"));
        assertTrue(source.contains("new Button(\"Reset Image\")"));
        assertTrue(source.contains("new Button(\"Reset Project\")"));
        assertFalse(source.contains("new Button(\"Reset Image...\")"));
        assertFalse(source.contains("new Button(\"Reset Project...\")"));
        assertTrue(source.contains("new Button(\"Export\")"));
        assertTrue(source.contains("styleButton(resetImage, ButtonRole.DANGER)"));
        assertTrue(source.contains("styleButton(resetProject, ButtonRole.DANGER)"));
        assertTrue(source.contains("styleButton(export, ButtonRole.SUCCESS)"));
        assertTrue(source.contains("AnimatedGradientHeader animatedHeader = new AnimatedGradientHeader(header);"));
        assertTrue(source.contains("new MenuButton(\"Options\")"));
        assertTrue(source.contains("headerSegmentButton(\"Static\")"));
        assertTrue(source.contains("headerSegmentButton(\"Dynamic\")"));
        assertTrue(source.contains("Motion\""));
        assertFalse(source.contains("installDynamicHeaderGradient(header, scriptName)"));
        assertTrue(source.contains("Map.of(\"SCRIPT_ACTION\", \"\\\"EXPORT\\\"\")"));
        assertFalse(script.contains("MODES_TO_RUN_OPTIONS = [\"RESET\", \"DETECT_CELLS\", \"QUANTIFY\", \"EXPORT\"]"));
    }

    @Test
    void compactEntrypointOmitsUnchangedSymbolicDefaultsFromUserOverrides() throws Exception {
        String script = realBaseScript("modules/pipelines/analysis/colocalization/src/main/groovy/colocalization.groovy");
        List<PipelineLauncher.EditableConstant> constants =
                PipelineLauncher.editableConstantsForScript("Colocalization", script);

        String rendered = PipelineLauncher.applyConstants(script, constants, PipelineLauncher.SettingsProfileState.scriptDefaults());

        assertFalse(rendered.contains("CellposeInferenceDefaults."),
                "The launcher must not render symbolic contract defaults before PipelineEntrypoint loads them.");
        assertFalse(rendered.contains("NUC_CELLPROB:"),
                "Unchanged contract defaults should be resolved by PipelineEntrypoint, not USER_OVERRIDES.");

        PipelineLauncher.EditableConstant nucCellprob = constants.stream()
                .filter(c -> "NUC_CELLPROB".equals(c.name()))
                .findFirst()
                .orElseThrow();
        assertEquals("0.0d", nucCellprob.currentDisplayValue());
        nucCellprob.setDisplayValue("0.125d");

        String changed = PipelineLauncher.applyConstants(script, constants, PipelineLauncher.SettingsProfileState.scriptDefaults());

        assertTrue(changed.contains("NUC_CELLPROB: 0.125d"),
                "Changed GUI values must still render as explicit USER_OVERRIDES.");
        assertFalse(changed.contains("CellposeInferenceDefaults."),
                "Changed overrides must not reintroduce unresolved symbolic defaults.");
    }

    @Test
    void scriptActionOverrideRendersQuotedGroovyAction() {
        String script = """
                final List MODES_TO_RUN = ["DETECT_CELLS", "QUANTIFY"]
                final String SCRIPT_ACTION = "RUN"
                final String SETTINGS_SOURCE = "script"
                final String SETTINGS_PROFILE_NAME = ""
                final String SETTINGS_PROFILE_PATH = ""
                final String SETTINGS_PROFILE_SHA256 = ""
                final String CONFIGURED_CONSTANTS_SHA256 = ""
                final boolean MANUAL_EDIT_AFTER_PROFILE_LOAD = false
                final Map cfg = [:]
                """;
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants(script);

        String rendered = PipelineLauncher.applyConstants(script, constants, PipelineLauncher.SettingsProfileState.scriptDefaults(),
                Map.of("SCRIPT_ACTION", "\"EXPORT\""));

        assertTrue(rendered.contains("final List MODES_TO_RUN = [\"DETECT_CELLS\", \"QUANTIFY\"]"));
        assertTrue(rendered.contains("final String SCRIPT_ACTION = \"EXPORT\""));
        assertFalse(rendered.contains("final String SCRIPT_ACTION = EXPORT"));
    }

    @Test
    void colocalizationThresholdVisibilityDefaultImageStateIsCompact() {
        Set<String> visible = PipelineLauncher.colocalizationThresholdVisibilityState("LEGACY_BINARY", "MEAN_INTENSITY", "LOG_GAUSSIAN_MIXTURE", "IMAGE", "NONE");

        assertEquals(Set.of("EXPRESSION_CLASSIFICATION_MODE", "POSITIVITY_METHOD", "THRESHOLD_POPULATION", "THRESHOLD_MODE", "THRESHOLD_SCOPE", "BACKGROUND_MODE", "GMM_COMPONENTS"), visible);
    }

    @Test
    void colocalizationSelectedThresholdSourceRevealsImageSelector() {
        Set<String> image = PipelineLauncher.colocalizationThresholdVisibilityState("LEGACY_BINARY", "MEAN_INTENSITY", "LOG_GAUSSIAN_MIXTURE", "IMAGE", "NONE");
        Set<String> region = PipelineLauncher.colocalizationThresholdVisibilityState("LEGACY_BINARY", "MEAN_INTENSITY", "LOG_GAUSSIAN_MIXTURE", "REGION", "NONE");
        Set<String> selected = PipelineLauncher.colocalizationThresholdVisibilityState("LEGACY_BINARY", "MEAN_INTENSITY", "LOG_GAUSSIAN_MIXTURE", "SELECTED_IMAGES", "NONE");

        assertEquals(image, region);
        assertTrue(selected.contains("THRESHOLD_SELECTED_IMAGE_NAMES"));
        assertTrue(selected.contains("MATCH_THRESHOLD_IMAGE_NAMES_AGAINST_ORIGINAL"));
    }

    @Test
    void colocalizationThresholdVisibilityIsModeDriven() {
        assertTrue(PipelineLauncher.colocalizationThresholdVisibilityState("LEGACY_BINARY", "MEAN_INTENSITY", "MANUAL", "IMAGE", "NONE")
                .contains("MANUAL_INTENSITY_THRESHOLDS"));
        assertTrue(PipelineLauncher.colocalizationThresholdVisibilityState("LEGACY_BINARY", "MEAN_INTENSITY", "MANUAL", "IMAGE", "NONE")
                .contains("THRESHOLD_PROVENANCE_BY_MARKER"));
        assertTrue(PipelineLauncher.colocalizationThresholdVisibilityState("LEGACY_BINARY", "MEAN_INTENSITY", "RANGE_PERCENT", "IMAGE", "NONE")
                .contains("RANGE_THRESHOLD_FRACTION_BY_MARKER"));
        assertTrue(PipelineLauncher.colocalizationThresholdVisibilityState("LEGACY_BINARY", "MEAN_INTENSITY", "LOG_GAUSSIAN_MIXTURE", "IMAGE", "MANUAL_OFFSET")
                .contains("BACKGROUND_SUBTRACTION_BY_CHANNEL"));

        Set<String> local = PipelineLauncher.colocalizationThresholdVisibilityState("LEGACY_BINARY", "MEAN_INTENSITY", "LOG_GAUSSIAN_MIXTURE", "IMAGE", "LOCAL_PERCENTILE");
        assertTrue(local.contains("LOCAL_BACKGROUND_PERCENTILE"));

        Set<String> pixelManual = PipelineLauncher.colocalizationThresholdVisibilityState("PIXEL_LEVEL_SCORE", "MEAN_INTENSITY", "MANUAL", "IMAGE", "NONE");
        assertTrue(pixelManual.contains("MANUAL_INTENSITY_BOUNDARIES_BY_MARKER"));
        assertFalse(pixelManual.contains("MANUAL_INTENSITY_THRESHOLDS"));
        assertFalse(pixelManual.contains("BACKGROUND_MODE"));
    }

    @Test
    void colocalizationPixelFractionVisibilityKeepsCutoffBesidePositivityMethod() {
        Set<String> mean = PipelineLauncher.colocalizationThresholdVisibilityState("LEGACY_BINARY", "MEAN_INTENSITY", "LOG_KDE_VALLEY", "IMAGE", "NONE");
        Set<String> fraction = PipelineLauncher.colocalizationThresholdVisibilityState("LEGACY_BINARY", "PIXEL_POSITIVE_FRACTION", "LOG_KDE_VALLEY", "IMAGE", "NONE");

        assertFalse(mean.contains("PIXEL_POSITIVE_FRACTION_MIN"));
        assertTrue(fraction.contains("PIXEL_POSITIVE_FRACTION_MIN"));
    }

    @Test
    void launcherDoesNotPreserveRemovedAnalysisAliases() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertFalse(source.contains("RESET_BASELINE"));
        assertFalse(source.contains("EXPORT_ON_QUANTIFY"));
        assertFalse(source.contains("EXPORT_RESULTS"));
        assertFalse(source.contains("LOCAL_ROI_PERCENTILE"));
        assertFalse(source.contains("LOCAL_SLIDE_PERCENTILE"));
        assertFalse(source.contains("LOCAL_REGION_PERCENTILE"));
        assertFalse(source.contains("LOCAL_IMAGE_PERCENTILE"));
        assertFalse(source.contains("BACKGROUND_SCOPE"));
        assertFalse(source.contains("THRESHOLD_EXCLUDE_MARKERS"));
    }

    @Test
    void colocalizationMarkerKeyMapSettingsUseSpecializedEditors() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

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
        String renderedNumeric = PipelineLauncher.renderMarkerKeyMapValues(numeric, PipelineLauncher.MarkerMapValueType.NUMERIC);

        assertTrue(renderedNumeric.contains("\"AF647|Nucleus\": 12.5d"));
        assertFalse(renderedNumeric.contains("DAPI|Nucleus"));

        Map<String, String> text = new java.util.LinkedHashMap<>();
        text.put("AF647|Nucleus", "manual \"publication\" threshold");
        String renderedText = PipelineLauncher.renderMarkerKeyMapValues(text, PipelineLauncher.MarkerMapValueType.TEXT);

        assertTrue(renderedText.contains("\"AF647|Nucleus\": \"manual \\\"publication\\\" threshold\""));
    }

    @Test
    void markerKeyMapParsingReadsExistingGroovyMaps() {
        Map<String, String> numeric = PipelineLauncher.parseMarkerKeyMapValues("""
                [
                    "AF488|Nucleus": 100.0d,
                    "AF647|Cell": 2.5
                ]
                """, PipelineLauncher.MarkerMapValueType.NUMERIC);
        Map<String, String> text = PipelineLauncher.parseMarkerKeyMapValues("""
                [
                    "AF488|Nucleus": "manual source"
                ]
                """, PipelineLauncher.MarkerMapValueType.TEXT);

        assertEquals("100.0", numeric.get("AF488|Nucleus"));
        assertEquals("2.5", numeric.get("AF647|Cell"));
        assertEquals("manual source", text.get("AF488|Nucleus"));
    }

    @Test
    void selectedImageNamesUsesProjectImageSelectionDialog() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertTrue(source.contains("private static final class ProjectImageSelectionEditor extends VBox"));
        assertTrue(source.contains("Dialog<ButtonType> dialog = new Dialog<>()"));
        assertTrue(source.contains("setTitle(\"ASTRA Project Image Selection\")"));
        assertTrue(source.contains("ListView<String> available"));
        assertTrue(source.contains("ListView<String> chosen"));
        assertTrue(source.contains("transferButton(\"Add >\")"));
        assertTrue(source.contains("transferButton(\"Add All >>\")"));
        assertTrue(source.contains("transferButton(\"< Remove\")"));
        assertTrue(source.contains("transferButton(\"<< Remove All\")"));
        assertTrue(source.contains("summary.setText(names.size() + \" of \" + allNames.size()"));
        assertTrue(source.contains("smallButton(\"Paste Image Names\")"));
        assertTrue(source.contains("renderStringList(selectedNames())"));
        assertTrue(source.contains("\"THRESHOLD_SELECTED_IMAGE_NAMES\".equals(c.name)"));
        assertTrue(source.contains("available.setCellFactory(list -> readableListCell())"));
        assertTrue(source.contains("chosen.setCellFactory(list -> readableListCell())"));
        assertTrue(source.contains("private static void notifyListenersAfterModalClose(List<Runnable> listeners)"));
        assertTrue(source.contains("Platform.runLater(() -> snapshot.forEach(Runnable::run));"));
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
        List<PipelineLauncher.EditableConstant> constants = PipelineLauncher.extractEditableConstants(script);

        PipelineLauncher.applyImageChannelDefaultNames(constants, List.of("Channel 1", "Channel 2"));
        String configured = PipelineLauncher.applyConstants(script, constants);

        assertTrue(configured.contains("final String CHANNEL_DAPI = \"\""));
        assertTrue(configured.contains("final String CHANNEL_WGA = \"\""));
        assertTrue(configured.contains("final String CHANNEL_ASMA = \"\""));
        assertTrue(configured.contains("final String CHANNEL_CD31 = \"\""));
        assertFalse(configured.contains("Channel 1"));
        assertFalse(configured.contains("AF488"));
    }

    @Test
    void retiredImageScopesAreNotVisibleInExtensionSource() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));
        String presentation = Files.readString(Path.of("src/main/java/qupath/ext/astra/GuiPresentation.java"));

        assertFalse(source.contains("ALL_IMAGES"));
        assertFalse(source.contains("SELECTED_IMAGES_BY_NAME"));
        assertFalse(presentation.contains("ALL_IMAGES"));
        assertFalse(presentation.contains("SELECTED_IMAGES_BY_NAME"));
        assertTrue(source.contains("PROJECT_IMAGE_SELECTION"));
        assertEquals("Selected Region", GuiPresentation.displayOption("SELECTED_ANALYSIS_REGION"));
    }

    @Test
    void provisionalVascularModesRequireOneConfirmation() {
        assertFalse(PipelineLauncher.requiresProvisionalVascularConfirmation(extractModes("[\"GENERATE_REGIONS\", \"DETECT_CELLS\"]")));
        assertTrue(PipelineLauncher.requiresProvisionalVascularConfirmation(extractModes("[\"AUTO_BUILD_CLASSIFIERS\"]")));
        assertTrue(PipelineLauncher.requiresProvisionalVascularConfirmation(extractModes("[\"AUTO_SELECT_ROIS\"]")));
        assertTrue(PipelineLauncher.requiresProvisionalVascularConfirmation(extractModes("[\"AUTO_BUILD_CLASSIFIERS\", \"AUTO_SELECT_ROIS\"]")));
    }

    @Test
    void runLogCaptureForwardsLogTextAndStopsAfterClose() {
        List<String> lines = new ArrayList<>();
        // QuPath's root LogManager depends on the full QuPath runtime/logback stack.
        // Unit tests exercise the run-scoped bridge deterministically; live QuPath
        // uses RunLogCapture.attach(...) to register the same bridge with LogManager.
        PipelineLauncher.RunLogCapture capture = PipelineLauncher.RunLogCapture.forTest(lines::add);
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
    void runLogParserClassifiesPipelineQuPathCellposeAndPythonOutput() {
        List<RunLogEntry> entries = RunLogParser.parse("""
                [INFO] Started Colocalization.
                [WARN] Settings autosave skipped.
                ERROR qupath.ext.astra.Runner - QuPath bridge failed
                INFO: AstraCellpose2D: 2026-05-22 17:55:45,761 [WARNING] the '--diam_mean' flag is deprecated
                INFO: AstraCellpose2D: >>>> running cellpose on 1 images using all channels
                INFO: ASTRA Colocalization started from configuration dialog.
                ERROR: Quantify: resolved threshold for AF647|Nucleus must be finite.
                Traceback (most recent call last):
                """, RunLogSource.QUPATH, RunLogSeverity.NEUTRAL);

        assertEquals(RunLogSource.ASTRA, entries.get(0).source());
        assertEquals(RunLogSeverity.INFO, entries.get(0).severity());
        assertEquals(RunLogSeverity.WARNING, entries.get(1).severity());
        assertEquals(RunLogSource.QUPATH, entries.get(2).source());
        assertEquals(RunLogSeverity.ERROR, entries.get(2).severity());
        assertEquals(RunLogSource.CELLPOSE, entries.get(3).source());
        assertEquals(RunLogSeverity.WARNING, entries.get(3).severity());
        assertTrue(entries.get(3).text().contains("--diam_mean"));
        assertEquals(RunLogSource.CELLPOSE, entries.get(4).source());
        assertFalse(entries.get(4).text().contains(">>>>"));
        assertEquals(RunLogSource.ASTRA, entries.get(5).source());
        assertEquals(RunLogSeverity.INFO, entries.get(5).severity());
        assertEquals(RunLogSource.ASTRA, entries.get(6).source());
        assertEquals(RunLogSeverity.ERROR, entries.get(6).severity());
        assertEquals(RunLogSource.PYTHON, entries.get(7).source());
        assertEquals(RunLogSeverity.ERROR, entries.get(7).severity());
    }

    @Test
    void runLogParserFormatsCompatibilityTextThroughStructuredEntries() {
        String formatted = PipelineLauncher.formatGuiLogText("""
                [LOG] COLOCALIZATION COLOCALIZATION [PREFLIGHT] Runtime Python synchronized.
                ERROR COLOCALIZATION [QUANTIFY] Non-finite AF647|Nucleus measurements detected.
                cellpose: 34 tile images processed
                """);

        assertTrue(formatted.contains("Preflight: Runtime Python synchronized."));
        assertTrue(formatted.contains("ERROR: Quantify: Non-finite AF647|Nucleus measurements detected."));
        assertTrue(formatted.contains("34 tile images processed"));
        assertFalse(formatted.contains("[LOG]"));
        assertFalse(formatted.contains("COLOCALIZATION COLOCALIZATION"));

        String cellpose = PipelineLauncher.formatGuiLogText("""
                INFO: AstraCellpose2D: 2026-05-22 17:55:45,761 [WARNING] the '--diam_mean' flag is deprecated
                INFO: AstraCellpose2D: >>>> running cellpose on 1 images using all channels
                """);

        assertTrue(cellpose.contains("the '--diam_mean' flag is deprecated"));
        assertTrue(cellpose.contains("running cellpose on 1 images using all channels"));
        assertFalse(cellpose.contains("AstraCellpose2D"));
        assertFalse(cellpose.contains(">>>>"));
    }

    @Test
    void runLogParserDoesNotLetAstraObjectNamesChangeScriptSource() {
        List<RunLogEntry> entries = RunLogParser.parse("""
                SMA-AF647  Region scored: [1/10] 'ASTRA SMA Media Search 4' | cells=200
                SMA-AF647  Region scored: [2/10] 'ROI 3' | cells=123
                """, RunLogSource.SCRIPT, RunLogSeverity.NEUTRAL);

        assertEquals(2, entries.size());
        assertEquals(RunLogSource.SCRIPT, entries.get(0).source());
        assertEquals(RunLogSource.SCRIPT, entries.get(1).source());
    }

    @Test
    void runLogGrouperKeepsConsecutiveSameSourceEntriesInOneBlock() {
        List<RunLogEntry> entries = List.of(
                new RunLogEntry(RunLogSource.ASTRA, RunLogSeverity.INFO, RunLogKind.MESSAGE, "start", "start"),
                new RunLogEntry(RunLogSource.ASTRA, RunLogSeverity.WARNING, RunLogKind.MESSAGE, "warn", "warn"),
                new RunLogEntry(RunLogSource.CELLPOSE, RunLogSeverity.INFO, RunLogKind.MESSAGE, "gpu", "gpu"),
                new RunLogEntry(RunLogSource.CELLPOSE, RunLogSeverity.ERROR, RunLogKind.MESSAGE, "failed", "failed"),
                new RunLogEntry(RunLogSource.ASTRA, RunLogSeverity.SUCCESS, RunLogKind.MESSAGE, "done", "done")
        );

        List<RunLogBlock> blocks = RunLogGrouper.groupBySource(entries);

        assertEquals(3, blocks.size());
        assertEquals(RunLogSource.ASTRA, blocks.get(0).source());
        assertEquals(2, blocks.get(0).entries().size());
        assertEquals(RunLogSource.CELLPOSE, blocks.get(1).source());
        assertEquals(2, blocks.get(1).entries().size());
        assertEquals(RunLogSource.ASTRA, blocks.get(2).source());
    }

    @Test
    void runLogBlockAccumulatorMergesDelimitedPipelineBlocksIntoOneCardModel() {
        List<RunLogEntry> entries = RunLogParser.parse("""
                Detect cells: ==============================================================================
                Detect cells: DETECT_CELLS COMPLETE
                Detect cells: Cells created     : 125
                Detect cells: Regions processed : 1
                Detect cells: ==============================================================================
                """, RunLogSource.QUPATH, RunLogSeverity.NEUTRAL);

        RunLogBlockAccumulator accumulator = new RunLogBlockAccumulator();
        List<RunLogRenderedBlock> blocks = new ArrayList<>();
        for (RunLogEntry entry : entries) {
            accumulator.accept(entry).ifPresent(blocks::add);
        }

        assertEquals(1, blocks.size());
        assertEquals("DETECT_CELLS COMPLETE", blocks.get(0).title());
        assertTrue(blocks.get(0).keyValues().stream().anyMatch(kv -> kv.key().contains("Cells created") && kv.value().equals("125")));
        assertEquals("125", blocks.get(0).metrics().get("Cells"));
        assertEquals(RunLogKind.SEPARATOR, entries.get(0).kind());
        assertEquals(RunLogSource.ASTRA, entries.get(0).source());
    }

    @Test
    void runTimelineSeedsAnalysisModesOnlyWhenSelected() {
        RunTimelineModel colocalization = new RunTimelineModel();
        colocalization.start("Colocalization", """
                final String PIPELINE_ID = "colocalization"
                final Map USER_OVERRIDES = [
                        SCRIPT_ACTION: "RUN",
                        MODES_TO_RUN: ["DETECT_CELLS", "QUANTIFY"]
                ]
                """);

        List<String> labels = colocalization.labelsForTest();
        assertTrue(labels.contains("Run Started"));
        assertTrue(labels.contains("Detect Cells"));
        assertTrue(labels.contains("Quantify"));
        assertTrue(labels.contains("Complete"));
        assertFalse(labels.contains("Generate Regions"));
        assertFalse(labels.contains("Auto Build Classifiers"));

        RunTimelineModel vascular = new RunTimelineModel();
        vascular.start("Vascular", """
                final String PIPELINE_ID = "vascular"
                final Map USER_OVERRIDES = [
                        SCRIPT_ACTION: "RUN",
                        MODES_TO_RUN: ["QUANTIFY"]
                ]
                """);

        List<String> vascularLabels = vascular.labelsForTest();
        assertTrue(vascularLabels.contains("Quantify"));
        assertFalse(vascularLabels.contains("Detect Cells"));
        assertFalse(vascularLabels.contains("Generate Regions"));
        assertFalse(vascularLabels.contains("Auto Select Rois"));
    }

    @Test
    void runTimelineScriptActionsReplaceNormalAnalysisModeTimeline() {
        RunTimelineModel export = new RunTimelineModel();
        export.start("Colocalization", """
                final String PIPELINE_ID = "colocalization"
                final Map USER_OVERRIDES = [
                        SCRIPT_ACTION: "EXPORT",
                        MODES_TO_RUN: ["DETECT_CELLS", "QUANTIFY"]
                ]
                """);

        assertTrue(export.labelsForTest().contains("Export"));
        assertFalse(export.labelsForTest().contains("Detect Cells"));
        assertFalse(export.labelsForTest().contains("Quantify"));

        RunTimelineModel reset = new RunTimelineModel();
        reset.start("Vascular", """
                final String PIPELINE_ID = "vascular"
                final Map USER_OVERRIDES = [
                        SCRIPT_ACTION: "RESET_PROJECT",
                        MODES_TO_RUN: ["GENERATE_REGIONS", "DETECT_CELLS", "QUANTIFY"]
                ]
                """);

        assertTrue(reset.labelsForTest().contains("Reset Project"));
        assertFalse(reset.labelsForTest().contains("Generate Regions"));
        assertFalse(reset.labelsForTest().contains("Detect Cells"));
        assertFalse(reset.labelsForTest().contains("Quantify"));
    }

    @Test
    void runTimelineEventsUpdateWarningsErrorsAndOutcome() {
        RunTimelineModel model = new RunTimelineModel();
        model.start("Colocalization", """
                final String PIPELINE_ID = "colocalization"
                final Map USER_OVERRIDES = [SCRIPT_ACTION: "RUN", MODES_TO_RUN: ["DETECT_CELLS", "QUANTIFY"]]
                """);

        RunLogEntry warning = new RunLogEntry(RunLogSource.CELLPOSE, RunLogSeverity.WARNING, RunLogKind.MESSAGE, "the '--diam_mean' flag is deprecated", "");
        model.accept(RunLogPresenter.eventFor(warning));
        assertEquals(1, model.warningCount());
        assertEquals(RunTimelineOutcome.RUNNING, model.outcome());

        RunLogEntry error = new RunLogEntry(RunLogSource.ASTRA, RunLogSeverity.ERROR, RunLogKind.MESSAGE, "Quantify: failed", "");
        model.accept(RunLogPresenter.eventFor(error));
        assertEquals(1, model.errorCount());
        assertEquals(RunTimelineOutcome.FAILED, model.outcome());
        assertTrue(model.statusTitle().contains("Failed"));
        assertTrue(model.elapsedLabel().startsWith("elapsed "));
        assertTrue(model.statusDetail("Image 3/20 | Cells 125").contains("Image 3/20"));
    }

    @Test
    void runLogPresenterRecognizesCardsCommandsAndMetrics() {
        RunLogEntry summary = new RunLogEntry(RunLogSource.ASTRA, RunLogSeverity.SUCCESS, RunLogKind.MESSAGE, "DETECT_CELLS COMPLETE", "");
        assertTrue(RunLogPresenter.isStageCard(summary));
        assertEquals(RunLogEventType.STAGE_COMPLETE, RunLogPresenter.eventFor(summary).type());

        RunLogEntry kv = new RunLogEntry(RunLogSource.ASTRA, RunLogSeverity.INFO, RunLogKind.KEY_VALUE, "Cells quantified : 78", "");
        assertEquals("78", RunLogMetrics.badges(kv).get("Cells"));

        RunLogEntry command = new RunLogEntry(RunLogSource.SCRIPT, RunLogSeverity.NEUTRAL, RunLogKind.MESSAGE, "bash -c \"/env/bin/python -m cellpose --dir /tmp\"", "");
        assertTrue(RunLogPresenter.isCommand(command));
        RunLogEntry version = new RunLogEntry(RunLogSource.CELLPOSE, RunLogSeverity.INFO, RunLogKind.MESSAGE, "cellpose version: 4.0.8+astra.2", "");
        assertFalse(RunLogPresenter.isCommand(version));

        RunLogEntry progress = new RunLogEntry(RunLogSource.CELLPOSE, RunLogSeverity.INFO, RunLogKind.PROGRESS, "100%|##########| 1/1 [00:19<00:00, 19.22s/it]", "");
        assertTrue(RunLogPresenter.isNoisyCellposeDetail(progress));
    }

    @Test
    void runProgressTrackerSummarizesImageRegionCellposeAndCellCounts() {
        RunProgressTracker tracker = new RunProgressTracker();
        RunLogEntry image = new RunLogEntry(RunLogSource.ASTRA, RunLogSeverity.INFO, RunLogKind.MESSAGE,
                "Image start : [3/20] 'slide'", "");
        tracker.accept(RunLogPresenter.eventFor(image));

        RunLogEntry region = new RunLogEntry(RunLogSource.ASTRA, RunLogSeverity.INFO, RunLogKind.MESSAGE,
                "Region detected: [2/5] 'Region' | nuclei=78 | cells=78", "");
        tracker.accept(RunLogPresenter.eventFor(region));

        RunLogEntry progress = new RunLogEntry(RunLogSource.CELLPOSE, RunLogSeverity.INFO, RunLogKind.PROGRESS,
                "75%|#######5  | 3/4 [00:38<00:12, 12.72s/it]", "");
        tracker.accept(RunLogPresenter.eventFor(progress));

        RunLogEntry cells = new RunLogEntry(RunLogSource.ASTRA, RunLogSeverity.INFO, RunLogKind.KEY_VALUE,
                "Cells quantified : 78", "");
        tracker.accept(RunLogPresenter.eventFor(cells));

        String detail = tracker.detail();
        assertTrue(detail.contains("Image 3/20"));
        assertTrue(detail.contains("Region 2/5"));
        assertTrue(detail.contains("Cellpose 75% (3/4)"));
        assertTrue(detail.contains("Cells 78"));
    }

    @Test
    void runLogErrorAdvisorMapsKnownFailuresConservatively() {
        RunLogEntry finite = new RunLogEntry(RunLogSource.ASTRA, RunLogSeverity.ERROR, RunLogKind.MESSAGE,
                "Quantify: resolved threshold for AF647|Nucleus must be finite.", "");
        RunLogErrorAdvice finiteAdvice = RunLogErrorAdvisor.advise(RunLogPresenter.eventFor(finite));
        assertEquals("Non-finite measurement or threshold", finiteAdvice.family());
        assertTrue(finiteAdvice.nextAction().contains("finite measurements"));

        RunLogEntry missingClass = new RunLogEntry(RunLogSource.SCRIPT, RunLogSeverity.ERROR, RunLogKind.MESSAGE,
                "Unable to resolve class JsonSlurper", "");
        assertEquals("Missing runtime class/property", RunLogErrorAdvisor.advise(RunLogPresenter.eventFor(missingClass)).family());

        RunLogEntry unknown = new RunLogEntry(RunLogSource.QUPATH, RunLogSeverity.ERROR, RunLogKind.MESSAGE,
                "Unexpected failure in QuPathScript", "");
        RunLogErrorAdvice unknownAdvice = RunLogErrorAdvisor.advise(RunLogPresenter.eventFor(unknown));
        assertEquals("Run failed", unknownAdvice.family());
        assertTrue(unknownAdvice.nextAction().contains("nearby log context"));
    }

    @Test
    void launcherSourceAvoidsGuiLogAndErrDoublePrefixes() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));
        String view = Files.readString(Path.of("src/main/java/qupath/ext/astra/StyledLogView.java"));

        assertTrue(source.contains("static String formatGuiLogText(String text)"));
        assertTrue(source.contains("RunLogParser.formatCleanText"));
        assertTrue(source.contains("output.appendText(text, RunLogSource.QUPATH"));
        assertTrue(source.contains("feedback.appendScriptText(text, error);"));
        assertTrue(view.contains("copy.setText(\"Copied\")"));
        assertTrue(view.contains("new PauseTransition(Duration.seconds(1.2))"));
        assertTrue(view.contains("copy.setStyle(copiedLogButtonStyle())"));
        assertTrue(view.contains("startSourceGroup(entry.source())"));
        assertTrue(view.contains("sourceTabStyle(source)"));
        assertTrue(view.contains("beginRun(String scriptName, String configuredScript)"));
        assertTrue(view.contains("RunLogPresenter.eventFor(entry)"));
        assertTrue(view.contains("RunLogBlockAccumulator"));
        assertTrue(view.contains("RunLogErrorAdvisor.advise"));
        assertTrue(view.contains("appendProgressLine(entry)"));
        assertTrue(view.contains("refreshTimelineElapsed()"));
        assertTrue(source.contains("new Timeline(new KeyFrame(Duration.seconds(1.0)"));
        assertTrue(source.contains("elapsedHeartbeat.playFromStart()"));
        assertTrue(source.contains("elapsedHeartbeat.stop()"));
        assertFalse(source.contains("append(\"[LOG] \" + text)"));
        assertFalse(source.contains("feedback.append(\"[ERR] \" + text)"));
    }

    @Test
    void cancellationMessageDocumentsNativeProcessLimitationAndAvoidsSuccess() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertTrue(source.contains("Cancellation requested. Java/Groovy task interruption was requested."));
        assertTrue(source.contains("Native Cellpose process may continue until the current operation exits."));
        assertTrue(source.contains("status.setText(\"Run cancelled.\")"));
        assertFalse(source.contains("Cancellation requested.") && source.contains("[DONE] Cancellation"));
    }

    @Test
    void logCaptureTextDocumentsCellposeSubprocessRoute() throws Exception {
        String launcher = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));
        String runner = Files.readString(Path.of("src/main/java/qupath/ext/biop/cmd/VirtualEnvironmentRunner.java"));

        assertTrue(launcher.contains("Cellpose subprocess stdout/stderr is captured when it is emitted through QuPath logging."));
        assertTrue(runner.contains("redirectErrorStream(true)"));
        assertTrue(runner.contains("logger.info(\"{}: {}\", name, line);"));
    }

    private static List<PipelineLauncher.EditableConstant> extractModes(String modes) {
        return PipelineLauncher.extractEditableConstants("""
                final List MODES_TO_RUN = %s
                final Map cfg = [:]
                """.formatted(modes));
    }

    private static String realBaseScript(String relativePath) throws Exception {
        Path path = currentBaseScriptPath(relativePath);
        return Files.readString(path);
    }

    private static Path currentBaseScriptPath(String relativePath) {
        Path localPath = LOCAL_BASE_ASTRA_ROOT.resolve(relativePath).normalize();
        if (Files.isRegularFile(localPath)) {
            return localPath;
        }
        Path vendoredPath = VENDORED_BASE_ASTRA_ROOT.resolve(relativePath).normalize();
        if (Files.isRegularFile(vendoredPath)) {
            return vendoredPath;
        }
        assumeTrue(false, "Skipping private source-script contract. Checked local source "
                + localPath + " and vendored release resource " + vendoredPath + ".");
        return localPath;
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
                        CHANNELS   : ["DAPI", "AF488"],
                        EXCLUDED_CHANNELS: ["DAPI"]
                    ]
                ]
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
