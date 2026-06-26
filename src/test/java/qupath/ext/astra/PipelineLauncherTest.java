package qupath.ext.astra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

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
        assertEquals("\"BALANCED\"", preset.currentDisplayValue());
        assertEquals("\"BALANCED\"", preset.defaultDisplayValue());
        assertFalse(preset.detailsText().contains("CellposeInferenceDefaults.SEGMENTATION_PRESET_BALANCED"));
        assertTrue(preset.detailsText().contains("Default value: Balanced."));
    }

    @Test
    void cellposePresetOptionsRenderAsProfessionalLabels() {
        assertEquals("Balanced", GuiPresentation.displayOption("SEGMENTATION_PRESET", "BALANCED"));
        assertEquals("Strict", GuiPresentation.displayOption("SEGMENTATION_PRESET", "STRICT"));
        assertEquals("Sensitive", GuiPresentation.displayOption("SEGMENTATION_PRESET", "SENSITIVE"));
        assertEquals("Custom", GuiPresentation.displayOption("SEGMENTATION_PRESET", "CUSTOM"));
    }

    @Test
    void cellposePresetOverrideWritesCleanToken() throws Exception {
        String script = realBaseScript("modules/pipelines/analysis/vascular/src/main/groovy/vascular.groovy");
        List<PipelineLauncher.EditableConstant> constants =
                PipelineLauncher.editableConstantsForScript("Vascular", script);

        PipelineLauncher.EditableConstant preset = constants.stream()
                .filter(constant -> constant.name().equals("SEGMENTATION_PRESET"))
                .findFirst()
                .orElseThrow();
        assertEquals("\"BALANCED\"", preset.currentDisplayValue());
        preset.setDisplayValue("\"STRICT\"");

        String rendered = PipelineLauncher.applyConstants(script,
                constants,
                PipelineLauncher.SettingsProfileState.scriptDefaults());

        assertTrue(rendered.contains("SEGMENTATION_PRESET: \"STRICT\""));
        assertFalse(rendered.contains("CellposeInferenceDefaults.SEGMENTATION_PRESET_STRICT"));
    }

    @Test
    void settingsProfileNameDialogUsesAstraOwnedGeometry() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertFalse(source.contains("TextInputDialog"));
        assertTrue(source.contains("static Dialog<String> createSettingsProfileNameDialog(Window owner)"));
        assertTrue(source.contains("VBox content = new VBox(SelectionGeometry.LABEL_TO_LIST_GAP, title, field);"));
        assertTrue(source.contains("content.setPadding(new Insets(SelectionGeometry.DIALOG_CONTENT_INSET));"));
        assertTrue(source.contains("addStyleClass(field, \"astra-input\")"));
        assertTrue(source.contains("addStyleClass(title, \"astra-dialog-section-title\")"));
    }

    @Test
    void confirmationDialogsUseAstraOwnedGeometry() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));
        String runtime = Files.readString(Path.of("src/main/java/qupath/ext/astra/RuntimeInstaller.java"));

        assertFalse(source.contains("new Alert"));
        assertFalse(source.contains("Dialogs.showErrorMessage"));
        assertFalse(source.contains("Dialogs.showInfoNotification"));
        assertFalse(runtime.contains("qupath.fx.dialogs.Dialogs"));
        assertFalse(runtime.contains("Dialogs.show"));
        assertTrue(source.contains("static Dialog<ButtonType> createResetConfirmationDialog(Window owner)"));
        assertTrue(source.contains("static Dialog<ButtonType> createProvisionalVascularConfirmationDialog(Window owner)"));
        assertTrue(source.contains("static Dialog<ButtonType> createAstraSuccessConfirmationDialog(Window owner"));
        assertTrue(source.contains("static void showAstraMessage(Window owner"));
        assertTrue(source.contains("static Dialog<ButtonType> createRunFailureDialog(Window owner"));
        assertTrue(source.contains("private static Dialog<ButtonType> createAstraConfirmationDialog"));
        assertTrue(source.contains("private static Dialog<ButtonType> createAstraMessageDialog"));
        assertTrue(source.contains("VBox content = new VBox(SelectionGeometry.DIALOG_CONTENT_GAP, title, body);"));
        assertTrue(source.contains("addStyleClass(content, \"astra-dialog-owned-content\")"));
        assertTrue(runtime.contains("PipelineLauncher.createAstraSuccessConfirmationDialog"));
        assertTrue(runtime.contains("PipelineLauncher.showAstraMessage"));
        assertTrue(runtime.contains("PipelineLauncher.showAstraErrorMessage"));
        assertTrue(source.contains("Platform.runLater(() -> showAstraMessage("));
    }

    @Test
    void mainLauncherDialogHasCancelClosePath() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertTrue(source.contains("dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);"));
        assertTrue(source.contains("Node nativeCancelClose = dialog.getDialogPane().lookupButton(ButtonType.CANCEL);"));
        assertTrue(source.contains("nativeCancelClose.setManaged(false);"));
        assertTrue(source.contains("nativeCancelClose.setVisible(false);"));
        assertTrue(source.contains("dialog.setResultConverter(button -> button == null ? ButtonType.CANCEL : button);"));
        assertTrue(source.contains("dialog.setOnCloseRequest(event -> dialog.setResult(ButtonType.CANCEL));"));
        assertTrue(source.contains("cancelButton.setOnAction(event -> {"));
        assertTrue(source.contains("dialog.setResult(ButtonType.CANCEL);"));
        assertTrue(source.contains("dialog.close();"));
        assertFalse(source.contains("cancelButton.setOnAction(event -> dialog.close());"));
    }

    @Test
    void launcherUsesStandardGroupOrderAndUiOrder() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertTrue(source.contains("GuiPresentation.standardGroups()"));
        assertTrue(source.contains("GuiPresentation.StandardGroup::name"));
        assertTrue(source.contains("STANDARD_GROUP_RANK.getOrDefault(group, Integer.MAX_VALUE)"));
        assertTrue(source.contains("Comparator.comparingInt(EditableConstant::uiOrder)"));
    }

    @Test
    void launcherSeparatesHoverHelpFromDetailedHelpDialog() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));
        String css = Files.readString(Path.of("src/main/resources/qupath/ext/astra/astra-launcher.css"));

        assertTrue(source.contains("new Tooltip(constant.helpText())"));
        assertTrue(source.contains("info.setOnAction(event -> showParameterHelpDialog(constant))"));
        assertTrue(source.contains("private static final double HELP_DIALOG_INSET =\n"
                + "            LauncherGeometry.INTRA_PANEL_MARGIN;"));
        assertTrue(source.contains("private static final double HELP_DIALOG_SECTION_GAP =\n"
                + "            HELP_DIALOG_INSET;"));
        assertTrue(source.contains("private static final double HELP_SUMMARY_COLUMN_GAP =\n"
                + "            HELP_DIALOG_INSET;"));
        assertTrue(source.contains("private static final double HELP_DETAIL_ACCENT_WIDTH =\n"
                + "            PARAMETER_ANCHOR_WIDTH;"));
        assertTrue(source.contains("private static final double HELP_DETAIL_CARD_INSET =\n"
                + "            HELP_DIALOG_INSET;"));
        assertTrue(source.contains("private static final double HELP_DIALOG_WIDTH =\n"
                + "            LauncherGeometry.LAYOUT_UNIT * 155.0 / 6.0;"));
        assertTrue(source.contains("private static final double HELP_DETAIL_VIEWPORT_HEIGHT =\n"
                + "            LauncherGeometry.LAYOUT_UNIT * 65.0 / 6.0;"));
        assertTrue(source.contains("VBox content = new VBox(HELP_DIALOG_SECTION_GAP);"));
        assertTrue(source.contains("content.setPadding(helpDialogPadding());"));
        assertTrue(source.contains("summary.setHgap(HELP_SUMMARY_COLUMN_GAP);"));
        assertTrue(source.contains("summary.setVgap(HELP_SUMMARY_ROW_GAP);"));
        assertTrue(source.contains("summary.setPadding(helpDialogPadding());"));
        assertTrue(source.contains("createHelpDetailsContent(constant.detailsText())"));
        assertTrue(source.contains("VBox cards = new VBox(HELP_DETAIL_CARD_GAP);"));
        assertTrue(source.contains("cards.setPadding(helpDialogPadding());"));
        assertTrue(source.contains("VBox card = new VBox(HELP_DETAIL_CARD_INTERNAL_GAP);"));
        assertTrue(source.contains("card.setPadding(helpDetailCardPadding());"));
        assertTrue(source.contains("parseHelpDetailSections(detailsText)"));
        assertTrue(source.contains("addStyleClass(card, \"astra-help-detail-card\")"));
        assertTrue(source.contains("addStyleClass(content, \"astra-help-dialog-content\")"));
        assertTrue(source.contains("addStyleClass(title, \"astra-help-title\")"));
        assertTrue(source.contains("Button copyDetails = new Button(\"Copy Details\")"));
        assertTrue(source.contains("addStyleClass(summary, \"astra-help-summary-grid\")"));
        assertTrue(source.contains("addStyleClass(detailShell, \"astra-help-details-shell\")"));
        assertTrue(source.contains("addStyleClass(detailAccent, \"astra-help-details-accent\")"));
        assertTrue(source.contains("addStyleClass(label, \"astra-help-summary-label\")"));
        assertTrue(source.contains("addStyleClass(value, \"astra-help-summary-value\")"));
        assertTrue(source.contains("addHelpSummaryRow(summary, 0, \"Parameter\", constant.name)"));
        assertTrue(source.contains("addHelpSummaryRow(summary, 1, \"Current value\", safeCurrentDisplayValue(constant))"));
        assertTrue(source.contains("addHelpSummaryRow(summary, 2, \"Default value\", safeDefaultDisplayValue(constant))"));
        assertTrue(source.contains("dialog.getDialogPane().setPrefWidth(HELP_DIALOG_WIDTH);"));
        assertTrue(source.contains("scroll.setPrefViewportHeight(HELP_DETAIL_VIEWPORT_HEIGHT);"));
        assertFalse(source.contains("dialog.getDialogPane().setPrefWidth(620.0);"));
        assertFalse(source.contains("scroll.setPrefViewportHeight(260.0);"));
        assertFalse(css.contains(".astra-help-summary-grid {\n    -fx-padding:"));
        assertFalse(css.contains(".astra-help-detail-card {\n    -fx-background-color: #ffffff;\n"
                + "    -fx-border-color: #d2e3e7;\n"
                + "    -fx-background-radius: 6;\n"
                + "    -fx-border-radius: 6;\n"
                + "    -fx-padding:"));
    }

    @Test
    void launcherDefinesCardDashboardAndSharedStylesheet() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));
        String css = Files.readString(Path.of("src/main/resources/qupath/ext/astra/astra-launcher.css"));

        assertTrue(source.contains("LAUNCHER_STYLESHEET_RESOURCE = \"/qupath/ext/astra/astra-launcher.css\""));
        assertTrue(source.contains("installAstraStyles(dialog.getDialogPane())"));
        assertTrue(source.contains("addStyleClass(root, \"astra-launcher-root\")"));
        assertTrue(source.contains("addStyleClass(grid, \"astra-section-content\")"));
        assertTrue(source.contains("addStyleClass(this, \"astra-collapsible-section\")"));
        assertTrue(source.contains("addStyleClass(header, \"astra-collapsible-header\")"));
        assertTrue(source.contains("addStyleClass(chip, \"astra-workflow-chip\")"));
        assertTrue(source.contains("addStyleClass(panel, \"astra-channel-panel\")"));
        assertTrue(source.contains("static Node createChannelPanel(List<ImageChannel> channels)"));
        assertTrue(source.contains("addStyleClass(swatch, \"astra-channel-chip-swatch\")"));
        assertTrue(source.contains("CHANNEL_SWATCH_STROKE_WIDTH ="));
        assertTrue(source.contains("SURFACE_BORDER_WIDTH / 2.0"));
        assertTrue(source.contains("createSettingsCard(section"));
        assertTrue(source.contains("GridPane cards = new GridPane()"));
        assertTrue(source.contains("column.setPercentWidth(100.0d / 3.0d)"));
        assertTrue(source.contains("scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER)"));
        assertFalse(source.contains("COLLAPSIBLE_HEADER_WIDTH_ADJUSTMENT"));
        assertFalse(source.contains("cards.setMinWidth(3 *"));
        assertFalse(source.contains("setPrefTileWidth"));
        assertTrue(source.contains("detachFromParent(section.content())"));
        assertTrue(source.contains("LauncherViewState.load("));
        assertTrue(source.contains("setNodeVisibleManaged(feedbackNode"));
        assertTrue(css.contains(".astra-button-primary"));
        assertTrue(css.contains(".astra-button:hover"));
        assertTrue(css.contains(".astra-launcher-root"));
        assertTrue(css.contains(".astra-section-content"));
        assertTrue(css.contains(".astra-section-content-focused"));
        assertTrue(css.contains(".astra-collapsible-header"));
        assertTrue(css.contains(".astra-workflow-chip"));
        assertTrue(css.contains(".astra-channel-panel"));
        assertTrue(css.contains(".astra-channel-chip"));
        assertTrue(css.contains(".astra-button-toggle-active"));
        assertTrue(css.contains(".astra-button:pressed"));
        assertFalse(css.contains(".astra-button-help:pressed"));
        assertFalse(css.contains(".astra-button-primary:pressed"));
        assertTrue(css.contains(".astra-settings-card:pressed"));
        assertTrue(css.contains(".astra-settings-card-theme-teal"));
        assertTrue(css.contains(".astra-output-pane"));
        assertTrue(css.contains(".astra-animated-gradient-header"));
        assertTrue(css.contains(".astra-log-copy-button"));
        assertTrue(css.contains(".astra-log-disclosure-button"));
        assertTrue(css.contains(".astra-log-scroll-frame"));
        assertTrue(css.contains(".astra-log-scroll-top-fade"));
        assertTrue(css.contains(".astra-log-card-title"));
        assertTrue(css.contains(".astra-log-key-value-card"));
        assertTrue(css.contains(".astra-log-command-card"));
        assertTrue(css.contains(".astra-log-metric-badge"));
        assertTrue(css.contains(".astra-log-timeline-label"));
        assertTrue(css.contains(".astra-log-failure-title"));
        assertTrue(css.contains(".scroll-pane .scroll-bar .thumb"));
        assertTrue(css.contains(".combo-box-popup .list-view"));
        assertTrue(css.contains(".context-menu"));
        assertTrue(css.contains(".tooltip"));
        assertTrue(css.contains(".astra-header-menu-button"));
        assertTrue(css.contains(".astra-header-menu-label"));
        assertTrue(css.contains(".astra-header-menu-chevron"));
        assertTrue(css.contains(".astra-header-action-rail-tab"));
        assertTrue(css.contains(".astra-header-context-menu"));
        assertTrue(css.contains(".astra-combo-cell"));
        assertTrue(css.contains(".astra-list-cell"));
        assertTrue(css.contains(".astra-help-dialog-content"));
        assertTrue(css.contains(".astra-help-details-shell"));
        assertTrue(css.contains(".astra-help-details-accent"));
        assertTrue(css.contains(".astra-semantic-card"));
        assertTrue(css.contains(".astra-dependent-panel-disabled"));
        assertTrue(css.contains(".astra-dependent-panel-failure-recovery"));
        assertTrue(css.contains(".astra-dependent-panel-selected-images"));
        assertTrue(css.contains(".astra-output-status-warning"));
        assertTrue(css.contains(".astra-settings-view-toggle"));
        assertTrue(css.contains(".astra-focused-section-header"));
        assertTrue(css.contains(".astra-focused-section-theme-teal"));
        assertTrue(source.contains("addStyleClass(top, \"astra-focused-section-theme-\" + cssToken(group.accentTheme()))"));
        assertFalse(css.contains(".astra-log-view {\n"
                + "    -fx-background-color: #061720;\n"
                + "    -fx-border-color: #355b69;\n"
                + "    -fx-border-radius: 6;\n"
                + "    -fx-background-radius: 6;\n"
                + "    -fx-padding:"));
        assertFalse(css.contains(".astra-nested-panel {\n"
                + "    -fx-background-color: #ffffff;\n"
                + "    -fx-border-color: #d7e2e6;\n"
                + "    -fx-border-radius: 6;\n"
                + "    -fx-background-radius: 6;\n"
                + "    -fx-padding:"));
        assertFalse(css.contains(".astra-header-options-group {\n"
                + "    -fx-background-color: #f7fbfc;\n"
                + "    -fx-background-radius: 6;\n"
                + "    -fx-border-color: #d2e3e7;\n"
                + "    -fx-border-radius: 6;\n"
                + "    -fx-padding:"));
        assertTrue(css.contains("-fx-pref-width: 24px;"));
        assertFalse(css.contains("-fx-pref-width: 28px;"));
        assertTrue(css.contains(".astra-settings-scroll .scroll-bar:vertical:disabled"));
        assertTrue(css.contains(".astra-settings-scroll .scroll-bar:vertical:disabled .thumb"));
        assertTrue(css.contains(".astra-settings-scroll .scroll-bar:horizontal"));
        assertFalse(css.contains(".astra-dependent-row-failure-recovery"));
        assertFalse(css.contains(".astra-parameter-row-even"));
        assertFalse(css.contains(".astra-parameter-row-odd"));
        assertFalse(source.contains("header.setStyle(\"-fx-background-color: #173747"));
        assertFalse(source.contains("private static String nestedLabelStyle()"));
        assertFalse(source.contains("private static String checkBoxStyle()"));
        assertFalse(source.contains("title + \" v\""));
        assertTrue(source.contains("private static final String CHEVRON_DOWN"));
        assertTrue(source.contains("addStyleClass(chevron, \"astra-header-menu-chevron\")"));
        assertTrue(css.contains("-fx-background-insets: 0;"));
        assertTrue(css.contains(".astra-button:hover"));
        assertTrue(css.contains("-fx-effect: null;"));
        assertFalse(css.contains("-fx-translate-y: 1;"));
        assertFalse(css.contains("dropshadow(gaussian, rgba(10, 47, 56, 0.14"));
        assertFalse(css.contains(".astra-button-primary:hover"));
        assertFalse(css.contains(".astra-button-secondary:hover"));
        assertFalse(css.contains(".astra-button-danger:hover"));
        assertFalse(css.contains(".astra-button-success:hover"));
        assertFalse(css.contains(".astra-button-small:hover"));
        assertFalse(css.contains(".astra-button-help:hover"));
        assertFalse(css.contains(".astra-button-primary:pressed"));
        assertFalse(css.contains(".astra-button-secondary:pressed"));
        assertFalse(css.contains(".astra-button-danger:pressed"));
        assertFalse(css.contains(".astra-button-success:pressed"));
        assertFalse(css.contains(".astra-button-small:pressed"));
        assertFalse(css.contains(".astra-button-help:pressed"));
        assertFalse(css.contains(".astra-dialog-pane .button:hover"));
        assertFalse(css.contains(".astra-log-copy-button:hover"));
        String animatedHeader = Files.readString(Path.of("src/main/java/qupath/ext/astra/AnimatedGradientHeader.java"));
        assertTrue(animatedHeader.contains("getStyleClass().add(\"astra-animated-gradient-header\")"));
        assertFalse(animatedHeader.contains("setStyle(\"-fx-background-color: #0d2430"));
        assertTrue(source.contains("private static List<String> resolveContractOptions(String expr)"));
        assertTrue(source.contains("List.of(\"BALANCED\", \"STRICT\", \"SENSITIVE\", \"CUSTOM\")"));
    }

    @Test
    void residualGuiMarginAuditClassifiesKnownResidualSurfaces() throws Exception {
        Path auditPath = Path.of("docs/gui-residual-margin-audit.csv");
        if (!Files.exists(auditPath)) {
            return;
        }
        String residualAudit = Files.readString(auditPath);

        assertTrue(residualAudit.contains("settings profile load FileChooser"));
        assertTrue(residualAudit.contains("run-complete dialog"));
        assertTrue(residualAudit.contains("runtime setup confirmation"));
        assertTrue(residualAudit.contains("bottom run progress lane"));
        assertTrue(residualAudit.contains("header action tooltips"));
        assertTrue(residualAudit.contains("parameter help tooltips"));
        assertTrue(residualAudit.contains("dependent reason tooltips"));
        assertTrue(residualAudit.contains("tooltip popup preview geometry"));
        assertTrue(residualAudit.contains("RuntimeInstaller progress window"));
        assertTrue(residualAudit.contains("RuntimeInstaller progress preview geometry"));
        assertTrue(residualAudit.contains("AnimatedGradientHeader visual geometry"));
        assertTrue(residualAudit.contains("ASTRA launcher CSS layout literals"));
        assertFalse(residualAudit.contains(",violation,"));
    }

    @Test
    void cssLayoutLiteralsHaveResidualProvenanceRows() throws Exception {
        Path provenancePath = Path.of("docs/gui-css-layout-literals.csv");
        if (!Files.exists(provenancePath)) {
            return;
        }
        String css = Files.readString(Path.of("src/main/resources/qupath/ext/astra/astra-launcher.css"));
        List<String> provenanceRows = Files.readAllLines(provenancePath);
        Pattern layoutDeclaration = Pattern.compile(
                "-fx-(padding|spacing|border-radius|background-radius|min-width|pref-width|max-width|min-height|pref-height|max-height|translate-[xy]|background-insets|border-insets):\\s*([^;]+);");
        long cssDeclarationCount = css.lines()
                .filter(line -> layoutDeclaration.matcher(line).find())
                .count();

        assertEquals(cssDeclarationCount, provenanceRows.size() - 1);
        assertTrue(provenanceRows.get(0).contains("classification"));
        assertFalse(String.join("\n", provenanceRows).contains(",violation,"));
    }

    @Test
    void runtimeInstallerProgressWindowUsesNamedGeometryTokens() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/RuntimeInstaller.java"));

        assertTrue(source.contains("private static final class InstallerGeometry"));
        assertTrue(source.contains("LauncherGeometryTokens.INTRA_PANEL_MARGIN"));
        assertTrue(source.contains("LauncherGeometryTokens.LAYOUT_UNIT * 65.0 / 2.0"));
        assertTrue(source.contains("LauncherGeometryTokens.LAYOUT_UNIT * 35.0 / 2.0"));
        assertTrue(source.contains("static VBox createInstallProgressRootForTesting()"));
        assertTrue(source.contains("createInstallProgressRoot(indicator, step, cancel, log)"));
        assertTrue(source.contains("addAstraStylesheet(scene);"));
        assertFalse(source.contains("new HBox(10"));
        assertFalse(source.contains("new VBox(10"));
        assertFalse(source.contains("new Insets(12"));
        assertFalse(source.contains("new Scene(root, 780, 420"));
    }

    @Test
    void tooltipGeometryUsesSharedTokensAndPreviewMode() throws Exception {
        String tokens = Files.readString(Path.of("src/main/java/qupath/ext/astra/LauncherGeometryTokens.java"));
        String css = Files.readString(Path.of("src/main/resources/qupath/ext/astra/astra-launcher.css"));
        String preview = Files.readString(Path.of("src/test/java/qupath/ext/astra/LauncherPreviewApp.java"));

        assertTrue(tokens.contains("TOOLTIP_VERTICAL_INSET = INTRA_PANEL_TIGHT_GAP"));
        assertTrue(tokens.contains("TOOLTIP_HORIZONTAL_INSET = INTRA_PANEL_SUBTLE_GAP"));
        assertTrue(css.contains("Mirrors LauncherGeometryTokens tooltip inset formulas"));
        assertTrue(css.contains("-fx-padding: 4px 8px 4px 8px;"));
        assertTrue(preview.contains("\"tooltip-geometry\".equals(snapshotMode)"));
        assertTrue(preview.contains("Surface.TOOLTIP"));
        assertTrue(preview.contains("addTooltipMeasurements(sceneRoot, measurements)"));
    }

    @Test
    void runtimeInstallerGeometryHasPreviewMode() throws Exception {
        String preview = Files.readString(Path.of("src/test/java/qupath/ext/astra/LauncherPreviewApp.java"));

        assertTrue(preview.contains("\"runtime-installer-geometry\".equals(snapshotMode)"));
        assertTrue(preview.contains("Surface.RUNTIME_INSTALLER"));
        assertTrue(preview.contains("addRuntimeInstallerMeasurements(sceneRoot, measurements)"));
        assertTrue(preview.contains("RuntimeInstaller.createInstallProgressRootForTesting()"));
    }

    @Test
    void animatedGradientHeaderClipRadiusUsesSharedGeometryToken() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/AnimatedGradientHeader.java"));

        assertTrue(source.contains("HEADER_CLIP_ARC =\n            LauncherGeometryTokens.INTRA_PANEL_MARGIN"));
        assertTrue(source.contains("clip.setArcWidth(HEADER_CLIP_ARC);"));
        assertTrue(source.contains("clip.setArcHeight(HEADER_CLIP_ARC);"));
        assertFalse(source.contains("clip.setArcWidth(12.0d);"));
        assertFalse(source.contains("clip.setArcHeight(12.0d);"));
    }

    @Test
    void headerMenuPlacementDefaultsLeftAndRightAlignsOnlyOnOverflow() {
        double margin = PipelineLauncher.headerMenuEdgeMarginForTesting();
        assertEquals(720.0,
                PipelineLauncher.preferredHeaderMenuX(0.0, 1000.0, 720.0, 802.0, 260.0, margin),
                0.0001);
        assertEquals(650.0,
                PipelineLauncher.preferredHeaderMenuX(0.0, 1000.0, 900.0, 980.0, 330.0, margin),
                0.0001);
        assertEquals(margin,
                PipelineLauncher.preferredHeaderMenuX(0.0, 180.0, 80.0, 160.0, 330.0, margin),
                0.0001);
    }

    @Test
    void helpDetailsParseIntoSectionCardsAndPreserveFallback() {
        List<PipelineLauncher.HelpDetailSection> parsed = PipelineLauncher.parseHelpDetailSections("""
                What it does: Controls image scope.

                When to change: Use project batches for audited runs.

                Default value: "CURRENT_IMAGE".
                """);

        assertEquals(3, parsed.size());
        assertEquals("What it does", parsed.get(0).title());
        assertEquals("Controls image scope.", parsed.get(0).body());
        assertEquals("When to change", parsed.get(1).title());
        assertEquals("Default value", parsed.get(2).title());
        assertTrue(PipelineLauncher.parseHelpDetailSections("Plain detail body.").isEmpty());
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
        String sharedGeometry = Files.readString(Path.of("src/main/java/qupath/ext/astra/LauncherGeometryTokens.java"));
        String css = Files.readString(Path.of("src/main/resources/qupath/ext/astra/astra-launcher.css"));

        assertTrue(sharedGeometry.contains("static final double LAYOUT_UNIT = 24.0;"));
        assertTrue(sharedGeometry.contains("static final double OUTER_MARGIN = LAYOUT_UNIT;"));
        assertTrue(sharedGeometry.contains("static final double INTRA_PANEL_MARGIN = OUTER_MARGIN / 2.0;"));
        assertTrue(sharedGeometry.contains("static final double INTRA_PANEL_TIGHT_GAP = INTRA_PANEL_MARGIN / 3.0;"));
        assertTrue(sharedGeometry.contains("static final double INTRA_PANEL_SUBTLE_GAP = INTRA_PANEL_MARGIN * 2.0 / 3.0;"));
        assertTrue(sharedGeometry.contains("static final double SURFACE_BORDER_WIDTH = LAYOUT_UNIT / 24.0;"));
        assertTrue(source.contains("private static final class LauncherGeometry"));
        assertTrue(source.contains("private static final double LAYOUT_UNIT = LauncherGeometryTokens.LAYOUT_UNIT;"));
        assertTrue(source.contains("private static final double OUTER_MARGIN = LauncherGeometryTokens.OUTER_MARGIN;"));
        assertTrue(source.contains("private static final double INPUT_STACK_GAP = OUTER_MARGIN;"));
        assertTrue(source.contains("private static final double INTRA_PANEL_MARGIN = LauncherGeometryTokens.INTRA_PANEL_MARGIN;"));
        assertTrue(source.contains("private static final double INTRA_PANEL_TIGHT_GAP = LauncherGeometryTokens.INTRA_PANEL_TIGHT_GAP;"));
        assertTrue(source.contains("private static final double INTRA_PANEL_SUBTLE_GAP = LauncherGeometryTokens.INTRA_PANEL_SUBTLE_GAP;"));
        assertTrue(source.contains("private static Insets intraPanelPadding()"));
        assertTrue(source.contains("return LauncherGeometryTokens.intraPanelPadding();"));
        assertTrue(source.contains("private static final double SCROLLBAR_GUTTER_WIDTH = OUTER_MARGIN;"));
        assertTrue(source.contains("private static final double SCROLLBAR_THUMB_WIDTH = SCROLLBAR_GUTTER_WIDTH / 3.0;"));
        assertTrue(source.contains("(SCROLLBAR_GUTTER_WIDTH - SCROLLBAR_THUMB_WIDTH) / 2.0"));
        assertTrue(source.contains("private static final double INPUT_CONTENT_TO_BAR_GAP =\n                OUTER_MARGIN - SCROLLBAR_SIDE_PADDING;"));
        assertTrue(source.contains("private static final double INTER_PANE_GAP =\n                OUTER_MARGIN - SCROLLBAR_SIDE_PADDING;"));
        assertFalse(source.contains("BUTTON_BAR_INTERNAL_VERTICAL_OFFSET"));
        assertFalse(source.contains("MAIN_ACTION_BAR_HEIGHT"));
        assertFalse(source.contains("MAIN_ACTION_BAR_NATURAL_TOP_GAP"));
        assertFalse(source.contains("MAIN_ACTION_BAR_TOP_PADDING"));
        assertTrue(source.contains("private static final double PARAMETER_ROW_HEIGHT =\n"
                + "            LauncherGeometry.LAYOUT_UNIT\n"
                + "                    + LauncherGeometry.INTRA_PANEL_SUBTLE_GAP\n"
                + "                    + (LauncherGeometryTokens.SURFACE_BORDER_WIDTH * 2.0);"));
        assertTrue(source.contains("private static final double PARAMETER_ROW_GAP =\n"
                + "            LauncherGeometry.INTRA_PANEL_SUBTLE_GAP;"));
        assertTrue(source.contains("private static final double PARAMETER_LABEL_COLUMN_WIDTH =\n"
                + "            (LauncherGeometry.LAYOUT_UNIT * 12.0)\n"
                + "                    + LauncherGeometry.INTRA_PANEL_TIGHT_GAP;"));
        assertTrue(source.contains("private static final double PARAMETER_HELP_COLUMN_WIDTH =\n"
                + "            LauncherGeometry.LAYOUT_UNIT\n"
                + "                    - (BORDER_WIDTH * 2.0);"));
        assertTrue(source.contains("private static final double PARAMETER_ANCHOR_WIDTH =\n"
                + "            LauncherGeometry.LAYOUT_UNIT / 4.0;"));
        assertTrue(source.contains("private static final double PARAMETER_ANCHOR_COLUMN_WIDTH =\n"
                + "            PARAMETER_ANCHOR_WIDTH;"));
        assertTrue(source.contains("private static final double PARAMETER_FIRST_ROW_HEIGHT =\n"
                + "            PARAMETER_ROW_HEIGHT;"));
        assertTrue(source.contains("private static final double PARAMETER_ANCHOR_HEIGHT =\n"
                + "            PARAMETER_FIRST_ROW_HEIGHT;"));
        assertTrue(source.contains("private static final double PARAMETER_ANCHOR_PAINT_RADIUS =\n"
                + "            Math.min(PARAMETER_ANCHOR_WIDTH, PARAMETER_FIRST_ROW_HEIGHT) / 2.0;"));
        assertTrue(source.contains("private static final double PARAMETER_ANCHOR_PAINT_ARC =\n"
                + "            PARAMETER_ANCHOR_PAINT_RADIUS * 2.0;"));
        assertTrue(source.contains("private static final double PARAMETER_HELP_BUTTON_SIZE =\n"
                + "            PARAMETER_HELP_COLUMN_WIDTH\n"
                + "                    - LauncherGeometry.INTRA_PANEL_TIGHT_GAP;"));
        assertTrue(source.contains("private static final double PARAMETER_ROW_HORIZONTAL_PADDING =\n"
                + "            LauncherGeometry.FLUSH;"));
        assertTrue(source.contains("private static final double PARAMETER_ROW_VERTICAL_PADDING =\n"
                + "            LauncherGeometry.FLUSH;"));
        assertTrue(source.contains("private static final double SURFACE_BORDER_WIDTH =\n"
                + "            LauncherGeometryTokens.SURFACE_BORDER_WIDTH;"));
        assertTrue(source.contains("private static final double PARAMETER_ROW_CONTAINER_LEFT_INSET =\n"
                + "            LauncherGeometry.INTRA_PANEL_MARGIN - PARAMETER_ROW_HORIZONTAL_PADDING;"));
        assertTrue(source.contains("private static final double PARAMETER_ROW_EDGE_TO_BAR_GAP =\n"
                + "            PARAMETER_ROW_HORIZONTAL_PADDING;"));
        assertTrue(source.contains("private static final double PARAMETER_BAR_TO_TEXT_GAP =\n"
                + "            ACCENT_INDENT - BAR_WIDTH;"));
        assertTrue(source.contains("private static final double PARAMETER_LABEL_COLUMN_GAP =\n"
                + "            PARAMETER_BAR_TO_TEXT_GAP;"));
        assertTrue(source.contains("private static final double PARAMETER_ANCHOR_COLUMN_WIDTH =\n"
                + "            PARAMETER_ANCHOR_WIDTH;"));
        assertTrue(source.contains("private static final double PARAMETER_ROW_TEXT_RAIL =\n"
                + "            PARAMETER_ROW_EDGE_TO_BAR_GAP\n"
                + "                    + PARAMETER_ANCHOR_WIDTH\n"
                + "                    + PARAMETER_BAR_TO_TEXT_GAP;"));
        assertTrue(source.contains("private static final double PARAMETER_GRID_TEXT_RAIL =\n"
                + "            LauncherGeometry.INTRA_PANEL_MARGIN + BORDER_WIDTH + PARAMETER_ROW_TEXT_RAIL;"));
        assertTrue(source.contains("private static final double DEPENDENT_PANEL_LEFT_INSET =\n"
                + "            ACCENT_INDENT - (BORDER_WIDTH * 2.0) - PARAMETER_ROW_EDGE_TO_BAR_GAP;"));
        assertTrue(source.contains("private static final double DEPENDENT_ROWS_LEFT_INSET =\n"
                + "            DEPENDENT_PANEL_LEFT_INSET + BORDER_WIDTH;"));
        assertTrue(source.contains("private static final double DEPENDENT_PANEL_OUTER_LEFT_MARGIN =\n"
                + "            LauncherGeometry.INTRA_PANEL_MARGIN - PARAMETER_ROW_CONTAINER_LEFT_INSET;"));
        assertTrue(source.contains("private static final double DEPENDENT_PANEL_RIGHT_PADDING =\n"
                + "            LauncherGeometry.INTRA_PANEL_MARGIN;"));
        assertTrue(source.contains("private static final double DEPENDENT_LABEL_COLUMN_GAP =\n"
                + "            PARAMETER_BAR_TO_TEXT_GAP;"));
        assertTrue(source.contains("private static final double DEPENDENT_TITLE_TEXT_INSET =\n"
                + "            ACCENT_INDENT - BORDER_WIDTH;"));
        assertTrue(source.contains("private static final double DEPENDENT_LABEL_COLUMN_WIDTH =\n"
                + "            PARAMETER_LABEL_COLUMN_WIDTH - ACCENT_INDENT;"));
        assertTrue(source.contains("private static Insets parameterGridPadding()"));
        assertTrue(source.contains("LauncherGeometry.INTRA_PANEL_MARGIN);"));
        assertTrue(source.contains("private static Insets mainActionBarPadding()"));
        assertTrue(source.contains("LauncherGeometry.FLUSH,\n                LauncherGeometry.OUTER_MARGIN,\n                LauncherGeometry.OUTER_MARGIN,\n                LauncherGeometry.OUTER_MARGIN);"));
        assertTrue(source.contains("private static Insets parameterRowPadding()"));
        assertTrue(source.contains("private static Insets dependentPanelPadding()"));
        assertTrue(source.contains("private static Insets dependentRowsPadding()"));
        assertTrue(source.contains("private static Insets dependentTitlePadding()"));
        assertTrue(source.contains("private static Insets dependentPanelGridMargin()"));
        assertTrue(source.contains("private static double parameterLabelTextWidth(double labelColumnWidth,\n"
                + "                                                  double labelColumnGap)"));
        assertTrue(source.contains("- (labelColumnGap * 2.0);"));
        assertTrue(source.contains("header.setPadding(LauncherGeometry.uniformOuterMargin());"));
        assertTrue(source.contains("private static final class HeaderGeometry"));
        assertTrue(source.contains("private static final double HEADER_STACK_GAP =\n"
                + "                LauncherGeometry.INTRA_PANEL_MARGIN;"));
        assertTrue(source.contains("private static final double TITLE_ROW_GAP =\n"
                + "                HEADER_STACK_GAP;"));
        assertTrue(source.contains("private static final double ACTION_CLUSTER_GAP =\n"
                + "                LauncherGeometry.INTRA_PANEL_SUBTLE_GAP - SURFACE_BORDER_WIDTH;"));
        assertTrue(source.contains("private static final double MENU_EDGE_MARGIN =\n"
                + "                LauncherGeometry.INTRA_PANEL_MARGIN;"));
        assertTrue(source.contains("private static final double WIDEST_SEGMENT_ROW_WIDTH =\n"
                + "                SEGMENT_LABEL_WIDTH"));
        assertTrue(source.contains("private static final double OPTIONS_GROUP_OUTER_WIDTH =\n"
                + "                WIDEST_SEGMENT_ROW_WIDTH"));
        assertTrue(source.contains("private static final double MENU_WIDTH =\n"
                + "                OPTIONS_GROUP_OUTER_WIDTH"));
        assertTrue(source.contains("private static final double MENU_POPUP_WIDTH =\n"
                + "                MENU_WIDTH"));
        assertTrue(source.contains("private static final double SIMPLE_MENU_ITEM_SHELL_INSET =\n"
                + "                OPTIONS_PANEL_INSET - (SURFACE_BORDER_WIDTH * 2.0);"));
        assertTrue(source.contains("private static final double MENU_ITEM_WIDTH =\n"
                + "                MENU_WIDTH - (OPTIONS_PANEL_INSET * 2.0);"));
        assertTrue(source.contains("VBox header = new VBox(HeaderGeometry.HEADER_STACK_GAP);"));
        assertTrue(source.contains("HBox titleRow = new HBox(HeaderGeometry.TITLE_ROW_GAP);"));
        assertTrue(source.contains("VBox titleBlock = new VBox(HeaderGeometry.TITLE_BLOCK_GAP);"));
        assertTrue(source.contains("HBox actionCluster = new HBox(HeaderGeometry.ACTION_CLUSTER_GAP);"));
        assertTrue(source.contains("new HBox(HeaderGeometry.MENU_GRAPHIC_GAP, label, chevron);"));
        assertTrue(source.contains("menu.setMinWidth(HeaderGeometry.MENU_POPUP_WIDTH);"));
        assertTrue(source.contains("menu.setPrefWidth(HeaderGeometry.MENU_POPUP_WIDTH);"));
        assertTrue(source.contains("HeaderGeometry.MENU_EDGE_MARGIN);"));
        assertTrue(source.contains("screenBounds.getMaxY() + HeaderGeometry.MENU_VERTICAL_OFFSET;"));
        assertTrue(source.contains("visualBounds.getMaxY() - HeaderGeometry.MENU_MIN_VISIBLE_HEIGHT"));
        assertTrue(source.contains("menuContent.setPadding(new Insets(HeaderGeometry.OPTIONS_PANEL_INSET));"));
        assertTrue(source.contains("new VBox(HeaderGeometry.OPTIONS_GROUP_GAP, outputRow, modeRow, motionRow);"));
        assertTrue(source.contains("new HBox(HeaderGeometry.SEGMENT_ROW_GAP);"));
        assertTrue(source.contains("new HBox(HeaderGeometry.SEGMENT_CONTROL_GAP, buttons);"));
        assertTrue(source.contains("VBox root = new VBox(LauncherGeometry.FLUSH);"));
        assertTrue(source.contains("VBox body = new VBox(LauncherGeometry.INPUT_STACK_GAP);"));
        assertTrue(source.contains("body.setPadding(LauncherGeometry.inputContentPadding());"));
        assertTrue(source.contains("box.setPadding(LauncherGeometry.intraPanelPadding());"));
        assertTrue(source.contains("top.setPadding(LauncherGeometry.intraPanelPadding());"));
        assertTrue(source.contains("grid.setPadding(parameterGridPadding());"));
        assertTrue(source.contains("cards.setHgap(SECTION_CONTENT_GAP);"));
        assertTrue(source.contains("cards.setVgap(SECTION_CONTENT_GAP);"));
        assertTrue(source.contains("scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);"));
        assertTrue(source.contains("scroll.viewportBoundsProperty().addListener"));
        assertTrue(source.contains("body.setMinHeight(newBounds == null ? Region.USE_COMPUTED_SIZE : newBounds.getHeight())"));
        assertTrue(source.contains("scroll.setPrefViewportWidth(SETTINGS_VIEWPORT_WIDTH);"));
        assertTrue(source.contains("scroll.setPrefViewportHeight(SETTINGS_VIEWPORT_HEIGHT);"));
        assertTrue(source.contains("HBox workspace = new HBox(LauncherGeometry.INTER_PANE_GAP);"));
        assertTrue(source.contains("workspace.setPadding(LauncherGeometry.uniformOuterMargin());"));
        assertTrue(source.contains("addStyleClass(dialog.getDialogPane(), \"astra-launcher-dialog-pane\");"));
        assertTrue(source.contains("applyMainActionButtonGeometry(runButton);"));
        assertTrue(source.contains("applyMainActionButtonGeometry(cancelButton);"));
        assertTrue(source.contains("createMainActionBar(runProgressLane, cancelButton, runButton)"));
        assertTrue(source.contains("HBox.setHgrow(progressLane, Priority.ALWAYS);"));
        assertTrue(source.contains("addStyleClass(bar, \"astra-main-action-bar\");"));
        assertFalse(source.contains("applyLauncherDialogButtonBarGeometry"));
        assertTrue(css.contains(".astra-main-action-bar"));
        assertTrue(css.contains(".astra-launcher-dialog-pane .button-bar"));
        assertTrue(css.contains("-fx-pref-height: 0;"));
        assertTrue(css.contains(".astra-dependent-panel"));
        assertTrue(css.contains("-fx-border-width: 1;"));
        assertTrue(source.contains("button.setMinHeight(PARAMETER_ROW_HEIGHT);"));
        assertTrue(source.contains("button.setPrefHeight(PARAMETER_ROW_HEIGHT);"));
        assertTrue(css.contains("Mirrors PipelineLauncher.LauncherGeometry"));
        assertTrue(css.contains("-fx-pref-width: 24px;"));
        assertTrue(css.contains("-fx-min-width: 24px;"));
        assertTrue(css.contains("-fx-max-width: 24px;"));
        assertTrue(css.contains("-fx-padding: 0 8 0 8;"));
        assertFalse(css.contains("-fx-min-height: 58px;"));
        assertFalse(css.contains("-fx-pref-height: 58px;"));
        assertFalse(css.contains("-fx-padding: 0 24 24 24;"));
        assertFalse(source.contains("WORKSPACE_GAP = 12.0"));
        assertFalse(css.contains("-fx-padding: 0 10 0 3;"));
        assertFalse(css.contains("-fx-padding: 0 6 0 6;"));
        assertFalse(css.contains("-fx-padding: 3 6;"));
        assertFalse(css.contains("-fx-padding: 10 10 11 0;"));
        assertFalse(css.contains("-fx-padding: 0 0 0 12;"));
        assertFalse(css.contains(".astra-dependent-panel-rows {\n    -fx-padding:"));
        assertFalse(source.contains("private static final double CONTENT_MARGIN"));
        assertFalse(source.contains("private static final double WORKSPACE_GAP"));
        assertFalse(source.contains("CONTENT_HORIZONTAL_MARGIN"));
        assertFalse(source.contains("BODY_HORIZONTAL_MARGIN"));
        assertFalse(source.contains("BODY_LEFT_MARGIN"));
        assertFalse(source.contains("BODY_RIGHT_MARGIN"));
        assertFalse(Pattern.compile("private static final double \\w+ = [0-9]")
                .matcher(source)
                .find());
        assertFalse(Pattern.compile("(new VBox|new HBox|super)\\(0\\.0\\)")
                .matcher(source)
                .find());
        assertFalse(Pattern.compile("new Insets\\(0\\)")
                .matcher(source)
                .find());
        assertFalse(Pattern.compile("setPrefViewport(Width|Height)\\([0-9]")
                .matcher(source)
                .find());
    }

    @Test
    void launcherGeometryConstantsSatisfyTrueMarginEquations() throws Exception {
        Class<?> launcherGeometry = nestedClass(PipelineLauncher.class, "LauncherGeometry");

        double outerMargin = staticDouble(launcherGeometry, "OUTER_MARGIN");
        double scrollbarGutter = staticDouble(launcherGeometry, "SCROLLBAR_GUTTER_WIDTH");
        double scrollbarThumb = staticDouble(launcherGeometry, "SCROLLBAR_THUMB_WIDTH");
        double scrollbarSidePadding = staticDouble(launcherGeometry, "SCROLLBAR_SIDE_PADDING");
        double inputContentToBarGap = staticDouble(launcherGeometry, "INPUT_CONTENT_TO_BAR_GAP");
        double interPaneGap = staticDouble(launcherGeometry, "INTER_PANE_GAP");
        double inputStackGap = staticDouble(launcherGeometry, "INPUT_STACK_GAP");

        assertEquals(outerMargin, inputStackGap);
        assertEquals(outerMargin, scrollbarGutter);
        assertEquals(scrollbarGutter / 3.0d, scrollbarThumb);
        assertEquals((scrollbarGutter - scrollbarThumb) / 2.0d, scrollbarSidePadding);
        assertEquals(outerMargin - scrollbarSidePadding, inputContentToBarGap);
        assertEquals(outerMargin - scrollbarSidePadding, interPaneGap);
        assertEquals(outerMargin, inputContentToBarGap + scrollbarSidePadding);
        assertEquals(outerMargin, scrollbarSidePadding + interPaneGap);
    }

    @Test
    void settingsScrollbarCssMirrorsLauncherGeometry() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/qupath/ext/astra/astra-launcher.css"));
        Class<?> launcherGeometry = nestedClass(PipelineLauncher.class, "LauncherGeometry");

        double gutter = staticDouble(launcherGeometry, "SCROLLBAR_GUTTER_WIDTH");
        double sidePadding = staticDouble(launcherGeometry, "SCROLLBAR_SIDE_PADDING");

        String scrollbarBlock = cssBlock(css, ".astra-settings-scroll .scroll-bar:vertical");

        assertEquals(gutter, cssPx(scrollbarBlock, "-fx-pref-width"));
        assertEquals(gutter, cssPx(scrollbarBlock, "-fx-min-width"));
        assertEquals(gutter, cssPx(scrollbarBlock, "-fx-max-width"));
        assertTrue(scrollbarBlock.contains("-fx-padding: 0 %.0f 0 %.0f;".formatted(sidePadding, sidePadding)));
    }

    @Test
    void settingsScrollPaneDoesNotClampContentHeightToViewport() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertTrue(source.contains("scroll.setFitToWidth(true);"));
        assertTrue(source.contains("scroll.setFitToHeight(false);"));
        assertTrue(source.contains("body.setMinHeight(newBounds == null ? Region.USE_COMPUTED_SIZE : newBounds.getHeight())"));
    }

    @Test
    void parameterRailConstantsSatisfyTrueBlankspaceEquations() throws Exception {
        Class<?> launcherGeometry = nestedClass(PipelineLauncher.class, "LauncherGeometry");
        double layoutUnit = staticDouble(launcherGeometry, "LAYOUT_UNIT");
        double intraPanelMargin = staticDouble(launcherGeometry, "INTRA_PANEL_MARGIN");
        double intraPanelTightGap = staticDouble(launcherGeometry, "INTRA_PANEL_TIGHT_GAP");
        double intraPanelSubtleGap = staticDouble(launcherGeometry, "INTRA_PANEL_SUBTLE_GAP");
        double borderWidth = staticDouble(PipelineLauncher.class,
                "BORDER_WIDTH");
        double surfaceBorderWidth = staticDouble(PipelineLauncher.class,
                "SURFACE_BORDER_WIDTH");
        double rowHeight = staticDouble(PipelineLauncher.class,
                "PARAMETER_ROW_HEIGHT");
        double rowGap = staticDouble(PipelineLauncher.class,
                "PARAMETER_ROW_GAP");
        double labelColumnWidth = staticDouble(PipelineLauncher.class,
                "PARAMETER_LABEL_COLUMN_WIDTH");
        double helpColumnWidth = staticDouble(PipelineLauncher.class,
                "PARAMETER_HELP_COLUMN_WIDTH");
        double rowHorizontalPadding = staticDouble(PipelineLauncher.class,
                "PARAMETER_ROW_HORIZONTAL_PADDING");
        double rowContainerLeftInset = staticDouble(PipelineLauncher.class,
                "PARAMETER_ROW_CONTAINER_LEFT_INSET");
        double rowEdgeToBarGap = staticDouble(PipelineLauncher.class,
                "PARAMETER_ROW_EDGE_TO_BAR_GAP");
        double anchorWidth = staticDouble(PipelineLauncher.class,
                "PARAMETER_ANCHOR_WIDTH");
        double barWidth = staticDouble(PipelineLauncher.class,
                "BAR_WIDTH");
        double anchorColumnWidth = staticDouble(PipelineLauncher.class,
                "PARAMETER_ANCHOR_COLUMN_WIDTH");
        double firstRowHeight = staticDouble(PipelineLauncher.class,
                "PARAMETER_FIRST_ROW_HEIGHT");
        double accentIndent = staticDouble(PipelineLauncher.class,
                "ACCENT_INDENT");
        double barToTextGap = staticDouble(PipelineLauncher.class,
                "PARAMETER_BAR_TO_TEXT_GAP");
        double rowTextRail = staticDouble(PipelineLauncher.class,
                "PARAMETER_ROW_TEXT_RAIL");
        double gridTextRail = staticDouble(PipelineLauncher.class,
                "PARAMETER_GRID_TEXT_RAIL");
        double dependentPanelLeftInset = staticDouble(PipelineLauncher.class,
                "DEPENDENT_PANEL_LEFT_INSET");
        double dependentRowsLeftInset = staticDouble(PipelineLauncher.class,
                "DEPENDENT_ROWS_LEFT_INSET");
        double dependentPanelOuterLeftMargin = staticDouble(PipelineLauncher.class,
                "DEPENDENT_PANEL_OUTER_LEFT_MARGIN");
        double dependentLabelColumnGap = staticDouble(PipelineLauncher.class,
                "DEPENDENT_LABEL_COLUMN_GAP");
        double dependentLabelColumnWidth = staticDouble(PipelineLauncher.class,
                "DEPENDENT_LABEL_COLUMN_WIDTH");
        double dependentTitleTextInset = staticDouble(PipelineLauncher.class,
                "DEPENDENT_TITLE_TEXT_INSET");
        double helpRail = staticDouble(PipelineLauncher.class,
                "HELP_RAIL");
        double editorRail = staticDouble(PipelineLauncher.class,
                "EDITOR_RAIL");
        double anchorHeight = staticDouble(PipelineLauncher.class,
                "PARAMETER_ANCHOR_HEIGHT");
        double anchorPaintRadius = staticDouble(PipelineLauncher.class,
                "PARAMETER_ANCHOR_PAINT_RADIUS");
        double anchorPaintArc = staticDouble(PipelineLauncher.class,
                "PARAMETER_ANCHOR_PAINT_ARC");
        double helpButtonSize = staticDouble(PipelineLauncher.class,
                "PARAMETER_HELP_BUTTON_SIZE");
        double settingsViewportWidth = staticDouble(PipelineLauncher.class,
                "SETTINGS_VIEWPORT_WIDTH");
        double settingsViewportHeight = staticDouble(PipelineLauncher.class,
                "SETTINGS_VIEWPORT_HEIGHT");
        double colocalizationLabelWidth = staticDouble(PipelineLauncher.class,
                "COLOCALIZATION_PANEL_LABEL_WIDTH");
        double colocalizationWideLabelWidth = staticDouble(PipelineLauncher.class,
                "COLOCALIZATION_PANEL_WIDE_LABEL_WIDTH");

        assertEquals(layoutUnit + intraPanelSubtleGap + (surfaceBorderWidth * 2.0d), rowHeight);
        assertEquals(surfaceBorderWidth, borderWidth);
        assertEquals(intraPanelSubtleGap, rowGap);
        assertEquals((layoutUnit * 12.0d) + intraPanelTightGap, labelColumnWidth);
        assertEquals(layoutUnit - (borderWidth * 2.0d), helpColumnWidth);
        assertEquals(0.0d, rowHorizontalPadding);
        assertEquals(intraPanelMargin - rowHorizontalPadding, rowContainerLeftInset);
        assertEquals(rowHorizontalPadding, rowEdgeToBarGap);
        assertEquals(layoutUnit / 4.0d, anchorWidth);
        assertEquals(anchorWidth, barWidth);
        assertEquals(anchorWidth, anchorColumnWidth);
        assertEquals(rowHeight, firstRowHeight);
        assertEquals(firstRowHeight, anchorHeight);
        assertEquals(Math.min(anchorWidth, firstRowHeight) / 2.0d, anchorPaintRadius);
        assertEquals(anchorPaintRadius * 2.0d, anchorPaintArc);
        assertEquals(helpColumnWidth - intraPanelTightGap, helpButtonSize);
        assertEquals(intraPanelMargin + borderWidth + rowEdgeToBarGap, accentIndent);
        assertEquals(accentIndent - barWidth, barToTextGap);
        assertTrue(barToTextGap > 0.0d);
        assertEquals(rowEdgeToBarGap + anchorWidth + barToTextGap, rowTextRail);
        assertEquals(intraPanelMargin + borderWidth + rowTextRail, gridTextRail);
        assertEquals(
                intraPanelMargin
                        + borderWidth
                        + labelColumnWidth
                        - rowHorizontalPadding
                        - helpColumnWidth,
                helpRail);
        assertEquals(
                intraPanelMargin
                        + borderWidth
                        + labelColumnWidth
                        + staticDouble(PipelineLauncher.class, "SECTION_CONTENT_GAP"),
                editorRail);
        assertEquals(layoutUnit * 30.0d, settingsViewportWidth);
        assertEquals((layoutUnit * 29.0d) + intraPanelTightGap, settingsViewportHeight);
        assertEquals(layoutUnit * 20.0d / 3.0d, colocalizationLabelWidth);
        assertEquals(layoutUnit * 15.0d / 2.0d, colocalizationWideLabelWidth);
        assertEquals(accentIndent - (borderWidth * 2.0d) - rowEdgeToBarGap, dependentPanelLeftInset);
        assertEquals(dependentPanelLeftInset + borderWidth, dependentRowsLeftInset);
        assertEquals(intraPanelMargin - rowContainerLeftInset, dependentPanelOuterLeftMargin);
        assertEquals(barToTextGap, dependentLabelColumnGap);
        assertEquals(accentIndent - borderWidth, dependentTitleTextInset);
        assertEquals(labelColumnWidth - accentIndent, dependentLabelColumnWidth);
    }

    @Test
    void launcherArchitectureUsesDeclaredSectionsAndNoDuplicatedSetupControls() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertTrue(source.contains("VBox header = new VBox(HeaderGeometry.HEADER_STACK_GAP);"));
        assertTrue(source.contains("VBox body = new VBox(LauncherGeometry.INPUT_STACK_GAP);"));
        assertTrue(source.contains("AnimatedGradientHeader animatedHeader = new AnimatedGradientHeader(header);"));
        assertTrue(source.contains("createHeaderMenuButton(\"Settings\""));
        assertTrue(source.contains("createHeaderMenuButton(\"Project\""));
        assertTrue(source.contains("createHeaderMenuButton(\"View\""));
        assertTrue(source.contains("createHeaderActionMenuItem(\"Reset settings\""));
        assertTrue(source.contains("createHeaderActionMenuItem(\"Reset Image\""));
        assertTrue(source.contains("createHeaderActionMenuItem(\"Export\""));
        assertFalse(source.contains("createHeaderActionGroup(\"Settings\""));
        assertTrue(source.contains("createViewMenuItem("));
        assertTrue(source.contains("createHeaderViewPanel("));
        assertTrue(source.contains("InputGradientFillPanel inputFillPanel = new InputGradientFillPanel();"));
        assertTrue(source.contains("VBox.setVgrow(inputFillPanel, Priority.ALWAYS);"));
        assertTrue(source.contains("RunProgressLane runProgressLane = new RunProgressLane();"));
        assertTrue(source.contains("feedback.attachRunProgressLane(runProgressLane);"));
        assertTrue(source.contains("createMainActionBar(runProgressLane, cancelButton, runButton)"));
        assertTrue(source.contains("private final Label progressText = new Label();"));
        assertTrue(source.contains("setPrefHeight(LauncherGeometry.ACTION_PROGRESS_TOTAL_HEIGHT);"));
        assertTrue(source.contains("progressTextFor(state)"));
        assertTrue(source.contains("Ready for the next ASTRA run."));
        assertTrue(source.contains("runProgressLane.setGradientMode(initialMode);"));
        assertTrue(source.contains("runProgressLane.setMotionSpeed(initialSpeed);"));
        assertTrue(source.contains("inputFillPanel.setGradientMode(initialMode);"));
        assertTrue(source.contains("inputFillPanel.setMotionSpeed(initialSpeed);"));
        assertTrue(source.contains("private static final class ParameterAccentBar extends StackPane"));
        assertTrue(source.contains("HEADER_MODE_PREFERENCE.addListener"));
        assertTrue(source.contains("gradientSurface.setHeaderMode(headerModePreference());"));
        assertFalse(source.contains("state == RunProgressState.RUNNING\n"
                + "                    ? selectedMode\n"
                + "                    : AnimatedGradientHeader.HeaderMode.STATIC"));
        assertTrue(source.contains("headerSegmentRow(\"Run Log Pane\", show, hide)"));
        assertTrue(source.contains("label.setMinWidth(HeaderGeometry.SEGMENT_LABEL_WIDTH)"));
        assertTrue(source.contains("showHeaderActionMenu(button, menu)"));
        assertTrue(source.contains("preferredHeaderMenuX("));
        assertTrue(source.contains("MENU_RENDERED_POPUP_WIDTH ="));
        assertTrue(source.contains("MENU_POPUP_WIDTH + (MENU_EDGE_MARGIN * 2.0)"));
        assertTrue(source.contains("MENU_ANCHOR_TO_WINDOW_OFFSET ="));
        assertTrue(source.contains("MENU_EDGE_MARGIN;"));
        assertTrue(source.contains("Window popupWindow = menu.getScene() == null ? null : menu.getScene().getWindow();"));
        assertTrue(source.contains("popupWindow.getWidth()"));
        assertTrue(source.contains("menu.show(button, x + HeaderGeometry.MENU_ANCHOR_TO_WINDOW_OFFSET, y)"));
        assertTrue(source.contains("menu.setX(alignedX);"));
        assertTrue(source.contains("PathPrefs.createPersistentPreference(HEADER_MODE_PREFERENCE_KEY"));
        assertTrue(source.contains("PathPrefs.createPersistentPreference(HEADER_MOTION_PREFERENCE_KEY"));
        assertTrue(source.contains("AnimatedGradientHeader.HeaderMode.DYNAMIC.name()"));
        assertTrue(source.contains("AnimatedGradientHeader.MotionSpeed.SMOOTH.name()"));
        assertTrue(source.contains("createSettingsNavigator(\"Settings Dashboard\""));
        assertTrue(source.contains("createSettingsNavigator(\"Advanced Settings\""));
        assertTrue(source.contains("Button dashboard = new Button(\"Dashboard\")"));
        assertTrue(source.contains("Button allSettings = new Button(\"All Settings\")"));
        assertTrue(source.contains("addStyleClass(viewRow, \"astra-settings-view-toggle\")"));
        assertTrue(source.contains("addStyleClass(header, \"astra-section-heading-row\")"));
        assertTrue(source.contains("addStyleClass(host, \"astra-settings-host\")"));
        assertTrue(source.contains("addStyleClass(top, \"astra-focused-section-header\")"));
        assertTrue(source.contains("setCollapsibleHeaderVisible(section.content(), false)"));
        assertTrue(source.contains("addStyleClass(content, \"astra-section-content-focused\")"));
        assertTrue(source.contains("removeStyleClass(content, \"astra-section-content-focused\")"));
        assertFalse(source.contains("VBox.setVgrow(routineNavigator, Priority.ALWAYS)"));
        assertFalse(source.contains("focused.setMaxHeight(Double.MAX_VALUE)"));
        assertTrue(Files.readString(Path.of("src/test/java/qupath/ext/astra/LauncherPreviewApp.java"))
                .contains("\"focused-panel-diagnostic-all\""));
        assertTrue(source.contains("createDependentPanel("));
        assertTrue(source.contains("Node feedbackNode = feedback.node();"));
        assertTrue(source.contains("\"Colocalization Setup\""));
        assertTrue(source.contains(".filter(c -> !isHandledByColocalizationPanel(c.name, colocalization))"));
        assertTrue(source.contains("private static VBox createColocalizationPanel("));
        assertTrue(source.contains("static boolean isHandledByColocalizationPanel(String name, boolean colocalization)"));
        assertTrue(source.contains("setNodeEnabled(nucleusModel, state.showNucleusModel())"));
        assertTrue(source.contains("addStyleClass(box, \"astra-semantic-card\")"));
        assertFalse(source.contains("createVascularPanel("));
        assertFalse(source.contains("createTrainingPanel("));
        assertFalse(source.contains("createTuningPanel("));
        assertFalse(source.contains("createValidationPanel("));
    }

    @Test
    void launcherSourceUsesSharedLabeledRowsForConsistentVerticalSpacing() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));
        String css = Files.readString(Path.of("src/main/resources/qupath/ext/astra/astra-launcher.css"));

        assertTrue(source.contains("private static HBox labeledRow(String labelText, Node editor, double labelWidth)"));
        assertTrue(source.contains("row.setMinHeight(PARAMETER_ROW_HEIGHT);"));
        assertTrue(source.contains("grid.setVgap(PARAMETER_ROW_GAP);"));
        assertTrue(source.contains("VBox group = new VBox(PARAMETER_ROW_GAP);"));
        assertTrue(source.contains("private final VBox rows = new VBox(PARAMETER_ROW_GAP);"));
        assertTrue(source.contains("labelBox.setAlignment(Pos.CENTER_LEFT);"));
        assertTrue(source.contains("GridPane.setValignment(info, VPos.CENTER);"));
        assertTrue(source.contains("The accent midpoint belongs on the first-row rail midpoint"));
        assertTrue(source.contains("labelBox.setMaxHeight(tall ? Double.MAX_VALUE : PARAMETER_ROW_HEIGHT);"));
        assertTrue(source.contains("labelBox.add(anchor, 0, 0, 1, 2);"));
        assertTrue(source.contains("GridPane.setFillHeight(anchor, true);"));
        assertTrue(source.contains("GridPane.setFillHeight(nodes.label, nodes.tall());"));
        assertTrue(source.contains("GridPane.setValignment(nodes.label, nodes.tall() ? VPos.TOP : VPos.CENTER);"));
        assertTrue(source.contains("private static boolean isTallParameterEditor(Node editor)"));
        assertTrue(source.contains("|| editor instanceof StageModeEditor"));
        assertTrue(source.contains("HBox row = labeledRow(displayLabel(constant.name), editor,\n"
                + "                    COLOCALIZATION_PANEL_LABEL_WIDTH);"));
        assertTrue(source.contains("HBox row = labeledRow(displayLabel(\"DETECTION_TARGET\"), editor,\n"
                + "                    COLOCALIZATION_PANEL_LABEL_WIDTH);"));
        assertTrue(source.contains("private static final double SECTION_CONTENT_GAP = LauncherGeometry.INTRA_PANEL_MARGIN;"));
        assertTrue(source.contains("private static final double CARD_CONTENT_GAP = LauncherGeometry.INTRA_PANEL_SUBTLE_GAP;"));
        assertTrue(source.contains("private static final double DASHBOARD_CARD_HEIGHT =\n"
                + "            LauncherGeometry.LAYOUT_UNIT * 11.0 / 2.0;"));
        assertTrue(source.contains("private static final double DASHBOARD_CARD_INSET =\n"
                + "            LauncherGeometry.INTRA_PANEL_MARGIN;"));
        assertTrue(source.contains("private static final double DASHBOARD_CARD_BODY_HEIGHT =\n"
                + "            DASHBOARD_CARD_HEIGHT"));
        assertTrue(source.contains("private static final double DASHBOARD_CARD_ACCENT_WIDTH =\n"
                + "            PARAMETER_ANCHOR_WIDTH;"));
        assertTrue(source.contains("private static final double DASHBOARD_CARD_ACCENT_TO_CONTENT_GAP =\n"
                + "            DASHBOARD_CARD_INSET;"));
        assertTrue(source.contains("card.setPadding(new Insets(DASHBOARD_CARD_INSET));"));
        assertTrue(source.contains("HBox shell = new HBox(DASHBOARD_CARD_ACCENT_TO_CONTENT_GAP, accent, content);"));
        assertFalse(css.contains(".astra-settings-card {\n    -fx-background-color: #ffffff;\n"
                + "    -fx-border-color: #c8dce1;\n"
                + "    -fx-border-radius: 7;\n"
                + "    -fx-background-radius: 7;\n"
                + "    -fx-padding:"));
        assertTrue(source.contains("private static final int MULTI_SELECT_BUTTON_SUMMARY_LIMIT = 64;"));
        assertTrue(source.contains("selectedButtonText(selectedValues)"));
        assertTrue(source.contains("double labelColumnWidth = dependent ? DEPENDENT_LABEL_COLUMN_WIDTH : PARAMETER_LABEL_COLUMN_WIDTH;"));
        assertTrue(source.contains("double labelColumnGap = dependent ? DEPENDENT_LABEL_COLUMN_GAP : PARAMETER_LABEL_COLUMN_GAP;"));
        assertTrue(source.contains("Label label = new Label(displayLabel(constant.name));"));
        assertTrue(source.contains("label.setWrapText(true);"));
        assertTrue(source.contains("label.setAlignment(Pos.CENTER_LEFT);"));
        assertTrue(source.contains("label.setMinSize(labelTextWidth, PARAMETER_FIRST_ROW_HEIGHT);"));
        assertTrue(source.contains("label.setPrefSize(labelTextWidth, PARAMETER_FIRST_ROW_HEIGHT);"));
        assertTrue(source.contains("label.setMaxWidth(labelTextWidth);"));
        assertTrue(css.contains(".astra-parameter-label {\n"
                + "    -fx-font-size: 12px;\n"
                + "    -fx-font-weight: bold;\n"
                + "    -fx-text-fill: #172431;\n"
                + "    -fx-fill: #172431;\n"
                + "    -fx-padding: 0;\n"
                + "}"));
        assertTrue(source.contains("double labelTextWidth = parameterLabelTextWidth(labelColumnWidth, labelColumnGap);"));
        assertTrue(source.contains("labelBox.setHgap(labelColumnGap);"));
        assertTrue(source.contains("labelBox.setPadding(parameterRowPadding());"));
        assertTrue(source.contains("ColumnConstraints anchorColumn = new ColumnConstraints(\n"
                + "                PARAMETER_ANCHOR_COLUMN_WIDTH,"));
        assertTrue(source.contains("ParameterAccentBar anchor = new ParameterAccentBar();"));
        assertTrue(source.contains("private final Rectangle paintMask = new Rectangle();"));
        assertTrue(source.contains("gradientSurface.setManaged(false);"));
        assertFalse(source.contains("bevelOverlay"));
        assertTrue(source.contains("setClip(paintMask);"));
        assertTrue(source.contains("paintMask.setWidth(width);"));
        assertTrue(source.contains("paintMask.setHeight(height);"));
        assertTrue(source.contains("paintMask.setArcWidth(PARAMETER_ANCHOR_PAINT_ARC);"));
        assertTrue(source.contains("paintMask.setArcHeight(PARAMETER_ANCHOR_PAINT_ARC);"));
        assertTrue(source.contains("double width = PARAMETER_ANCHOR_WIDTH;"));
        assertFalse(source.contains("PARAMETER_ANCHOR_CLIP_ARC"));
        assertFalse(source.contains("strokeRoundRect("));
        String previewSource = Files.readString(Path.of("src/test/java/qupath/ext/astra/LauncherPreviewApp.java"));
        assertTrue(previewSource.contains("anchor.localToScene(anchor.getLayoutBounds()).getMinX()"));
        assertTrue(previewSource.contains("anchor.localToScene(anchor.getLayoutBounds()).getMaxX()"));
        assertTrue(previewSource.contains("node.getParent().localToScene(node.getParent().getLayoutBounds()).getMinX()"));
        assertTrue(source.contains("GridPane.setHalignment(anchor, HPos.LEFT);"));
        assertTrue(source.contains("info.setMinSize(PARAMETER_HELP_BUTTON_SIZE, PARAMETER_HELP_BUTTON_SIZE);"));
        assertTrue(source.contains("box.setPadding(dependentPanelPadding());"));
        assertTrue(source.contains("box.setMaxWidth(Double.MAX_VALUE);"));
        assertTrue(source.contains("title.setPadding(dependentTitlePadding());"));
        assertTrue(source.contains("reason.setPadding(dependentTitlePadding());"));
        assertTrue(source.contains("inner.setPadding(dependentRowsPadding());"));
        assertTrue(source.contains("inner.setHgap(SECTION_CONTENT_GAP);"));
        assertTrue(source.contains("GridPane.setHgrow(panel, Priority.ALWAYS);"));
        assertTrue(source.contains("GridPane.setFillWidth(panel, true);"));
        assertTrue(source.contains("GridPane.setMargin(panel, dependentPanelGridMargin());"));
        assertTrue(source.contains("addParameterRow(inner, row, row, constant, rows, autosave, true);"));
        assertFalse(css.contains(".astra-parameter-row {\n    -fx-padding:"));
        assertFalse(css.contains(".astra-dependent-panel {\n    -fx-padding:"));
        assertFalse(css.contains(".astra-dependent-panel-title {\n    -fx-font-size: 12px;\n"
                + "    -fx-font-weight: bold;\n"
                + "    -fx-text-fill: #172431;\n"
                + "    -fx-padding:"));
        assertFalse(css.contains(".astra-dependent-panel-rows {\n    -fx-padding:"));
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
        assertEquals("Any Pixel Above Threshold", GuiPresentation.displayOption("POSITIVITY_METHOD", "ANY_PIXEL_ABOVE_THRESHOLD"));
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
        assertTrue(source.contains("super(SelectionGeometry.EDITOR_STACK_GAP);"));
        assertTrue(source.contains("list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE)"));
        assertTrue(source.contains("list.setPrefSize(\n"
                + "                    SelectionGeometry.SINGLE_LIST_WIDTH,\n"
                + "                    SelectionGeometry.SINGLE_LIST_HEIGHT);"));
        assertTrue(source.contains("Button selectAll = ProjectImageSelectionEditor.smallButton(\"Select All\")"));
        assertTrue(source.contains("Button clear = ProjectImageSelectionEditor.smallButton(\"Clear\")"));
        assertTrue(source.contains("FlowPane actions = new FlowPane(\n"
                + "                    SelectionGeometry.DIALOG_ACTION_GAP,"));
        assertTrue(source.contains("content.setPadding(new Insets(SelectionGeometry.DIALOG_CONTENT_INSET));"));
        assertTrue(source.contains("ButtonType.APPLY"));
        assertFalse(source.contains("FlowPane channels"));
        assertFalse(source.contains("list.setPrefSize(420.0, 280.0)"));
        assertTrue(source.contains("compartment.getItems().addAll(\"Nucleus\", \"Cytoplasm\", \"Cell\")"));
        assertTrue(source.contains("compartment.setMinWidth(CHECK_COMPARTMENT_MIN_WIDTH)"));
        assertTrue(source.contains("compartment.setPrefWidth(CHECK_COMPARTMENT_PREF_WIDTH)"));
        assertTrue(source.contains("styleComboBox(compartment)"));
        assertTrue(source.contains("label.setMinWidth(CHECK_LABEL_MIN_WIDTH)"));
        assertTrue(source.contains("remove.setMinHeight(CHECK_REMOVE_BUTTON_HEIGHT)"));
        assertTrue(source.contains("remove.setMinWidth(CHECK_REMOVE_BUTTON_WIDTH)"));
        assertTrue(source.contains("private static void styleCheckBox(CheckBox box)"));
        assertTrue(source.contains("addStyleClass(box, \"astra-checkbox\")"));
    }

    @Test
    void comboBoxSelectedValueUsesReadableTextColor() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));
        String css = Files.readString(Path.of("src/main/resources/qupath/ext/astra/astra-launcher.css"));

        assertTrue(source.contains("private static final class ControlGeometry"));
        assertTrue(source.contains("private static final double COMBO_CELL_VERTICAL_INSET =\n"
                + "                LauncherGeometry.INTRA_PANEL_SUBTLE_GAP - SURFACE_BORDER_WIDTH;"));
        assertTrue(source.contains("private static final double COMBO_CELL_HORIZONTAL_INSET =\n"
                + "                LauncherGeometry.INTRA_PANEL_MARGIN - (SURFACE_BORDER_WIDTH * 2.0);"));
        assertTrue(source.contains("private static final class SelectionGeometry"));
        assertTrue(source.contains("private static final double EDITOR_STACK_GAP =\n"
                + "                LauncherGeometry.INTRA_PANEL_SUBTLE_GAP - SURFACE_BORDER_WIDTH;"));
        assertTrue(source.contains("private static final double DUAL_LIST_GAP =\n"
                + "                LauncherGeometry.INTRA_PANEL_MARGIN;"));
        assertTrue(source.contains("private static final double DIALOG_CONTENT_INSET =\n"
                + "                LauncherGeometry.INTRA_PANEL_MARGIN;"));
        assertTrue(source.contains("private static final double PROJECT_LIST_WIDTH =\n"
                + "                LauncherGeometry.LAYOUT_UNIT * 25.0 / 2.0;"));
        assertTrue(source.contains("private static final double PROJECT_LIST_HEIGHT =\n"
                + "                LauncherGeometry.LAYOUT_UNIT * 40.0 / 3.0;"));
        assertTrue(source.contains("private static Insets comboCellPadding()"));
        assertTrue(source.contains("private static void styleComboCell(ListCell<?> cell)"));
        assertTrue(source.contains("cell.setPadding(comboCellPadding());"));
        assertTrue(source.contains("private static void styleComboBox(ComboBox<String> combo)"));
        assertTrue(source.contains("addStyleClass(combo, \"astra-combo\")"));
        assertTrue(source.contains("addStyleClass(combo.getEditor(), \"astra-input\")"));
        assertTrue(source.contains("private static void styleComboBoxSubnodes(ComboBox<String> combo)"));
        assertTrue(source.contains("combo.lookup(\".arrow-button\")"));
        assertTrue(source.contains("addStyleClass(arrowButton, \"astra-combo-arrow-button\")"));
        assertTrue(source.contains("addStyleClass(arrow, \"astra-combo-arrow\")"));
        assertTrue(source.contains("addStyleClass(selectedCell, \"astra-combo-cell\")"));
        assertTrue(source.contains("combo.setButtonCell(readableComboCell())"));
        assertTrue(source.contains("combo.setCellFactory(list -> readableComboCell())"));
        assertTrue(source.contains("styleComboCell(this);"));
        assertFalse(css.contains("-fx-padding: 7 10;"));
        assertFalse(source.contains("setStyle(\"-fx-font-family: \" + FONT_STACK + \"; -fx-font-size: 12px;\")"));
    }

    @Test
    void runFeedbackPaneUsesFixedRailSoMarginsDoNotCollapse() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertTrue(source.contains("box.setMaxWidth(Region.USE_PREF_SIZE);"));
        assertTrue(source.contains("HBox.setHgrow(box, Priority.NEVER);"));
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
        assertTrue(source.contains("checksPanel.getChildren().add(labeledRow(\"Display in QuPath UI\", displayCheck,\n"
                + "                COLOCALIZATION_PANEL_LABEL_WIDTH))"));
        assertFalse(source.contains("addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get(\"DISPLAY_COLOCALIZATION_CHECK\")"));
        assertTrue(source.contains("addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get(\"PIXEL_POSITIVE_FRACTION_MIN\")"));
        assertTrue(source.contains("addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get(\"THRESHOLD_POPULATION\")"));
        assertTrue(source.contains("addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get(\"GMM_COMPONENTS\")"));
        assertTrue(source.contains("addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get(\"OTSU_CLASS_COUNT\")"));
        assertTrue(source.contains("addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get(\"THRESHOLD_SCOPE\")"));
        assertTrue(source.contains("addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get(\"THRESHOLD_SELECTED_IMAGE_NAMES\")"));
        assertTrue(source.contains("addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get(\"THRESHOLD_PROVENANCE_BY_MARKER\")"));
        assertFalse(source.contains("THRESHOLD_EXCLUDE_MARKERS"));
        assertTrue(source.contains("installColocalizationThresholdDependencies(byName, thresholdRows, thresholdPanel)"));
        assertTrue(source.contains("static Set<String> colocalizationThresholdEnabledRows("));
        assertTrue(source.contains("List.of(\"EXPRESSION_CLASSIFICATION_MODE\", \"POSITIVITY_METHOD\", \"THRESHOLD_MODE\", \"THRESHOLD_SCOPE\", \"BACKGROUND_MODE\")"));
        assertFalse(source.contains("BACKGROUND_SCOPE"));
        assertTrue(source.contains("panel.requestLayout()"));
        assertTrue(source.contains("rows.forEach((name, row) -> setEnabled(rows, name, enabledRows.contains(name)))"));
    }

    @Test
    void genericDependenciesOnlyListenToControlsThatDriveDependencies() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertTrue(source.contains("\"IMAGE_SCOPE\","));
        assertTrue(source.contains("\"THRESHOLD_SCOPE\","));
        assertTrue(source.contains("\"USE_BATCH_MODE\""));
        assertFalse(source.contains("byName.values().forEach(c -> c.addChangeListener(update))"));
        assertFalse(source.contains("byName.values().forEach(c -> c.addOptionListener(update))"));
    }

    @Test
    void parameterDependenciesUseSharedPanelsAndNeverHideRowsAdHoc() throws Exception {
        String source = Files.readString(Path.of("src/main/java/qupath/ext/astra/PipelineLauncher.java"));

        assertTrue(source.contains("new DependencyPanel(\n                    \"selected-images\""));
        assertTrue(source.contains("\"IMAGE_SCOPE\",\n                    Set.of(\"SELECTED_IMAGE_NAMES\""));
        assertTrue(source.contains("new DependencyPanel(\n                    \"threshold-selected-images\""));
        assertTrue(source.contains("new DependencyPanel(\n                    \"nucleus-model-source\""));
        assertTrue(source.contains("new DependencyPanel(\n                    \"cell-model-source\""));
        assertTrue(source.contains("new DependencyPanel(\n                    \"threshold-mode\""));
        assertTrue(source.contains("new DependencyPanel(\n                    \"background-mode\""));
        assertTrue(source.contains("setEnabled(rows, \"SELECTED_IMAGE_NAMES\""));
        assertTrue(source.contains("setEnabled(rows, \"MATCH_SELECTED_IMAGE_NAMES_AGAINST_ORIGINAL\""));
        assertTrue(source.contains("setEnabled(rows, \"NUC_SAVED_MODEL_ID\""));
        assertTrue(source.contains("private static void setNodeEnabled(Node node, boolean enabled)"));
        assertFalse(source.contains("setNodeVisible(nucleusModel"));
        assertFalse(source.contains("private static void setNodeVisible(Node node, boolean visible)"));
        assertFalse(source.contains("setVisible(rows"));
        assertFalse(source.contains("private static void setVisible(Map<String, RowNodes>"));
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
        assertTrue(source.contains("createViewMenuItem("));
        assertTrue(source.contains("animatedHeader,\n                runProgressLane,\n                inputFillPanel"));
        assertTrue(source.contains("headerSegmentButton(\"Show\")"));
        assertTrue(source.contains("headerSegmentButton(\"Hide\")"));
        assertFalse(source.contains("\"Hide Output\""));
        assertFalse(source.contains("\"Show Output\""));
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
    void colocalizationThresholdEnabledRowsDefaultImageStateIsCompact() {
        Set<String> enabled = PipelineLauncher.colocalizationThresholdEnabledRows("LEGACY_BINARY", "MEAN_INTENSITY", "LOG_GAUSSIAN_MIXTURE", "IMAGE", "NONE");

        assertEquals(Set.of("EXPRESSION_CLASSIFICATION_MODE", "POSITIVITY_METHOD", "THRESHOLD_POPULATION", "THRESHOLD_MODE", "THRESHOLD_SCOPE", "BACKGROUND_MODE", "GMM_COMPONENTS"), enabled);
    }

    @Test
    void colocalizationSelectedThresholdSourceEnablesImageSelector() {
        Set<String> image = PipelineLauncher.colocalizationThresholdEnabledRows("LEGACY_BINARY", "MEAN_INTENSITY", "LOG_GAUSSIAN_MIXTURE", "IMAGE", "NONE");
        Set<String> region = PipelineLauncher.colocalizationThresholdEnabledRows("LEGACY_BINARY", "MEAN_INTENSITY", "LOG_GAUSSIAN_MIXTURE", "REGION", "NONE");
        Set<String> selected = PipelineLauncher.colocalizationThresholdEnabledRows("LEGACY_BINARY", "MEAN_INTENSITY", "LOG_GAUSSIAN_MIXTURE", "SELECTED_IMAGES", "NONE");

        assertEquals(image, region);
        assertTrue(selected.contains("THRESHOLD_SELECTED_IMAGE_NAMES"));
        assertTrue(selected.contains("MATCH_THRESHOLD_IMAGE_NAMES_AGAINST_ORIGINAL"));
    }

    @Test
    void colocalizationThresholdEnabledRowsAreModeDriven() {
        assertTrue(PipelineLauncher.colocalizationThresholdEnabledRows("LEGACY_BINARY", "MEAN_INTENSITY", "MANUAL", "IMAGE", "NONE")
                .contains("MANUAL_INTENSITY_THRESHOLDS"));
        assertTrue(PipelineLauncher.colocalizationThresholdEnabledRows("LEGACY_BINARY", "MEAN_INTENSITY", "MANUAL", "IMAGE", "NONE")
                .contains("THRESHOLD_PROVENANCE_BY_MARKER"));
        assertTrue(PipelineLauncher.colocalizationThresholdEnabledRows("LEGACY_BINARY", "MEAN_INTENSITY", "RANGE_PERCENT", "IMAGE", "NONE")
                .contains("RANGE_THRESHOLD_FRACTION_BY_MARKER"));
        assertTrue(PipelineLauncher.colocalizationThresholdEnabledRows("LEGACY_BINARY", "MEAN_INTENSITY", "LOG_GAUSSIAN_MIXTURE", "IMAGE", "MANUAL_OFFSET")
                .contains("BACKGROUND_SUBTRACTION_BY_CHANNEL"));

        Set<String> local = PipelineLauncher.colocalizationThresholdEnabledRows("LEGACY_BINARY", "MEAN_INTENSITY", "LOG_GAUSSIAN_MIXTURE", "IMAGE", "LOCAL_PERCENTILE");
        assertTrue(local.contains("LOCAL_BACKGROUND_PERCENTILE"));

        Set<String> pixelManual = PipelineLauncher.colocalizationThresholdEnabledRows("PIXEL_LEVEL_SCORE", "MEAN_INTENSITY", "MANUAL", "IMAGE", "NONE");
        assertTrue(pixelManual.contains("MANUAL_INTENSITY_BOUNDARIES_BY_MARKER"));
        assertFalse(pixelManual.contains("MANUAL_INTENSITY_THRESHOLDS"));
        assertFalse(pixelManual.contains("BACKGROUND_MODE"));
    }

    @Test
    void colocalizationPixelFractionDependencyKeepsCutoffBesidePositivityMethod() {
        Set<String> mean = PipelineLauncher.colocalizationThresholdEnabledRows("LEGACY_BINARY", "MEAN_INTENSITY", "LOG_KDE_VALLEY", "IMAGE", "NONE");
        Set<String> fraction = PipelineLauncher.colocalizationThresholdEnabledRows("LEGACY_BINARY", "PIXEL_POSITIVE_FRACTION", "LOG_KDE_VALLEY", "IMAGE", "NONE");

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
        assertTrue(source.contains("available.setPrefSize(\n"
                + "                    SelectionGeometry.PROJECT_LIST_WIDTH,\n"
                + "                    SelectionGeometry.PROJECT_LIST_HEIGHT);"));
        assertTrue(source.contains("chosen.setPrefSize(\n"
                + "                    SelectionGeometry.PROJECT_LIST_WIDTH,\n"
                + "                    SelectionGeometry.PROJECT_LIST_HEIGHT);"));
        assertTrue(source.contains("VBox moveButtons = new VBox(\n"
                + "                    SelectionGeometry.TRANSFER_BUTTON_GAP,"));
        assertTrue(source.contains("HBox chooser = new HBox(\n"
                + "                    SelectionGeometry.DUAL_LIST_GAP,"));
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
        assertFalse(source.contains("available.setPrefSize(300.0, 320.0)"));
        assertFalse(source.contains("chosen.setPrefSize(300.0, 320.0)"));
        assertFalse(source.contains("new HBox(12.0, availableBox, moveButtons, chosenBox)"));
        assertFalse(source.contains("new VBox(10.0, filter, chooser)"));
        assertFalse(source.contains("return new VBox(5.0, label, list)"));
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
    void runLogParserPreservesExplicitSourcesBeforeInference() {
        List<RunLogEntry> entries = RunLogParser.parse("""
                [ASTRA][INFO] ASTRA run started.
                [Script][NEUTRAL] SMA-AF647  Region scored: [1/10] 'ASTRA SMA Media Search 4'
                [QuPath][INFO] Writing object hierarchy with 1084 object(s)...
                [Cellpose][WARNING] the '--diam_mean' flag is deprecated
                [Python][ERROR] Traceback (most recent call last):
                [System][CANCELLED] Cancellation requested.
                """, RunLogSource.QUPATH, RunLogSeverity.NEUTRAL);

        assertEquals(6, entries.size());
        assertEquals(RunLogSource.ASTRA, entries.get(0).source());
        assertEquals(RunLogSeverity.INFO, entries.get(0).severity());
        assertEquals(RunLogSource.SCRIPT, entries.get(1).source());
        assertEquals(RunLogSeverity.NEUTRAL, entries.get(1).severity());
        assertTrue(entries.get(1).text().contains("ASTRA SMA Media Search"));
        assertEquals(RunLogSource.QUPATH, entries.get(2).source());
        assertEquals(RunLogSource.CELLPOSE, entries.get(3).source());
        assertEquals(RunLogSeverity.WARNING, entries.get(3).severity());
        assertEquals(RunLogSource.PYTHON, entries.get(4).source());
        assertEquals(RunLogSeverity.ERROR, entries.get(4).severity());
        assertEquals(RunLogSource.SYSTEM, entries.get(5).source());
        assertEquals(RunLogSeverity.CANCELLED, entries.get(5).severity());
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
        assertTrue(view.contains("styleCopyButton(copy, true)"));
        assertTrue(view.contains("addStyleClass(hiddenToggle, \"astra-log-disclosure-button\")"));
        assertTrue(view.contains("StackPane scrollFrame = new StackPane(scroll, topFade);"));
        assertTrue(view.contains("topFade.prefWidthProperty().bind(scrollFrame.widthProperty());"));
        assertTrue(view.contains("topFade.prefHeightProperty().bind(scrollFrame.heightProperty());"));
        assertTrue(view.contains("StackPane.setAlignment(topFade, Pos.TOP_CENTER);"));
        assertTrue(view.contains("addStyleClass(topFade, \"astra-log-scroll-top-fade\")"));
        assertTrue(view.contains("topFade.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE)"));
        assertTrue(view.contains("addStyleClass(tab, \"astra-log-source-tab\")"));
        assertTrue(view.contains("addStyleClass(currentGroupBody, \"astra-log-source-block\")"));
        assertTrue(view.contains("addStyleClass(card, \"astra-log-message-card\")"));
        assertTrue(view.contains("addStyleClass(title, \"astra-log-card-title\")"));
        assertTrue(view.contains("addStyleClass(card, \"astra-log-key-value-card\")"));
        assertTrue(view.contains("addStyleClass(card, \"astra-log-command-card\")"));
        assertTrue(view.contains("addStyleClass(row, \"astra-log-metric-row\")"));
        assertTrue(view.contains("addStyleClass(badge, \"astra-log-metric-badge\")"));
        assertTrue(view.contains("addStyleClass(label, \"astra-log-timeline-label\")"));
        assertTrue(view.contains("addStyleClass(title, \"astra-log-failure-title\")"));
        assertFalse(view.contains("copy.setStyle(copiedLogButtonStyle())"));
        assertFalse(view.contains("currentHiddenToggle.setStyle(disclosureButtonStyle())"));
        assertFalse(view.contains("title.setStyle(\"-fx-font-family: \" + FONT_STACK"));
        assertFalse(view.contains("badge.setStyle(\"-fx-font-family: \" + FONT_STACK"));
        assertFalse(view.contains("card.setStyle(\"-fx-background-color: #102733"));
        assertFalse(view.contains("card.setStyle(\"-fx-background-color: #121d27"));
        assertFalse(view.contains("sourceTabStyle(source)"));
        assertFalse(view.contains("sourceBlockStyle(source)"));
        assertFalse(view.contains("cardStyle("));
        assertFalse(view.contains("lineTextStyle("));
        assertTrue(view.contains("startSourceGroup(entry.source())"));
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

    private static Class<?> nestedClass(Class<?> parent, String simpleName) {
        for (Class<?> nested : parent.getDeclaredClasses()) {
            if (simpleName.equals(nested.getSimpleName())) {
                return nested;
            }
        }
        throw new IllegalArgumentException("No nested class " + simpleName + " in " + parent.getName());
    }

    private static double staticDouble(Class<?> type, String fieldName) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getDouble(null);
    }

    private static double cssPx(String css, String property) {
        java.util.regex.Matcher matcher = Pattern.compile(Pattern.quote(property) + ": ([0-9]+(?:\\.[0-9]+)?)px;")
                .matcher(css);
        if (!matcher.find()) {
            throw new IllegalArgumentException("No CSS px declaration for " + property);
        }
        return Double.parseDouble(matcher.group(1));
    }

    private static String cssBlock(String css, String selector) {
        int start = css.indexOf(selector + " {");
        if (start < 0) {
            throw new IllegalArgumentException("No CSS block for " + selector);
        }
        int end = css.indexOf("\n}", start);
        if (end < 0) {
            throw new IllegalArgumentException("Unclosed CSS block for " + selector);
        }
        return css.substring(start, end);
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
