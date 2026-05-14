package qupath.ext.astra;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.ColorTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.logging.LogManager;
import qupath.lib.gui.logging.TextAppendable;
import qupath.lib.gui.scripting.languages.GroovyLanguage;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.scripting.ScriptParameters;

import javax.script.ScriptException;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Presents a lightweight ASTRA configuration dialog and executes the selected
 * bundled pipeline script with the user's edited constants.
 *
 * <p>The launcher deliberately treats the base ASTRA Groovy script as the
 * source of truth.  It extracts editable top-level {@code final} declarations,
 * edits only those declaration values, and then executes the resulting script
 * through QuPath's Groovy scripting runtime.  This keeps the extension-side GUI
 * generic while avoiding a second divergent configuration schema.</p>
 */
final class AstraPipelineLauncher {

    private static final Logger logger = LoggerFactory.getLogger(AstraPipelineLauncher.class);
    private static final Pattern DECLARATION_PATTERN = Pattern.compile(
            "^final\\s+(String|boolean|int|double|List|Map)\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*(.*)$"
    );
    private static final String FONT_STACK = "\"Aptos Display\", \"Segoe UI\", \"Inter\", \"Helvetica Neue\", Arial, sans-serif";
    private static final String MONO_FONT_STACK = "\"JetBrains Mono\", \"SF Mono\", Consolas, monospace";
    private static final String INK = "#172431";
    private static final String MUTED = "#5f7080";
    private static final String PAPER = "#f4f7f8";
    private static final String PANEL = "#ffffff";
    private static final String TEAL = "#1f7a7a";
    private static final String TEAL_DARK = "#0d4f55";
    private static final String CORAL = "#d9604c";
    private static final String GOLD = "#d4a72c";
    private static final String CONTROL_BORDER = "#7fa3ad";
    private static final String PREF_SCHEMA_ID = "__schema_id";
    private static final Preferences SETTINGS = Preferences.userNodeForPackage(AstraPipelineLauncher.class).node("pipeline-settings");

    private AstraPipelineLauncher() {
        throw new AssertionError("No instances");
    }

    /**
     * Opens the configuration dialog and executes the configured pipeline when
     * the user confirms.
     *
     * @param qupath active QuPath GUI.
     * @param scriptName user-facing script name.
     * @param scriptText bundled script text.
     */
    static void configureAndRun(QuPathGUI qupath, String scriptName, String scriptText) {
        Objects.requireNonNull(qupath, "qupath");
        Objects.requireNonNull(scriptName, "scriptName");
        Objects.requireNonNull(scriptText, "scriptText");

        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> configureAndRun(qupath, scriptName, scriptText));
            return;
        }

        List<EditableConstant> constants = extractEditableConstants(scriptText);
        if (constants.isEmpty()) {
            Dialogs.showErrorMessage("ASTRA " + scriptName, "No editable ASTRA configuration constants were found.");
            return;
        }
        String schemaId = schemaIdentity(constants);
        PersistentApplyResult persisted = applyPersistentSettings(scriptName, schemaId, constants);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(qupath.getStage());
        dialog.setTitle(scriptName);
        dialog.setHeaderText(null);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        RunFeedback feedback = new RunFeedback(scriptName);
        dialog.getDialogPane().setContent(createContent(qupath, scriptName, constants, !persisted.restored(), feedback, schemaId));
        if (persisted.schemaReset()) {
            feedback.warn("Saved settings were reset because this script schema changed.");
        }
        dialog.getDialogPane().setStyle("-fx-background-color: " + PAPER + "; -fx-font-family: " + FONT_STACK + ";");
        dialog.setResizable(true);

        Button runButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        runButton.setText("Run");
        runButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            String configuredScript;
            try {
                configuredScript = applyConstants(scriptText, constants);
            } catch (RuntimeException e) {
                Dialogs.showErrorMessage("ASTRA " + scriptName, e.getMessage());
                return;
            }
            if (requiresAllImagesConfirmation(constants) && !confirmAllImagesRun(qupath, scriptName)) {
                feedback.warn("Project-wide run cancelled before execution.");
                return;
            }
            if (requiresProvisionalVascularConfirmation(constants) && !confirmProvisionalVascularAutomation(qupath, scriptName)) {
                feedback.warn("Provisional vascular automation cancelled before execution.");
                return;
            }
            savePersistentSettings(scriptName, schemaId, constants);
            feedback.info(finalConfigSummary(scriptName, schemaId, constants));
            executeAsync(qupath, scriptName, configuredScript, feedback, runButton);
        });

        dialog.showAndWait();
    }

    /**
     * Extracts editable top-level constants from the script header.
     *
     * @param scriptText source script.
     * @return editable constants in source order.
     */
    static List<EditableConstant> extractEditableConstants(String scriptText) {
        String[] lines = scriptText.split("\\R", -1);
        List<EditableConstant> out = new ArrayList<>();
        Map<String, List<String>> scriptOptions = new LinkedHashMap<>();
        Map<String, String> scriptHelp = new LinkedHashMap<>();
        int offset = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("final boolean USE_LOCAL_CLASSES") || line.startsWith("final Map cfg")) {
                break;
            }

            Matcher matcher = DECLARATION_PATTERN.matcher(line.trim());
            if (!matcher.matches()) {
                offset += line.length() + 1;
                continue;
            }

            String type = matcher.group(1);
            String name = matcher.group(2);
            if (name.startsWith("__") || "USE_LOCAL_CLASSES".equals(name)) {
                offset += line.length() + 1;
                continue;
            }

            int start = offset + line.indexOf("final ");
            int endLine = i;
            StringBuilder declaration = new StringBuilder(line);
            if ("List".equals(type) || "Map".equals(type)) {
                int balance = bracketBalance(line);
                while (balance > 0 && endLine + 1 < lines.length) {
                    endLine++;
                    String next = lines[endLine];
                    declaration.append('\n').append(next);
                    balance += bracketBalance(next);
                }
            }

            String fullDeclaration = declaration.toString();
            int valueStart = fullDeclaration.indexOf('=');
            if (valueStart < 0) {
                offset += line.length() + 1;
                continue;
            }
            String value = fullDeclaration.substring(valueStart + 1).trim();
            int comment = firstTopLevelComment(value);
            String suffix = "";
            if (comment >= 0) {
                suffix = value.substring(comment);
                value = value.substring(0, comment).trim();
            }

            int end = offset;
            for (int j = i; j <= endLine; j++) {
                end += lines[j].length() + 1;
            }
            if (end > scriptText.length()) {
                end = scriptText.length();
            }

            if ("List".equals(type) && name.endsWith("_OPTIONS")) {
                String targetName = name.substring(0, name.length() - "_OPTIONS".length());
                scriptOptions.put(targetName, parseStringOptions(value));
            } else if ("String".equals(type) && name.endsWith("_HELP")) {
                String targetName = name.substring(0, name.length() - "_HELP".length());
                scriptHelp.put(targetName, EditableConstant.stripStringQuotes("String", value));
            } else {
                out.add(new EditableConstant(type, name, value, suffix, start, end, isAdvanced(name), scriptOptions.get(name), scriptHelp.get(name)));
            }
            for (int j = i; j <= endLine; j++) {
                if (j > i) {
                    offset += lines[j].length() + 1;
                }
            }
            i = endLine;
            offset += line.length() + 1;
        }

        return out;
    }

    private static List<String> parseStringOptions(String value) {
        String text = value == null ? "" : value.trim();
        if (!text.startsWith("[") || !text.endsWith("]")) {
            return List.of();
        }
        List<String> options = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        char quote = 0;
        for (int i = 1; i < text.length() - 1; i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (ch == '\\' && i + 1 < text.length() - 1) {
                    current.append(text.charAt(i + 1));
                    i++;
                } else if (ch == quote) {
                    options.add(current.toString());
                    current.setLength(0);
                    inString = false;
                    quote = 0;
                } else {
                    current.append(ch);
                }
            } else if (ch == '"' || ch == '\'') {
                inString = true;
                quote = ch;
            }
        }
        return options;
    }

    /**
     * Applies edited constants to the script.
     *
     * @param scriptText original script text.
     * @param constants edited constants.
     * @return configured script text.
     */
    static String applyConstants(String scriptText, List<EditableConstant> constants) {
        StringBuilder out = new StringBuilder(scriptText);
        List<EditableConstant> reversed = new ArrayList<>(constants);
        Collections.reverse(reversed);
        for (EditableConstant constant : reversed) {
            out.replace(constant.start, constant.end, constant.renderDeclaration());
        }
        return out.toString();
    }

    static PersistentApplyResult applyPersistentSettings(String scriptName, String schemaId, List<EditableConstant> constants) {
        Preferences node = settingsNode(scriptName);
        String storedSchemaId = node.get(PREF_SCHEMA_ID, null);
        if (storedSchemaId != null && !storedSchemaId.equals(schemaId)) {
            clearPersistentSettings(scriptName);
            return new PersistentApplyResult(false, true);
        }
        if (storedSchemaId == null && hasStoredValues(node, constants)) {
            clearPersistentSettings(scriptName);
            return new PersistentApplyResult(false, true);
        }
        boolean restored = false;
        for (EditableConstant constant : constants) {
            String value = node.get(constant.name, null);
            if (value != null) {
                constant.setDisplayValue(value);
                restored = true;
            }
        }
        return new PersistentApplyResult(restored, false);
    }

    private static boolean hasStoredValues(Preferences node, List<EditableConstant> constants) {
        for (EditableConstant constant : constants) {
            if (node.get(constant.name, null) != null) {
                return true;
            }
        }
        return false;
    }

    private static void savePersistentSettings(String scriptName, String schemaId, List<EditableConstant> constants) {
        Preferences node = settingsNode(scriptName);
        node.put(PREF_SCHEMA_ID, schemaId);
        for (EditableConstant constant : constants) {
            try {
                node.put(constant.name, constant.currentDisplayValue());
            } catch (RuntimeException e) {
                logger.debug("Skipping invalid in-progress ASTRA setting {} for {}", constant.name, scriptName);
            }
        }
    }

    static void clearPersistentSettings(String scriptName) {
        try {
            settingsNode(scriptName).clear();
        } catch (BackingStoreException e) {
            logger.warn("Unable to clear ASTRA settings for {}", scriptName, e);
        }
    }

    static Preferences settingsNode(String scriptName) {
        return SETTINGS.node(scriptName.replaceAll("[^A-Za-z0-9_.-]", "_"));
    }

    static String schemaIdentity(List<EditableConstant> constants) {
        StringBuilder schema = new StringBuilder();
        for (EditableConstant constant : constants) {
            schema.append(constant.name).append('\t')
                    .append(constant.type).append('\t')
                    .append(constant.value).append('\t')
                    .append(String.join(",", constant.options)).append('\t')
                    .append(constant.helpText()).append('\n');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(schema.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable for ASTRA GUI schema identity.", e);
        }
    }

    static String finalConfigSummary(String scriptName, String schemaId, List<EditableConstant> constants) {
        Map<String, EditableConstant> byName = new LinkedHashMap<>();
        constants.forEach(c -> byName.put(c.name, c));
        List<String> lines = new ArrayList<>();
        lines.add("Resolved configuration:");
        lines.add("  pipeline: " + scriptName);
        lines.add("  schema: " + schemaId.substring(0, Math.min(12, schemaId.length())));
        appendSummary(lines, byName, "IMAGE_SCOPE", "  image scope");
        appendSummary(lines, byName, "SELECTED_IMAGE_NAMES", "  selected images");
        appendSummary(lines, byName, "DETECTION_TARGET", "  detection target");
        appendEffectiveModelSummary(lines, byName, "NUCLEUS", "nucleus");
        appendEffectiveModelSummary(lines, byName, "CELL", "cell");
        appendSummary(lines, byName, "NUCLEUS_SEGMENTATION_CHANNELS", "  nucleus segmentation channels");
        appendSummary(lines, byName, "CELL_SEGMENTATION_CHANNELS", "  cell segmentation channels");
        appendSummary(lines, byName, "CHANNELS_FOR_NUCLEUS", "  nucleus channels");
        appendSummary(lines, byName, "CHANNELS_FOR_CELL", "  cell channels");
        appendSummary(lines, byName, "COLOCALIZATION_CHECKS", "  colocalization checks");
        appendSummary(lines, byName, "THRESHOLD_MODE", "  threshold mode");
        appendSummary(lines, byName, "THRESHOLD_EXCLUDE_MARKERS", "  threshold exclusions");
        appendSummary(lines, byName, "BACKGROUND_MODE", "  background mode");
        appendSummary(lines, byName, "EXPORT_RESULTS", "  export results");
        appendSummary(lines, byName, "RESULTS_FOLDER", "  results folder");
        return String.join("\n", lines);
    }

    private static void appendEffectiveModelSummary(List<String> lines, Map<String, EditableConstant> constants, String target, String label) {
        String detectionTarget = rawString(constants, "DETECTION_TARGET", "BOTH");
        if ("NUCLEUS".equals(target) && "CELL".equals(detectionTarget)) {
            lines.add("  effective " + label + " model: not used for DETECTION_TARGET=CELL");
            return;
        }
        if ("CELL".equals(target) && "NUCLEUS".equals(detectionTarget)) {
            lines.add("  effective " + label + " model: not used for DETECTION_TARGET=NUCLEUS");
            return;
        }
        String prefix = "NUCLEUS".equals(target) ? "NUC_" : "CELL_";
        String specificSource = rawString(constants, prefix + "MODEL_SOURCE", "");
        String source = specificSource.isBlank() ? rawString(constants, "MODEL_SOURCE", "") : specificSource;
        boolean inherited = specificSource.isBlank();
        String valueName = rawString(constants, prefix + "MODEL_NAME", "");
        String valueFile = rawString(constants, prefix + "MODEL_FILE", "");
        String sharedName = rawString(constants, "MODEL_NAME", "");
        String sharedFile = rawString(constants, "MODEL_FILE", "");
        String value = "FILE".equals(source)
                ? (valueFile.isBlank() ? sharedFile : valueFile)
                : (valueName.isBlank() ? sharedName : valueName);
        lines.add("  effective " + label + " model source: " + source + (inherited ? " (inherited)" : " (target-specific)"));
        lines.add("  effective " + label + " model: " + value);
    }

    private static String rawString(Map<String, EditableConstant> constants, String name, String fallback) {
        EditableConstant constant = constants.get(name);
        if (constant == null) {
            return fallback;
        }
        String raw = constant.currentDisplayValue();
        return EditableConstant.stripOuterQuotes(raw == null ? "" : raw.trim());
    }

    private static void appendSummary(List<String> lines, Map<String, EditableConstant> constants, String name, String label) {
        EditableConstant constant = constants.get(name);
        if (constant != null) {
            lines.add(label + ": " + constant.currentDisplayValue());
        }
    }

    static boolean requiresAllImagesConfirmation(List<EditableConstant> constants) {
        for (EditableConstant constant : constants) {
            if ("IMAGE_SCOPE".equals(constant.name)) {
                return "ALL_IMAGES".equals(constant.optionValue());
            }
        }
        return false;
    }

    static boolean requiresProvisionalVascularConfirmation(List<EditableConstant> constants) {
        for (EditableConstant constant : constants) {
            if ("MODES_TO_RUN".equals(constant.name)) {
                List<String> modes = EditableConstant.csvValues(constant.currentDisplayValue());
                return modes.contains("AUTO_BUILD_CLASSIFIERS") || modes.contains("AUTO_SELECT_ROIS");
            }
        }
        return false;
    }

    static List<String> targetModelControlNames(String detectionTarget) {
        String target = detectionTarget == null ? "BOTH" : detectionTarget.trim().toUpperCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        if (!"CELL".equals(target)) {
            names.addAll(List.of("NUC_MODEL_SOURCE", "NUC_MODEL_NAME", "NUC_MODEL_FILE"));
        }
        if (!"NUCLEUS".equals(target)) {
            names.addAll(List.of("CELL_MODEL_SOURCE", "CELL_MODEL_NAME", "CELL_MODEL_FILE"));
        }
        return names;
    }

    record ColocalizationPanelState(boolean showNucleusModel, boolean showCellModel, boolean showNucleusSegmentation, boolean showCellSegmentation) {
    }

    static ColocalizationPanelState colocalizationPanelState(String detectionTarget) {
        String target = detectionTarget == null ? "BOTH" : detectionTarget.trim().toUpperCase(Locale.ROOT);
        boolean showNucleus = !"CELL".equals(target);
        boolean showCell = !"NUCLEUS".equals(target);
        return new ColocalizationPanelState(showNucleus, showCell, showNucleus, showCell);
    }

    static List<String> synchronizedThresholdExclusions(List<String> selectedExclusions, List<ColocalizationCheck> checks) {
        List<String> markerKeys = markerKeysFromChecks(checks);
        if (selectedExclusions == null || selectedExclusions.isEmpty()) {
            return List.of();
        }
        return selectedExclusions.stream()
                .filter(markerKeys::contains)
                .distinct()
                .toList();
    }

    private static boolean confirmAllImagesRun(QuPathGUI qupath, String scriptName) {
        int imageCount = qupath.getProject() == null ? 0 : qupath.getProject().getImageList().size();
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(qupath.getStage());
        alert.setTitle("ASTRA " + scriptName);
        alert.setHeaderText("Run across the full project?");
        alert.setContentText("IMAGE_SCOPE is ALL_IMAGES. ASTRA will run this pipeline on " + imageCount + " project entries.");
        alert.getDialogPane().setStyle("-fx-background-color: " + PAPER + "; -fx-font-family: " + FONT_STACK + ";");
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    private static boolean confirmProvisionalVascularAutomation(QuPathGUI qupath, String scriptName) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(qupath.getStage());
        alert.setTitle("ASTRA " + scriptName);
        alert.setHeaderText("Run provisional vascular automation?");
        alert.setContentText("""
                These vascular automation modes are provisional and review-required.
                They are not equivalent to the validated manual ROI/Trace baseline workflow.
                Proceed only if this is intentional.""");
        alert.getDialogPane().setStyle("-fx-background-color: " + PAPER + "; -fx-font-family: " + FONT_STACK + ";");
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    private static Node createContent(QuPathGUI qupath, String scriptName, List<EditableConstant> constants, boolean applyChannelDefaults, RunFeedback feedback, String schemaId) {
        List<ImageChannel> imageChannels = imageChannels(qupath);
        if (applyChannelDefaults) {
            applyImageChannelDefaults(constants, imageChannels);
        }
        constants.forEach(EditableConstant::markDefault);

        VBox root = new VBox(14.0);
        root.setPadding(new Insets(0));
        root.setStyle("-fx-background-color: " + PAPER + "; -fx-font-family: " + FONT_STACK + ";");

        VBox header = new VBox(12.0);
        header.setPadding(new Insets(22.0, 24.0, 20.0, 24.0));
        header.setStyle("-fx-background-color: linear-gradient(to right, #102a3a, #1f7a7a 62%, #d9604c);");
        HBox titleRow = new HBox(12.0);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label(scriptName);
        title.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 24px; -fx-font-weight: 800; -fx-text-fill: white;");
        Button reset = new Button("Reset settings");
        reset.setFocusTraversable(false);
        reset.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 11px; -fx-font-weight: 900; -fx-background-color: rgba(255,255,255,0.92); -fx-text-fill: " + TEAL_DARK + "; -fx-border-color: white; -fx-border-radius: 5; -fx-background-radius: 5;");
        reset.setOnAction(event -> {
            constants.forEach(EditableConstant::resetEditor);
            clearPersistentSettings(scriptName);
            feedback.info("Settings reset to the current image-aware defaults.");
        });
        titleRow.getChildren().addAll(title, reset);
        Label subtitle = new Label(descriptionFor(scriptName));
        subtitle.setWrapText(true);
        subtitle.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 13px; -fx-text-fill: #e3f4f1;");
        header.getChildren().addAll(titleRow, subtitle, createPipelineFlow(scriptName));

        VBox body = new VBox(14.0);
        body.setPadding(new Insets(0, 18.0, 18.0, 18.0));
        body.getChildren().add(createChannelPanel(imageChannels));
        boolean colocalization = isColocalizationConfig(constants);
        if (colocalization) {
            body.getChildren().add(createColocalizationPanel(scriptName, constants, imageChannels, schemaId));
        }

        VBox basic = sectionShell("Basic", "Start here. These are the normal run controls: target, scope, channels, model source, thresholds, and outputs.");
        for (String group : orderedGroups(constants, false)) {
            List<EditableConstant> groupConstants = constants.stream()
                    .filter(c -> !c.advanced && group.equals(groupFor(c.name)))
                    .filter(c -> !isHandledByColocalizationPanel(c.name, colocalization))
                    .toList();
            if (!groupConstants.isEmpty()) {
                basic.getChildren().add(createSection(scriptName, group, groupConstants, true, constants, schemaId));
            }
        }

        VBox advanced = sectionShell("Advanced", "Defaults are intentionally conservative. Open these only for deliberate tuning, diagnostics, or publication-specific overrides.");
        for (String group : orderedGroups(constants, true)) {
            List<EditableConstant> groupConstants = constants.stream()
                    .filter(c -> c.advanced && group.equals(groupFor(c.name)))
                    .filter(c -> !isHandledByColocalizationPanel(c.name, colocalization))
                    .toList();
            if (!groupConstants.isEmpty()) {
                advanced.getChildren().add(createSection(scriptName, group, groupConstants, false, constants, schemaId));
            }
        }

        body.getChildren().addAll(basic, advanced);

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(720.0);
        scroll.setPrefViewportHeight(700.0);
        scroll.setStyle("-fx-background: " + PAPER + "; -fx-background-color: " + PAPER + ";");
        HBox.setHgrow(scroll, Priority.ALWAYS);

        HBox workspace = new HBox(14.0);
        workspace.setPadding(new Insets(0, 18.0, 18.0, 18.0));
        workspace.setStyle("-fx-background-color: " + PAPER + ";");
        Node feedbackNode = feedback.node();
        workspace.getChildren().addAll(scroll, feedbackNode);
        VBox.setVgrow(workspace, Priority.ALWAYS);

        root.getChildren().addAll(header, workspace);
        return root;
    }

    private static VBox sectionShell(String titleText, String subtitleText) {
        VBox box = new VBox(9.0);
        box.setPadding(new Insets(14.0));
        box.setStyle("-fx-background-color: " + PANEL + "; -fx-border-color: #cfdce1; -fx-border-radius: 7; -fx-background-radius: 7;");
        Label title = new Label(titleText);
        title.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 18px; -fx-font-weight: 800; -fx-text-fill: " + INK + ";");
        Label subtitle = new Label(subtitleText);
        subtitle.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-text-fill: " + MUTED + ";");
        subtitle.setWrapText(true);
        box.getChildren().addAll(title, subtitle);
        return box;
    }

    private static boolean isColocalizationConfig(List<EditableConstant> constants) {
        return constants.stream().anyMatch(c -> "COLOCALIZATION_CHECKS".equals(c.name))
                && constants.stream().anyMatch(c -> "DETECTION_TARGET".equals(c.name));
    }

    private static boolean isHandledByColocalizationPanel(String name, boolean colocalization) {
        if (!colocalization) {
            return false;
        }
        return Set.of(
                "NUC_MODEL_SOURCE", "NUC_MODEL_NAME", "NUC_MODEL_FILE",
                "CELL_MODEL_SOURCE", "CELL_MODEL_NAME", "CELL_MODEL_FILE",
                "NUCLEUS_SEGMENTATION_CHANNELS", "CELL_SEGMENTATION_CHANNELS",
                "COLOCALIZATION_CHECKS", "THRESHOLD_EXCLUDE_MARKERS"
        ).contains(name);
    }

    private static VBox createColocalizationPanel(String scriptName, List<EditableConstant> constants, List<ImageChannel> imageChannels, String schemaId) {
        Map<String, EditableConstant> byName = new LinkedHashMap<>();
        constants.forEach(c -> byName.put(c.name, c));
        List<String> channelNames = imageChannels.stream().map(ImageChannel::getName).filter(Objects::nonNull).toList();

        VBox box = sectionShell("Colocalization Setup", "Target-specific controls for segmentation models, segmentation channels, marker checks, and threshold exclusions.");
        EditableConstant detectionTarget = byName.get("DETECTION_TARGET");

        VBox modelPanel = semanticCard("Target Models", "Choose nucleus and cell model initializers independently. Shared MODEL_* values remain available in Advanced only as inheritance defaults.");
        VBox nucleusModel = targetModelGroup("Nucleus model", byName.get("NUC_MODEL_SOURCE"), byName.get("NUC_MODEL_NAME"), byName.get("NUC_MODEL_FILE"), scriptName, schemaId, constants);
        VBox cellModel = targetModelGroup("Cell model", byName.get("CELL_MODEL_SOURCE"), byName.get("CELL_MODEL_NAME"), byName.get("CELL_MODEL_FILE"), scriptName, schemaId, constants);
        modelPanel.getChildren().addAll(nucleusModel, cellModel);

        VBox segmentationPanel = semanticCard("Segmentation Channels", "Checkboxes write explicit Groovy lists into the target-specific segmentation channel constants.");
        EditableConstant nucChannels = byName.get("NUCLEUS_SEGMENTATION_CHANNELS");
        EditableConstant cellChannels = byName.get("CELL_SEGMENTATION_CHANNELS");
        ChannelCheckboxEditor nucEditor = new ChannelCheckboxEditor("Nucleus segmentation channels", channelNames, nucChannels == null ? "[]" : nucChannels.displayValue);
        ChannelCheckboxEditor cellEditor = new ChannelCheckboxEditor("Cell segmentation channels", channelNames, cellChannels == null ? "[]" : cellChannels.displayValue);
        if (nucChannels != null) {
            nucChannels.setCustomEditor(nucEditor);
            nucEditor.addChangeListener(() -> savePersistentSettings(scriptName, schemaId, constants));
        }
        if (cellChannels != null) {
            cellChannels.setCustomEditor(cellEditor);
            cellEditor.addChangeListener(() -> savePersistentSettings(scriptName, schemaId, constants));
        }
        segmentationPanel.getChildren().addAll(nucEditor, cellEditor);

        VBox checksPanel = semanticCard("Colocalization Checks", "Build each positivity check from a label, one compartment, and the channels that must be positive together.");
        EditableConstant checksConstant = byName.get("COLOCALIZATION_CHECKS");
        ColocalizationChecksEditor checksEditor = new ColocalizationChecksEditor(channelNames, checksConstant == null ? "[]" : checksConstant.displayValue);
        if (checksConstant != null) {
            checksConstant.setCustomEditor(checksEditor);
            checksEditor.addChangeListener(() -> savePersistentSettings(scriptName, schemaId, constants));
        }
        checksPanel.getChildren().add(checksEditor);

        VBox exclusionPanel = semanticCard("Threshold Exclusions", "Only marker keys used by the checks above can be excluded from thresholding and threshold QC.");
        EditableConstant exclusionsConstant = byName.get("THRESHOLD_EXCLUDE_MARKERS");
        ThresholdExclusionEditor exclusionEditor = new ThresholdExclusionEditor(exclusionsConstant == null ? "[]" : exclusionsConstant.displayValue);
        exclusionEditor.refresh(markerKeysFromChecks(checksEditor.checks()));
        if (exclusionsConstant != null) {
            exclusionsConstant.setCustomEditor(exclusionEditor);
            exclusionEditor.addChangeListener(() -> savePersistentSettings(scriptName, schemaId, constants));
        }
        checksEditor.addChangeListener(() -> exclusionEditor.refresh(markerKeysFromChecks(checksEditor.checks())));
        exclusionPanel.getChildren().add(exclusionEditor);

        Runnable updateTargetVisibility = () -> {
            String target = detectionTarget == null ? "BOTH" : detectionTarget.optionValue();
            ColocalizationPanelState state = colocalizationPanelState(target);
            setNodeVisible(nucleusModel, state.showNucleusModel());
            setNodeVisible(nucEditor, state.showNucleusSegmentation());
            setNodeVisible(cellModel, state.showCellModel());
            setNodeVisible(cellEditor, state.showCellSegmentation());
        };
        if (detectionTarget != null) {
            detectionTarget.addChangeListener(updateTargetVisibility);
        }
        updateTargetVisibility.run();

        box.getChildren().addAll(modelPanel, segmentationPanel, checksPanel, exclusionPanel);
        return box;
    }

    private static VBox semanticCard(String titleText, String subtitleText) {
        VBox box = new VBox(9.0);
        box.setPadding(new Insets(12.0));
        box.setStyle("-fx-background-color: #f9fcfd; -fx-border-color: #c8dce1; -fx-border-radius: 6; -fx-background-radius: 6;");
        Label title = new Label(titleText);
        title.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 14px; -fx-font-weight: 900; -fx-text-fill: " + TEAL_DARK + ";");
        Label subtitle = new Label(subtitleText);
        subtitle.setWrapText(true);
        subtitle.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-text-fill: " + MUTED + ";");
        box.getChildren().addAll(title, subtitle);
        return box;
    }

    private static VBox targetModelGroup(String title, EditableConstant source, EditableConstant name, EditableConstant file, String scriptName, String schemaId, List<EditableConstant> allConstants) {
        VBox group = new VBox(8.0);
        group.setPadding(new Insets(10.0));
        group.setStyle("-fx-background-color: white; -fx-border-color: #d7e2e6; -fx-border-radius: 5; -fx-background-radius: 5;");
        Label label = new Label(title);
        label.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 13px; -fx-font-weight: 900; -fx-text-fill: " + INK + ";");
        group.getChildren().add(label);
        Map<String, RowNodes> rows = new LinkedHashMap<>();
        for (EditableConstant constant : Arrays.asList(source, name, file)) {
            if (constant == null) {
                continue;
            }
            HBox row = new HBox(8.0);
            row.setAlignment(Pos.CENTER_LEFT);
            Label rowLabel = new Label(prettyName(constant.name));
            rowLabel.setMinWidth(160.0);
            rowLabel.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-font-weight: 800; -fx-text-fill: " + INK + ";");
            Node editor = constant.createEditor();
            HBox.setHgrow(editor, Priority.ALWAYS);
            row.getChildren().addAll(rowLabel, editor);
            group.getChildren().add(row);
            rows.put(constant.name, new RowNodes(rowLabel, editor));
            constant.addChangeListener(() -> savePersistentSettings(scriptName, schemaId, allConstants));
        }
        Runnable update = () -> {
            String selected = source == null ? "" : source.optionValue();
            setVisible(rows, name == null ? "" : name.name, selected.isBlank() || "MODEL_NAME".equals(selected));
            setVisible(rows, file == null ? "" : file.name, "FILE".equals(selected));
        };
        if (source != null) {
            source.addChangeListener(update);
        }
        update.run();
        return group;
    }

    private static void setNodeVisible(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private static Node createPipelineFlow(String scriptName) {
        HBox flow = new HBox(8.0);
        flow.setAlignment(Pos.CENTER_LEFT);
        List<String> stages = List.of("Training", "Tuning", "Validation", "Analysis");
        String active = pipelineStage(scriptName);
        for (int i = 0; i < stages.size(); i++) {
            String stage = stages.get(i);
            Label chip = new Label(stage);
            boolean isActive = stage.equals(active);
            chip.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-font-weight: 800; -fx-padding: 7 12; " +
                    "-fx-background-radius: 16; -fx-border-radius: 16; " +
                    (isActive
                            ? "-fx-background-color: white; -fx-text-fill: " + TEAL_DARK + "; -fx-border-color: white;"
                            : "-fx-background-color: rgba(255,255,255,0.18); -fx-text-fill: #bfd3d4; -fx-border-color: rgba(255,255,255,0.22);"));
            flow.getChildren().add(chip);
            if (i < stages.size() - 1) {
                Label arrow = new Label(">");
                arrow.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 14px; -fx-font-weight: 800; -fx-text-fill: #d9e7e8;");
                flow.getChildren().add(arrow);
            }
        }
        return flow;
    }

    private static CollapsibleSection createSection(String scriptName, String title, List<EditableConstant> constants, boolean expanded, List<EditableConstant> allConstants, String schemaId) {
        GridPane grid = new GridPane();
        grid.setPadding(new Insets(14.0));
        grid.setHgap(12.0);
        grid.setVgap(10.0);
        grid.setStyle("-fx-background-color: " + PANEL + "; -fx-border-color: #d7e2e6; -fx-border-radius: 0 0 6 6; -fx-background-radius: 0 0 6 6;");

        int row = 0;
        Map<String, RowNodes> rows = new LinkedHashMap<>();
        for (EditableConstant constant : constants) {
            HBox labelBox = new HBox(7.0);
            labelBox.setAlignment(Pos.CENTER_LEFT);
            Label label = new Label(prettyName(constant.name));
            label.setMinWidth(220.0);
            label.setWrapText(true);
            label.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-font-weight: 800; -fx-text-fill: " + INK + ";");
            Button info = new Button("?");
            info.setMinSize(18.0, 18.0);
            info.setMaxSize(18.0, 18.0);
            info.setFocusTraversable(false);
            info.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-padding: 0; -fx-font-size: 11px; -fx-font-weight: 900; -fx-text-fill: white; -fx-border-color: " + TEAL + "; -fx-border-radius: 9; -fx-background-radius: 9; -fx-background-color: " + TEAL + ";");
            Tooltip tooltip = new Tooltip(constant.helpText());
            tooltip.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px;");
            info.setTooltip(tooltip);
            installReliableTooltip(info, tooltip);
            labelBox.getChildren().addAll(label, info);
            grid.add(labelBox, 0, row);

            Node editor = constant.createEditor();
            constant.addChangeListener(() -> savePersistentSettings(scriptName, schemaId, allConstants));
            GridPane.setHgrow(editor, Priority.ALWAYS);
            grid.add(editor, 1, row++);
            rows.put(constant.name, new RowNodes(labelBox, editor));
        }
        installConditionalVisibility(constants, rows);

        return new CollapsibleSection(title, grid, expanded);
    }

    private static Node createChannelPanel(List<ImageChannel> channels) {
        VBox panel = new VBox(8.0);
        panel.setPadding(new Insets(14.0));
        panel.setStyle("-fx-background-color: #fff8df; -fx-border-color: " + GOLD + "; -fx-border-radius: 7; -fx-background-radius: 7;");

        Label title = new Label("Open image channels");
        title.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 14px; -fx-font-weight: 800; -fx-text-fill: #6f5200;");

        FlowPane chips = new FlowPane(8.0, 8.0);
        chips.setStyle("-fx-padding: 2 0 0 0;");
        if (channels.isEmpty()) {
            Label empty = new Label("No image is open. Channel-dependent fields still use the script defaults.");
            empty.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-text-fill: #725f2a;");
            panel.getChildren().addAll(title, empty);
            return panel;
        }

        for (ImageChannel channel : channels) {
            HBox chip = new HBox(6.0);
            chip.setAlignment(Pos.CENTER_LEFT);
            chip.setPadding(new Insets(5.0, 8.0, 5.0, 8.0));
            chip.setStyle("-fx-background-color: white; -fx-border-color: #e1d2a1; -fx-border-radius: 14; -fx-background-radius: 14;");
            Rectangle swatch = new Rectangle(12.0, 12.0);
            swatch.setArcHeight(4.0);
            swatch.setArcWidth(4.0);
            swatch.setStyle("-fx-fill: " + channelColor(channel) + "; -fx-stroke: #31404a; -fx-stroke-width: 0.5;");
            Label name = new Label(channel.getName());
            name.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-font-weight: 800; -fx-text-fill: #24323a;");
            chip.getChildren().addAll(swatch, name);
            chips.getChildren().add(chip);
        }

        panel.getChildren().addAll(title, chips);
        return panel;
    }

    private static List<ImageChannel> imageChannels(QuPathGUI qupath) {
        if (qupath.getImageData() == null || qupath.getImageData().getServer() == null) {
            return List.of();
        }
        return qupath.getImageData().getServer().getMetadata().getChannels();
    }

    private static void applyImageChannelDefaults(List<EditableConstant> constants, List<ImageChannel> channels) {
        if (channels.isEmpty()) {
            return;
        }
        List<String> names = channels.stream().map(ImageChannel::getName).filter(Objects::nonNull).toList();
        applyImageChannelDefaultNames(constants, names);
    }

    static void applyImageChannelDefaultNames(List<EditableConstant> constants, List<String> names) {
        if (names.isEmpty()) {
            return;
        }
        Map<String, EditableConstant> byName = new LinkedHashMap<>();
        constants.forEach(c -> byName.put(c.name, c));
        for (EditableConstant constant : constants) {
            switch (constant.name) {
                case "CHANNEL_DAPI" -> constant.setDisplayString(preferredChannel(names, "DAPI", "Hoechst", "DAPI"));
                case "CHANNEL_WGA" -> constant.setDisplayString(preferredChannel(names, "AF488", "FITC", "WGA"));
                case "CHANNEL_ASMA" -> constant.setDisplayString(preferredChannel(names, "AF555", "Cy3", "aSMA", "ASMA"));
                case "CHANNEL_CD31" -> constant.setDisplayString(preferredChannel(names, "AF647", "Cy5", "CD31"));
                case "CHANNELS_FOR_NUCLEUS" -> constant.setDisplayList(List.of(preferredChannel(names, "DAPI", "Hoechst", "DAPI")));
                case "NUCLEUS_SEGMENTATION_CHANNELS" -> constant.setDisplayList(firstChannel(names));
                case "CELL_SEGMENTATION_CHANNELS" -> constant.setDisplayList(secondChannel(names));
                default -> {
                }
            }
        }
        if (isColocalizationConfig(constants)) {
            applyColocalizationImageDefaults(byName, names);
        }
    }

    static void applyColocalizationImageDefaults(Map<String, EditableConstant> constants, List<String> names) {
        if (names == null || names.isEmpty()) {
            return;
        }
        String first = names.get(0);
        String second = names.size() > 1 ? names.get(1) : null;
        String compartment = "Nucleus";
        List<String> checkChannels = second == null ? List.of(first) : List.of(first, second);
        String label = safeLabel(String.join("_AND_", checkChannels) + "_nucleus");
        String firstKey = first + "|" + compartment;
        String thresholdKey = second == null ? null : second + "|" + compartment;

        setListConstant(constants, "NUCLEUS_SEGMENTATION_CHANNELS", List.of(first));
        setListConstant(constants, "CELL_SEGMENTATION_CHANNELS", second == null ? List.of() : List.of(second));
        setRawConstant(constants, "COLOCALIZATION_CHECKS", renderColocalizationChecks(List.of(new ColocalizationCheck(label, compartment, checkChannels))));
        setListConstant(constants, "THRESHOLD_EXCLUDE_MARKERS", List.of(firstKey));
        setRawConstant(constants, "MANUAL_INTENSITY_THRESHOLDS", thresholdKey == null ? "[:]" : "[\n        " + quoteGroovy(thresholdKey) + "     : 100.0d\n]");
        setRawConstant(constants, "RANGE_THRESHOLD_FRACTION_BY_MARKER", thresholdKey == null ? "[:]" : "[\n        " + quoteGroovy(thresholdKey) + "     : 0.50d\n]");
        setRawConstant(constants, "THRESHOLD_PROVENANCE_BY_MARKER", thresholdKey == null ? "[:]" : "[\n        " + quoteGroovy(thresholdKey) + "     : \"EDIT: describe threshold source before publication use\"\n]");
        setRawConstant(constants, "BACKGROUND_SUBTRACTION_BY_CHANNEL", "[:]");
    }

    private static void setListConstant(Map<String, EditableConstant> constants, String name, List<String> values) {
        EditableConstant constant = constants.get(name);
        if (constant != null) {
            constant.setDisplayListAllowEmpty(values);
        }
    }

    private static void setRawConstant(Map<String, EditableConstant> constants, String name, String value) {
        EditableConstant constant = constants.get(name);
        if (constant != null) {
            constant.setDisplayValue(value);
        }
    }

    private static String safeLabel(String raw) {
        String label = raw == null ? "" : raw.trim().replaceAll("[^A-Za-z0-9]+", "_");
        label = label.replaceAll("^_+|_+$", "");
        return label.isBlank() ? "colocalization_nucleus" : label;
    }

    private static List<String> firstChannel(List<String> names) {
        return names.isEmpty() ? List.of() : List.of(names.get(0));
    }

    private static List<String> secondChannel(List<String> names) {
        return names.size() < 2 ? List.of() : List.of(names.get(1));
    }

    private static String preferredChannel(List<String> names, String... candidates) {
        for (String candidate : candidates) {
            for (String name : names) {
                if (name.equalsIgnoreCase(candidate)) {
                    return name;
                }
            }
        }
        for (String candidate : candidates) {
            for (String name : names) {
                if (name.toLowerCase(Locale.ROOT).contains(candidate.toLowerCase(Locale.ROOT))) {
                    return name;
                }
            }
        }
        return names.get(0);
    }

    private static String semanticButtonStyle() {
        return "-fx-font-family: " + FONT_STACK + "; -fx-font-size: 11px; -fx-font-weight: 900; " +
                "-fx-background-color: #e6f0f2; -fx-text-fill: " + TEAL_DARK + "; " +
                "-fx-border-color: #b5cbd2; -fx-border-radius: 4; -fx-background-radius: 4;";
    }

    static List<ColocalizationCheck> parseColocalizationChecks(String rawValue) {
        String raw = rawValue == null ? "" : rawValue;
        List<ColocalizationCheck> out = new ArrayList<>();
        Pattern entryPattern = Pattern.compile("(?s)\\[\\s*LABEL\\s*:\\s*\"([^\"]*)\"\\s*,\\s*COMPARTMENT\\s*:\\s*\"([^\"]*)\"\\s*,\\s*CHANNELS\\s*:\\s*\\[(.*?)\\]\\s*\\]");
        Matcher matcher = entryPattern.matcher(raw);
        while (matcher.find()) {
            out.add(new ColocalizationCheck(
                    matcher.group(1),
                    matcher.group(2),
                    EditableConstant.csvValues("[" + matcher.group(3) + "]")
            ));
        }
        return out;
    }

    static String renderColocalizationChecks(List<ColocalizationCheck> checks) {
        if (checks == null || checks.isEmpty()) {
            return "[]";
        }
        StringBuilder out = new StringBuilder("[\n");
        for (ColocalizationCheck check : checks) {
            out.append("        [\n")
                    .append("                LABEL      : ").append(quoteGroovy(check.label())).append(",\n")
                    .append("                COMPARTMENT: ").append(quoteGroovy(check.compartment())).append(",\n")
                    .append("                CHANNELS   : ").append(renderStringList(check.channels())).append("\n")
                    .append("        ],\n");
        }
        out.append("]");
        return out.toString();
    }

    static List<String> markerKeysFromChecks(List<ColocalizationCheck> checks) {
        List<String> keys = new ArrayList<>();
        if (checks == null) {
            return keys;
        }
        for (ColocalizationCheck check : checks) {
            String compartment = check.compartment() == null ? "" : check.compartment().trim();
            for (String channel : check.channels()) {
                String marker = channel == null ? "" : channel.trim();
                if (!marker.isBlank() && !compartment.isBlank()) {
                    String key = marker + "|" + compartment;
                    if (!keys.contains(key)) {
                        keys.add(key);
                    }
                }
            }
        }
        return keys;
    }

    static String renderStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return "[" + values.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(AstraPipelineLauncher::quoteGroovy)
                .reduce((a, b) -> a + ", " + b)
                .orElse("") + "]";
    }

    private static String quoteGroovy(String value) {
        String clean = value == null ? "" : value;
        return "\"" + clean.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void installReliableTooltip(Button info, Tooltip tooltip) {
        info.addEventHandler(MouseEvent.MOUSE_ENTERED, event -> {
            var point = info.localToScreen(info.getWidth() + 8.0, info.getHeight() / 2.0);
            if (point != null) {
                tooltip.show(info, point.getX(), point.getY());
            }
        });
        info.addEventHandler(MouseEvent.MOUSE_EXITED, event -> tooltip.hide());
    }

    private static void installConditionalVisibility(List<EditableConstant> constants, Map<String, RowNodes> rows) {
        Map<String, EditableConstant> byName = new LinkedHashMap<>();
        constants.forEach(c -> byName.put(c.name, c));
        Runnable update = () -> {
            setVisible(rows, "MODEL_NAME", isSelected(byName, "MODEL_SOURCE", "MODEL_NAME"));
            setVisible(rows, "MODEL_FILE", isSelected(byName, "MODEL_SOURCE", "FILE"));
            setVisible(rows, "NUC_MODEL_NAME", isSelected(byName, "NUC_MODEL_SOURCE", "MODEL_NAME"));
            setVisible(rows, "NUC_MODEL_FILE", isSelected(byName, "NUC_MODEL_SOURCE", "FILE"));
            setVisible(rows, "CELL_MODEL_NAME", isSelected(byName, "CELL_MODEL_SOURCE", "MODEL_NAME"));
            setVisible(rows, "CELL_MODEL_FILE", isSelected(byName, "CELL_MODEL_SOURCE", "FILE"));
            setVisible(rows, "BEST_PARAMS_FILE", isSelected(byName, "PARAM_SOURCE", "BEST_PARAMS_FILE"));
            setVisible(rows, "NUC_BEST_PARAMS_FILE", isSelected(byName, "NUC_PARAM_SOURCE", "BEST_PARAMS_FILE"));
            setVisible(rows, "CELL_BEST_PARAMS_FILE", isSelected(byName, "CELL_PARAM_SOURCE", "BEST_PARAMS_FILE"));
            setVisible(rows, "SELECTED_IMAGE_NAMES", isSelected(byName, "IMAGE_SCOPE", "SELECTED_IMAGES_BY_NAME"));
            setVisible(rows, "MATCH_SELECTED_IMAGE_NAMES_AGAINST_ORIGINAL", isSelected(byName, "IMAGE_SCOPE", "SELECTED_IMAGES_BY_NAME"));
            setVisible(rows, "MANUAL_INTENSITY_THRESHOLDS", isSelected(byName, "THRESHOLD_MODE", "MANUAL"));
            setVisible(rows, "THRESHOLD_PROVENANCE_BY_MARKER", isSelected(byName, "THRESHOLD_MODE", "MANUAL"));
            setVisible(rows, "RANGE_THRESHOLD_FRACTION_BY_MARKER", isSelected(byName, "THRESHOLD_MODE", "RANGE_PERCENT"));
            setVisible(rows, "BACKGROUND_SUBTRACTION_BY_CHANNEL", isSelected(byName, "BACKGROUND_MODE", "MANUAL_OFFSET"));
            setVisible(rows, "MODES_TO_RUN", !isChecked(byName, "RESET_BASELINE"));
        };
        byName.values().forEach(c -> c.addOptionListener(update));
        byName.values().forEach(c -> c.addChangeListener(update));
        update.run();
    }

    private static boolean isSelected(Map<String, EditableConstant> constants, String name, String expected) {
        EditableConstant constant = constants.get(name);
        return constant == null || expected.equals(constant.optionValue());
    }

    private static boolean isChecked(Map<String, EditableConstant> constants, String name) {
        EditableConstant constant = constants.get(name);
        return constant != null && Boolean.parseBoolean(constant.optionValue());
    }

    private static void setVisible(Map<String, RowNodes> rows, String name, boolean visible) {
        RowNodes row = rows.get(name);
        if (row == null) {
            return;
        }
        row.label.setVisible(visible);
        row.label.setManaged(visible);
        row.editor.setVisible(visible);
        row.editor.setManaged(visible);
    }

    private static void executeAsync(QuPathGUI qupath, String scriptName, String configuredScript, RunFeedback feedback, Button runButton) {
        feedback.start();
        runButton.setDisable(true);
        Future<?> future = qupath.getThreadPoolManager().getSingleThreadExecutor(AstraPipelineLauncher.class).submit(() -> {
            try (RunLogCapture ignored = RunLogCapture.attach(feedback::appendLogText)) {
                logger.info("ASTRA {} started from configuration dialog.", scriptName);
                feedback.info("Started " + scriptName + ".");
                ScriptParameters params = ScriptParameters.builder()
                        .setScript(configuredScript)
                        .setProject(qupath.getProject())
                        .setImageData(qupath.getImageData())
                        .setDefaultImports(QPEx.getCoreClasses())
                        .setDefaultStaticImports(Collections.singletonList(QPEx.class))
                        .setWriter(new FeedbackWriter(feedback, false))
                        .setErrorWriter(new FeedbackWriter(feedback, true))
                        .build();
                Object result = GroovyLanguage.getInstance().execute(params);
                if (result != null) {
                    logger.info("ASTRA {} result: {}", scriptName, result);
                    feedback.info("Result: " + result);
                }
                if (feedback.isCancellationRequested()) {
                    feedback.cancelled("Run cancellation was requested.");
                } else {
                    feedback.success("Run completed.");
                    Platform.runLater(() -> Dialogs.showInfoNotification("ASTRA " + scriptName, "Run completed."));
                }
            } catch (CancellationException e) {
                logger.warn("ASTRA {} cancelled.", scriptName);
                feedback.cancelled("Run cancelled.");
            } catch (ScriptException e) {
                logger.error("ASTRA {} failed.", scriptName, e);
                if (feedback.isCancellationRequested()) {
                    feedback.cancelled("Run cancellation was requested before the script stopped.");
                } else {
                    feedback.error(String.valueOf(e.getMessage()));
                    Platform.runLater(() -> Dialogs.showErrorMessage("ASTRA " + scriptName, String.valueOf(e.getMessage())));
                }
            } catch (Throwable t) {
                logger.error("ASTRA {} failed.", scriptName, t);
                if (feedback.isCancellationRequested()) {
                    feedback.cancelled("Run cancellation was requested before the script stopped.");
                } else {
                    feedback.error(t.getClass().getSimpleName() + ": " + t.getMessage());
                    Platform.runLater(() -> Dialogs.showErrorMessage("ASTRA " + scriptName, t.getClass().getSimpleName() + ": " + t.getMessage()));
                }
            } finally {
                Platform.runLater(() -> runButton.setDisable(false));
            }
        });
        feedback.attachFuture(future);
    }

    private static int bracketBalance(String line) {
        int balance = 0;
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == quote && (i == 0 || line.charAt(i - 1) != '\\')) {
                    quoted = false;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quoted = true;
                quote = c;
            } else if (c == '[') {
                balance++;
            } else if (c == ']') {
                balance--;
            }
        }
        return balance;
    }

    private static int firstTopLevelComment(String value) {
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < value.length() - 1; i++) {
            char c = value.charAt(i);
            if (quoted) {
                if (c == quote && value.charAt(i - 1) != '\\') {
                    quoted = false;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quoted = true;
                quote = c;
            } else if (c == '/' && value.charAt(i + 1) == '/') {
                return i;
            }
        }
        return -1;
    }

    private static String prettyName(String name) {
        return name.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static List<String> orderedGroups(List<EditableConstant> constants, boolean advanced) {
        List<String> groups = new ArrayList<>();
        for (EditableConstant constant : constants) {
            if (constant.advanced != advanced) {
                continue;
            }
            String group = groupFor(constant.name);
            if (!groups.contains(group)) {
                groups.add(group);
            }
        }
        return groups;
    }

    private static String descriptionFor(String scriptName) {
        return switch (pipelineStage(scriptName)) {
            case "Training" -> "Build Cellpose-SAM models from curated QuPath training annotations.";
            case "Tuning" -> "Search parameter sets against validation annotations and save auditable best parameters.";
            case "Validation" -> "Measure trained model performance on validation regions before analysis use.";
            case "Analysis" -> scriptName.toLowerCase(Locale.ROOT).contains("vascular")
                    ? "Run vascular region generation, detection, and quantification workflows."
                    : scriptName.toLowerCase(Locale.ROOT).contains("colocalization")
                    ? "Detect cells, measure channels, and call marker colocalization with explicit thresholds."
                    : "Run downstream ASTRA analysis utilities.";
            default -> "Configure and run an ASTRA pipeline.";
        };
    }

    private static String pipelineStage(String scriptName) {
        String lower = scriptName.toLowerCase(Locale.ROOT);
        if (lower.contains("training")) return "Training";
        if (lower.contains("tuning")) return "Tuning";
        if (lower.contains("validation")) return "Validation";
        return "Analysis";
    }

    private static boolean isAdvanced(String name) {
        Set<String> basic = Set.of(
                "RESET_BASELINE",
                "MODES_TO_RUN",
                "TRAIN_TARGET",
                "TRAINING_MODE",
                "TUNE_TARGET",
                "SEARCH_MODE",
                "SEARCH_STYLE",
                "TIERS_TO_RUN",
                "VALIDATE_TARGET",
                "VALIDATION_MODE",
                "SCORE_METRIC",
                "CLASS_ANALYSIS_REGION",
                "CLASSIFIER_LUMEN",
                "CLASSIFIER_SMOOTH_MUSCLE",
                "CLASSIFIER_ENDOTHELIUM",
                "CLASS_ROI",
                "CLASS_TRACE",
                "MODEL_SOURCE",
                "MODEL_NAME",
                "MODEL_FILE",
                "PARAM_SOURCE",
                "BEST_PARAMS_FILE",
                "IMAGE_SCOPE",
                "SELECTED_IMAGE_NAMES",
                "DETECTION_TARGET",
                "NUC_MODEL_SOURCE",
                "NUC_MODEL_NAME",
                "NUC_MODEL_FILE",
                "CELL_MODEL_SOURCE",
                "CELL_MODEL_NAME",
                "CELL_MODEL_FILE",
                "CHANNEL_DAPI",
                "CHANNEL_WGA",
                "CHANNEL_ASMA",
                "CHANNEL_CD31",
                "CHANNELS_FOR_NUCLEUS",
                "CHANNELS_FOR_CELL",
                "NUCLEUS_SEGMENTATION_CHANNELS",
                "CELL_SEGMENTATION_CHANNELS",
                "CELLPOSE_CELL_CHANNELS",
                "COLOCALIZATION_CHECKS",
                "THRESHOLD_EXCLUDE_MARKERS",
                "POSITIVITY_METHOD",
                "THRESHOLD_MODE",
                "BACKGROUND_MODE",
                "USE_BATCH_MODE",
                "USE_PIXEL_SCALING",
                "SHOW_GUI_NOTIFICATIONS",
                "EXPORT_RESULTS",
                "EXPORT_QC_FIGURES",
                "RESULTS_FOLDER",
                "RESULTS_BASENAME",
                "OVERWRITE_RESULTS"
        );
        return !basic.contains(name);
    }

    private static String groupFor(String name) {
        if (name.contains("CHANNEL")) return "Channels";
        if (name.contains("MODEL") || name.contains("PARAM")) return "Model and Parameters";
        if (name.contains("IMAGE_SCOPE") || name.contains("SELECTED_IMAGE")) return "Image Scope";
        if (name.contains("THRESHOLD") || name.contains("BACKGROUND") || name.contains("COLOCALIZATION")) return "Colocalization";
        if (name.contains("EXPORT") || name.contains("RESULT")) return "Results";
        if (name.contains("VIEWER") || name.contains("GUI")) return "Interface";
        if (name.contains("GPU") || name.contains("BATCH") || name.contains("PIXEL") || name.contains("SAM") || name.contains("CELLPOSE")) return "Cellpose-SAM Runtime";
        return "General";
    }

    private static String channelColor(ImageChannel channel) {
        Integer color = channel.getColor();
        if (color == null || channel.isTransparent()) {
            return "#b7c0c7";
        }
        int[] rgb = ColorTools.unpackRGB(color);
        return String.format("#%02x%02x%02x", rgb[0], rgb[1], rgb[2]);
    }

    private static final class RunFeedback {

        private final VBox box;
        private final Label status;
        private final ProgressIndicator progress;
        private final TextArea output;
        private final Button killButton;
        private final AtomicReference<Future<?>> currentRun = new AtomicReference<>();
        private final AtomicBoolean cancellationRequested = new AtomicBoolean(false);

        private RunFeedback(String scriptName) {
            box = new VBox(8.0);
            box.setPadding(new Insets(14.0));
            box.setStyle("-fx-background-color: #102a3a; -fx-border-color: #284f60; -fx-border-radius: 7; -fx-background-radius: 7;");
            box.setPrefWidth(430.0);
            box.setMinWidth(360.0);
            box.setMaxWidth(520.0);
            HBox.setHgrow(box, Priority.NEVER);

            HBox header = new HBox(10.0);
            header.setAlignment(Pos.CENTER_LEFT);
            progress = new ProgressIndicator();
            progress.setPrefSize(18.0, 18.0);
            progress.setVisible(false);
            progress.setManaged(false);

            status = new Label("Ready to run " + scriptName + ".");
            status.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 13px; -fx-font-weight: 900; -fx-text-fill: white;");
            killButton = new Button("Kill Run");
            killButton.setDisable(true);
            killButton.setFocusTraversable(false);
            killButton.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 11px; -fx-font-weight: 900; -fx-background-color: #ffddd5; -fx-text-fill: #7c2417; -fx-border-color: #f0a090; -fx-border-radius: 4; -fx-background-radius: 4;");
            killButton.setOnAction(event -> requestCancellation());
            HBox.setHgrow(status, Priority.ALWAYS);
            header.getChildren().addAll(progress, status, killButton);

            output = new TextArea();
            output.setEditable(false);
            output.setWrapText(true);
            output.setPrefRowCount(32);
            output.setStyle("-fx-font-family: " + MONO_FONT_STACK + "; -fx-font-size: 11.5px; -fx-control-inner-background: #071923; -fx-text-fill: #eaf7f4; -fx-highlight-fill: #1f7a7a; -fx-border-color: #4d7583; -fx-border-radius: 4; -fx-background-radius: 4;");
            VBox.setVgrow(output, Priority.ALWAYS);

            box.getChildren().addAll(header, output);
            info("Script output and run-scoped QuPath/Cellpose logs appear here. Cellpose subprocess stdout/stderr is captured when it is emitted through QuPath logging.");
        }

        private Node node() {
            return box;
        }

        private void start() {
            Platform.runLater(() -> {
                output.clear();
                status.setText("Running...");
                status.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 13px; -fx-font-weight: 900; -fx-text-fill: white;");
                progress.setVisible(true);
                progress.setManaged(true);
                killButton.setDisable(false);
                cancellationRequested.set(false);
                appendLine("ASTRA run started.");
            });
        }

        private void attachFuture(Future<?> future) {
            currentRun.set(future);
        }

        private boolean isCancellationRequested() {
            return cancellationRequested.get();
        }

        private void requestCancellation() {
            cancellationRequested.set(true);
            Future<?> future = currentRun.get();
            boolean requested = future != null && future.cancel(true);
            // The launcher owns the Java/Groovy Future, not the active VirtualEnvironmentRunner
            // Process instance inside Cellpose. Keep the status honest unless a future
            // runtime API exposes direct process termination.
            appendLine(requested
                    ? "[CANCELLED] Cancellation requested. Java/Groovy task interruption was requested. Native Cellpose process may continue until the current operation exits."
                    : "[CANCELLED] Cancellation marked. The current Java/Groovy task could not be interrupted directly. Native Cellpose process may continue until the current operation exits.");
            Platform.runLater(() -> {
                status.setText("Cancellation requested.");
                status.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 13px; -fx-font-weight: 900; -fx-text-fill: #ffe0a3;");
                killButton.setDisable(true);
            });
        }

        private void info(String message) {
            append("[INFO] " + message + "\n");
        }

        private void warn(String message) {
            append("[WARN] " + message + "\n");
        }

        private void success(String message) {
            Platform.runLater(() -> {
                status.setText(message);
                status.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 13px; -fx-font-weight: 900; -fx-text-fill: #bdf2d0;");
                progress.setVisible(false);
                progress.setManaged(false);
                killButton.setDisable(true);
                appendLine("[DONE] " + message);
            });
        }

        private void error(String message) {
            Platform.runLater(() -> {
                status.setText("Run failed.");
                status.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 13px; -fx-font-weight: 900; -fx-text-fill: #ffb8aa;");
                progress.setVisible(false);
                progress.setManaged(false);
                killButton.setDisable(true);
                appendLine("[ERROR] " + message);
            });
        }

        private void cancelled(String message) {
            Platform.runLater(() -> {
                status.setText("Run cancelled.");
                status.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 13px; -fx-font-weight: 900; -fx-text-fill: #ffe0a3;");
                progress.setVisible(false);
                progress.setManaged(false);
                killButton.setDisable(true);
                appendLine("[CANCELLED] " + message);
            });
        }

        private void append(String text) {
            Platform.runLater(() -> {
                output.appendText(text);
                output.positionCaret(output.getLength());
            });
        }

        private void appendLogText(String text) {
            append("[LOG] " + text);
            if (!text.endsWith("\n")) {
                append("\n");
            }
        }

        private void appendLine(String text) {
            append(text + "\n");
        }
    }

    private static final class FeedbackWriter extends Writer {

        private final RunFeedback feedback;
        private final boolean error;
        private final StringBuilder buffer = new StringBuilder();

        private FeedbackWriter(RunFeedback feedback, boolean error) {
            this.feedback = feedback;
            this.error = error;
        }

        @Override
        public synchronized void write(char[] cbuf, int off, int len) {
            buffer.append(cbuf, off, len);
            flushCompleteLines();
        }

        @Override
        public synchronized void flush() throws IOException {
            if (!buffer.isEmpty()) {
                emit(buffer.toString());
                buffer.setLength(0);
            }
        }

        @Override
        public synchronized void close() throws IOException {
            flush();
        }

        private void flushCompleteLines() {
            int newline;
            while ((newline = buffer.indexOf("\n")) >= 0) {
                String line = buffer.substring(0, newline + 1);
                buffer.delete(0, newline + 1);
                emit(line);
            }
        }

        private void emit(String text) {
            if (error) {
                feedback.append("[ERR] " + text);
            } else {
                feedback.append(text);
            }
        }
    }

    private static final class CollapsibleSection extends VBox {

        private final Node content;
        private final Label arrow;

        private CollapsibleSection(String title, Node content, boolean expanded) {
            super(0.0);
            this.content = content;
            this.arrow = new Label(expanded ? "v" : ">");

            Button header = new Button();
            header.setMaxWidth(Double.MAX_VALUE);
            header.setFocusTraversable(false);
            header.setPadding(new Insets(0));
            header.setStyle("-fx-background-color: #173747; -fx-border-color: #173747; -fx-background-radius: 6 6 0 0; -fx-border-radius: 6 6 0 0;");

            Label titleLabel = new Label(title);
            titleLabel.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 13px; -fx-font-weight: 900; -fx-text-fill: white;");
            arrow.setMinWidth(18.0);
            arrow.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 13px; -fx-font-weight: 900; -fx-text-fill: #dcebed;");
            BorderPane headerContent = new BorderPane();
            headerContent.setPadding(new Insets(9.0, 12.0, 9.0, 12.0));
            headerContent.setLeft(titleLabel);
            headerContent.setRight(arrow);
            header.setGraphic(headerContent);
            headerContent.prefWidthProperty().bind(header.widthProperty().subtract(10.0));

            header.setOnAction(event -> setExpanded(!this.content.isVisible()));
            getChildren().addAll(header, content);
            setExpanded(expanded);
        }

        private void setExpanded(boolean expanded) {
            content.setVisible(expanded);
            content.setManaged(expanded);
            arrow.setText(expanded ? "v" : ">");
        }
    }

    private record RowNodes(Node label, Node editor) {
    }

    record PersistentApplyResult(boolean restored, boolean schemaReset) {
    }

    static final class RunLogCapture implements TextAppendable, AutoCloseable {

        private final Consumer<String> sink;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private boolean registered;

        private RunLogCapture(Consumer<String> sink) {
            this.sink = Objects.requireNonNull(sink, "sink");
        }

        static RunLogCapture attach(Consumer<String> sink) {
            RunLogCapture capture = new RunLogCapture(sink);
            LogManager.addTextAppendableFX(capture);
            capture.registered = true;
            return capture;
        }

        static RunLogCapture forTest(Consumer<String> sink) {
            return new RunLogCapture(sink);
        }

        @Override
        public void appendText(String text) {
            if (!closed.get() && text != null && !text.isBlank()) {
                sink.accept(text);
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                if (registered) {
                    LogManager.removeTextAppendableFX(this);
                }
            }
        }
    }

    record ColocalizationCheck(String label, String compartment, List<String> channels) {
    }

    private static final class ChannelCheckboxEditor extends VBox {

        private final Map<String, CheckBox> boxes = new LinkedHashMap<>();

        private ChannelCheckboxEditor(String titleText, List<String> channels, String rawValue) {
            super(7.0);
            Label title = new Label(titleText);
            title.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-font-weight: 900; -fx-text-fill: " + INK + ";");
            FlowPane options = new FlowPane(8.0, 8.0);
            List<String> selected = EditableConstant.csvValues(rawValue);
            for (String channel : channels) {
                CheckBox box = new CheckBox(channel);
                box.setSelected(selected.contains(channel));
                box.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-text-fill: " + INK + ";");
                boxes.put(channel, box);
                options.getChildren().add(box);
            }
            if (channels.isEmpty()) {
                Label empty = new Label("Open an image to choose channels without typing.");
                empty.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-text-fill: " + MUTED + ";");
                getChildren().addAll(title, empty);
            } else {
                getChildren().addAll(title, options);
            }
        }

        private List<String> selectedChannels() {
            return boxes.entrySet().stream()
                    .filter(e -> e.getValue().isSelected())
                    .map(Map.Entry::getKey)
                    .toList();
        }

        private void setSelected(List<String> values) {
            boxes.forEach((name, box) -> box.setSelected(values.contains(name)));
        }

        private void addChangeListener(Runnable listener) {
            boxes.values().forEach(box -> box.selectedProperty().addListener((obs, oldValue, newValue) -> listener.run()));
        }
    }

    private static final class ColocalizationChecksEditor extends VBox {

        private final List<String> imageChannels;
        private final VBox rows = new VBox(8.0);
        private final List<CheckRow> checkRows = new ArrayList<>();
        private final List<Runnable> listeners = new ArrayList<>();

        private ColocalizationChecksEditor(List<String> imageChannels, String rawValue) {
            super(8.0);
            this.imageChannels = List.copyOf(imageChannels);
            List<ColocalizationCheck> parsed = parseColocalizationChecks(rawValue);
            if (parsed.isEmpty()) {
                parsed = List.of(new ColocalizationCheck("DAPI_AND_AF488_nucleus", "Nucleus", imageChannels.stream().limit(2).toList()));
            }
            parsed.forEach(this::addRow);
            Button add = new Button("Add check");
            add.setFocusTraversable(false);
            add.setStyle(semanticButtonStyle());
            add.setOnAction(event -> {
                addRow(new ColocalizationCheck("", "Nucleus", List.of()));
                notifyListeners();
            });
            getChildren().addAll(rows, add);
        }

        private void addRow(ColocalizationCheck check) {
            CheckRow row = new CheckRow(check);
            checkRows.add(row);
            rows.getChildren().add(row.node);
        }

        private List<ColocalizationCheck> checks() {
            return checkRows.stream().map(CheckRow::check).toList();
        }

        private String render() {
            return renderColocalizationChecks(checks());
        }

        private void addChangeListener(Runnable listener) {
            listeners.add(listener);
        }

        private void notifyListeners() {
            listeners.forEach(Runnable::run);
        }

        private final class CheckRow {

            private final HBox node = new HBox(8.0);
            private final TextField label = new TextField();
            private final ComboBox<String> compartment = new ComboBox<>();
            private final Map<String, CheckBox> channelBoxes = new LinkedHashMap<>();

            private CheckRow(ColocalizationCheck check) {
                node.setAlignment(Pos.CENTER_LEFT);
                node.setStyle("-fx-background-color: white; -fx-border-color: #d7e2e6; -fx-border-radius: 5; -fx-background-radius: 5; -fx-padding: 8;");
                label.setPromptText("Label");
                label.setText(check.label());
                label.setPrefColumnCount(18);
                label.setStyle(EditableConstant.controlStyle());
                compartment.getItems().addAll("Nucleus", "Cell", "Cytoplasm");
                compartment.setValue(check.compartment().isBlank() ? "Nucleus" : check.compartment());
                compartment.setStyle(EditableConstant.controlStyle());
                FlowPane channels = new FlowPane(6.0, 6.0);
                for (String channel : imageChannels) {
                    CheckBox box = new CheckBox(channel);
                    box.setSelected(check.channels().contains(channel));
                    box.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 11px; -fx-text-fill: " + INK + ";");
                    channelBoxes.put(channel, box);
                    channels.getChildren().add(box);
                    box.selectedProperty().addListener((obs, oldValue, newValue) -> notifyListeners());
                }
                Button remove = new Button("Remove");
                remove.setFocusTraversable(false);
                remove.setStyle(semanticButtonStyle());
                remove.setOnAction(event -> {
                    checkRows.remove(this);
                    rows.getChildren().remove(node);
                    notifyListeners();
                });
                label.textProperty().addListener((obs, oldValue, newValue) -> notifyListeners());
                compartment.valueProperty().addListener((obs, oldValue, newValue) -> notifyListeners());
                HBox.setHgrow(channels, Priority.ALWAYS);
                node.getChildren().addAll(label, compartment, channels, remove);
            }

            private ColocalizationCheck check() {
                return new ColocalizationCheck(
                        label.getText().trim(),
                        String.valueOf(compartment.getValue()).trim(),
                        channelBoxes.entrySet().stream()
                                .filter(e -> e.getValue().isSelected())
                                .map(Map.Entry::getKey)
                                .toList()
                );
            }
        }
    }

    private static final class ThresholdExclusionEditor extends VBox {

        private final Set<String> selected;
        private final Map<String, CheckBox> boxes = new LinkedHashMap<>();
        private final List<Runnable> listeners = new ArrayList<>();

        private ThresholdExclusionEditor(String rawValue) {
            super(7.0);
            selected = new java.util.LinkedHashSet<>(EditableConstant.csvValues(rawValue));
        }

        private void refresh(List<String> markerKeys) {
            selected.addAll(boxes.entrySet().stream()
                    .filter(e -> e.getValue().isSelected())
                    .map(Map.Entry::getKey)
                    .toList());
            selected.retainAll(markerKeys);
            boxes.clear();
            getChildren().clear();
            if (markerKeys.isEmpty()) {
                Label empty = new Label("Add colocalization checks to choose threshold exclusions.");
                empty.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-text-fill: " + MUTED + ";");
                getChildren().add(empty);
                return;
            }
            for (String key : markerKeys) {
                CheckBox box = new CheckBox("Exclude " + key.replace("|", " | ") + " from thresholding/QC");
                box.setSelected(selected.contains(key));
                box.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-text-fill: " + INK + ";");
                box.selectedProperty().addListener((obs, oldValue, newValue) -> {
                    if (newValue) {
                        selected.add(key);
                    } else {
                        selected.remove(key);
                    }
                    notifyListeners();
                });
                boxes.put(key, box);
                getChildren().add(box);
            }
        }

        private List<String> selectedKeys() {
            return boxes.entrySet().stream()
                    .filter(e -> e.getValue().isSelected())
                    .map(Map.Entry::getKey)
                    .toList();
        }

        private void addChangeListener(Runnable listener) {
            listeners.add(listener);
        }

        private void notifyListeners() {
            listeners.forEach(Runnable::run);
        }
    }

    private static final class ListEditor extends VBox {

        private final TextField field;

        private ListEditor(String example) {
            super(5.0);
            field = new TextField(EditableConstant.simpleListToCsv(example));
            field.setPromptText("comma-separated values");
            field.setPrefColumnCount(48);
            field.setStyle(EditableConstant.controlStyle());
            Label hint = new Label("Enter plain values separated by commas. ASTRA will write the required Groovy list syntax.");
            hint.setWrapText(true);
            hint.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 10.5px; -fx-text-fill: " + MUTED + ";");
            Button restore = new Button("Restore example");
            restore.setFocusTraversable(false);
            restore.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 10.5px; -fx-font-weight: 800; -fx-background-color: #e6f0f2; -fx-text-fill: " + TEAL_DARK + "; -fx-border-color: #b5cbd2; -fx-border-radius: 4; -fx-background-radius: 4;");
            restore.setOnAction(event -> field.setText(EditableConstant.simpleListToCsv(example)));
            getChildren().addAll(field, hint, restore);
        }

        private String text() {
            return field.getText();
        }

        private void setText(String text) {
            field.setText(text);
        }

        private void addChangeListener(Runnable listener) {
            field.textProperty().addListener((obs, oldValue, newValue) -> listener.run());
        }
    }

    private static final class CodeEditor extends VBox {

        private final TextArea area;

        private CodeEditor(String example) {
            super(5.0);
            area = new TextArea(example);
            area.setPrefRowCount(Math.max(3, Math.min(12, example.split("\\R", -1).length + 1)));
            area.setWrapText(false);
            area.setStyle("-fx-font-family: " + MONO_FONT_STACK + "; -fx-font-size: 12px; -fx-control-inner-background: #fbfdff; -fx-text-fill: " + INK + "; -fx-border-color: " + CONTROL_BORDER + "; -fx-border-radius: 4; -fx-background-radius: 4;");
            Label hint = new Label("Structured advanced value. Keep keys, brackets, commas, and quotes intact. Use Restore example if the structure is damaged.");
            hint.setWrapText(true);
            hint.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 10.5px; -fx-text-fill: " + MUTED + ";");
            Button restore = new Button("Restore example");
            restore.setFocusTraversable(false);
            restore.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 10.5px; -fx-font-weight: 800; -fx-background-color: #e6f0f2; -fx-text-fill: " + TEAL_DARK + "; -fx-border-color: #b5cbd2; -fx-border-radius: 4; -fx-background-radius: 4;");
            restore.setOnAction(event -> area.setText(example));
            getChildren().addAll(area, hint, restore);
        }

        private String text() {
            return area.getText().trim();
        }

        private void setText(String text) {
            area.setText(text);
        }

        private void addChangeListener(Runnable listener) {
            area.textProperty().addListener((obs, oldValue, newValue) -> listener.run());
        }
    }

    /**
     * Editable top-level Groovy constant.
     */
    static final class EditableConstant {

        private final String type;
        private final String name;
        private final String value;
        private String displayValue;
        private String defaultDisplayValue;
        private final String suffix;
        private final int start;
        private final int end;
        private final boolean advanced;
        private final List<String> options;
        private final String help;
        private Node editor;

        private EditableConstant(String type, String name, String value, String suffix, int start, int end, boolean advanced, List<String> options, String help) {
            this.type = type;
            this.name = name;
            this.suffix = suffix == null ? "" : suffix;
            this.start = start;
            this.end = end;
            this.advanced = advanced;
            this.options = options == null ? List.of() : List.copyOf(options);
            this.help = help == null || help.isBlank()
                    ? "ASTRA did not provide help metadata for this script constant."
                    : help;
            this.editor = null;
            this.value = value;
            this.displayValue = value;
            this.defaultDisplayValue = value;
        }

        String name() {
            return name;
        }

        String helpText() {
            return help;
        }

        private Node createEditor() {
            if (editor == null) {
                editor = buildEditor();
            }
            return editor;
        }

        private Node buildEditor() {
            if (!options.isEmpty()) {
                ComboBox<String> comboBox = new ComboBox<>();
                comboBox.getItems().addAll(options);
                comboBox.setValue(stripStringQuotes(type, displayValue));
                comboBox.setMaxWidth(Double.MAX_VALUE);
                comboBox.setStyle(controlStyle());
                return comboBox;
            }
            if ("boolean".equals(type)) {
                CheckBox checkBox = new CheckBox();
                checkBox.setSelected(Boolean.parseBoolean(displayValue));
                checkBox.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-text-fill: " + INK + ";");
                return checkBox;
            }
            if ("List".equals(type) && isSimpleListField(name, displayValue)) {
                return new ListEditor(displayValue);
            }
            if ("List".equals(type) || "Map".equals(type)) {
                return new CodeEditor(displayValue);
            }
            TextField field = new TextField(stripStringQuotes(type, displayValue));
            field.setPrefColumnCount(48);
            field.setStyle(controlStyle());
            return field;
        }

        private String renderDeclaration() {
            String value;
            if (editor == null) {
                value = renderStoredValue();
                return "final " + type + " " + name + " = " + value + (suffix.isBlank() ? "" : " " + suffix) + "\n";
            }
            Node activeEditor = editor;
            value = null;
            if (activeEditor instanceof ComboBox<?> comboBox) {
                value = renderOptionValue(String.valueOf(comboBox.getValue()));
            } else if (activeEditor instanceof CheckBox checkBox) {
                value = Boolean.toString(checkBox.isSelected());
            } else if (activeEditor instanceof ListEditor listEditor) {
                value = renderSimpleListValue(listEditor.text());
            } else if (activeEditor instanceof ChannelCheckboxEditor channelEditor) {
                value = renderStringList(channelEditor.selectedChannels());
            } else if (activeEditor instanceof ColocalizationChecksEditor checksEditor) {
                value = checksEditor.render();
            } else if (activeEditor instanceof ThresholdExclusionEditor exclusionEditor) {
                value = renderStringList(exclusionEditor.selectedKeys());
            } else if (activeEditor instanceof CodeEditor codeEditor) {
                value = codeEditor.text();
            } else if (activeEditor instanceof TextArea area) {
                value = area.getText().trim();
            } else if (activeEditor instanceof TextField field) {
                value = "List".equals(type) && isSimpleListField(name, this.value)
                        ? renderSimpleListValue(field.getText())
                        : renderFieldValue(field.getText());
            } else {
                throw new IllegalStateException("Unsupported editor for " + name);
            }
            if (value.isBlank()) {
                value = "String".equals(type) ? "\"\"" : value;
            }
            return "final " + type + " " + name + " = " + value + (suffix.isBlank() ? "" : " " + suffix) + "\n";
        }

        private String renderStoredValue() {
            if ("String".equals(type)) {
                return renderFieldValue(stripStringQuotes(type, displayValue));
            }
            return displayValue.trim();
        }

        private void markDefault() {
            defaultDisplayValue = displayValue;
        }

        private void setDisplayValue(String displayValue) {
            this.displayValue = displayValue;
        }

        private String currentDisplayValue() {
            if (editor == null) {
                return displayValue;
            }
            Node activeEditor = editor;
            if (activeEditor instanceof ComboBox<?> comboBox) {
                return renderOptionValue(String.valueOf(comboBox.getValue()));
            } else if (activeEditor instanceof CheckBox checkBox) {
                return Boolean.toString(checkBox.isSelected());
            } else if (activeEditor instanceof ListEditor listEditor) {
                return renderSimpleListValue(listEditor.text());
            } else if (activeEditor instanceof ChannelCheckboxEditor channelEditor) {
                return renderStringList(channelEditor.selectedChannels());
            } else if (activeEditor instanceof ColocalizationChecksEditor checksEditor) {
                return checksEditor.render();
            } else if (activeEditor instanceof ThresholdExclusionEditor exclusionEditor) {
                return renderStringList(exclusionEditor.selectedKeys());
            } else if (activeEditor instanceof CodeEditor codeEditor) {
                return codeEditor.text();
            } else if (activeEditor instanceof TextArea area) {
                return area.getText().trim();
            } else if (activeEditor instanceof TextField field) {
                return "List".equals(type) && isSimpleListField(name, this.value)
                        ? renderSimpleListValue(field.getText())
                        : renderFieldValue(field.getText());
            }
            throw new IllegalStateException("Unsupported editor for " + name);
        }

        private void resetEditor() {
            displayValue = defaultDisplayValue;
            if (editor == null) {
                return;
            }
            if (editor instanceof ComboBox<?> comboBox) {
                @SuppressWarnings("unchecked")
                ComboBox<String> typed = (ComboBox<String>) comboBox;
                typed.setValue(stripStringQuotes(type, displayValue));
            } else if (editor instanceof CheckBox checkBox) {
                checkBox.setSelected(Boolean.parseBoolean(displayValue));
            } else if (editor instanceof ListEditor listEditor) {
                listEditor.setText(EditableConstant.simpleListToCsv(displayValue));
            } else if (editor instanceof ChannelCheckboxEditor channelEditor) {
                channelEditor.setSelected(EditableConstant.csvValues(displayValue));
            } else if (editor instanceof CodeEditor codeEditor) {
                codeEditor.setText(displayValue);
            } else if (editor instanceof TextArea area) {
                area.setText(displayValue);
            } else if (editor instanceof TextField field) {
                field.setText(stripStringQuotes(type, displayValue));
            }
        }

        private void setDisplayString(String channelName) {
            if (!"String".equals(type) || channelName == null || channelName.isBlank()) {
                return;
            }
            displayValue = "\"" + channelName.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }

        private void setDisplayList(List<String> channels) {
            if (!"List".equals(type) || channels == null || channels.isEmpty()) {
                return;
            }
            displayValue = renderStringList(channels);
        }

        private void setDisplayListAllowEmpty(List<String> channels) {
            if ("List".equals(type) && channels != null) {
                displayValue = renderStringList(channels);
            }
        }

        private void setCustomEditor(Node editor) {
            this.editor = editor;
        }

        private String optionValue() {
            Node activeEditor = editor;
            if (activeEditor instanceof ComboBox<?> comboBox) {
                return String.valueOf(comboBox.getValue());
            }
            if (activeEditor instanceof CheckBox checkBox) {
                return Boolean.toString(checkBox.isSelected());
            }
            return stripStringQuotes(type, displayValue);
        }

        private void addOptionListener(Runnable listener) {
            Node activeEditor = createEditor();
            if (activeEditor instanceof ComboBox<?> comboBox) {
                comboBox.valueProperty().addListener((obs, oldValue, newValue) -> listener.run());
            }
        }

        private void addChangeListener(Runnable listener) {
            Node activeEditor = createEditor();
            if (activeEditor instanceof ComboBox<?> comboBox) {
                comboBox.valueProperty().addListener((obs, oldValue, newValue) -> listener.run());
            } else if (activeEditor instanceof CheckBox checkBox) {
                checkBox.selectedProperty().addListener((obs, oldValue, newValue) -> listener.run());
            } else if (activeEditor instanceof ListEditor listEditor) {
                listEditor.addChangeListener(listener);
            } else if (activeEditor instanceof ChannelCheckboxEditor channelEditor) {
                channelEditor.addChangeListener(listener);
            } else if (activeEditor instanceof ColocalizationChecksEditor checksEditor) {
                checksEditor.addChangeListener(listener);
            } else if (activeEditor instanceof ThresholdExclusionEditor exclusionEditor) {
                exclusionEditor.addChangeListener(listener);
            } else if (activeEditor instanceof CodeEditor codeEditor) {
                codeEditor.addChangeListener(listener);
            } else if (activeEditor instanceof TextArea area) {
                area.textProperty().addListener((obs, oldValue, newValue) -> listener.run());
            } else if (activeEditor instanceof TextField field) {
                field.textProperty().addListener((obs, oldValue, newValue) -> listener.run());
            }
        }

        private static String controlStyle() {
            return "-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-background-color: #fbfdff; " +
                    "-fx-border-color: " + CONTROL_BORDER + "; -fx-border-radius: 4; -fx-background-radius: 4; " +
                    "-fx-control-inner-background: #fbfdff; -fx-text-fill: " + INK + ";";
        }

        private String renderFieldValue(String raw) {
            String value = raw == null ? "" : raw.trim();
            if ("String".equals(type)) {
                return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            }
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank.");
            }
            return value;
        }

        private String renderOptionValue(String raw) {
            String value = raw == null ? "" : raw.trim();
            if ("String".equals(type)) {
                return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            }
            return value;
        }

        private static boolean isSimpleListField(String name, String value) {
            String trimmed = value.trim();
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]") || trimmed.contains(":")) {
                return false;
            }
            return name.contains("CHANNELS")
                    || name.contains("IMAGE_NAMES")
                    || "MODES_TO_RUN".equals(name)
                    || "TIERS_TO_RUN".equals(name);
        }

        private static String simpleListToCsv(String value) {
            String inner = value.trim();
            if (inner.length() >= 2) {
                inner = inner.substring(1, inner.length() - 1).trim();
            }
            if (inner.isBlank()) {
                return "";
            }
            return Arrays.stream(inner.split(","))
                    .map(String::trim)
                    .map(EditableConstant::stripOuterQuotes)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
        }

        private static List<String> csvValues(String value) {
            String csv = simpleListToCsv(value);
            if (csv.isBlank()) {
                return List.of();
            }
            return Arrays.stream(csv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
        }

        private String renderSimpleListValue(String raw) {
            String value = raw == null ? "" : raw.trim();
            if (value.isBlank()) {
                return "[]";
            }
            boolean numeric = "TIERS_TO_RUN".equals(name);
            List<String> tokens = Arrays.stream(value.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
            if (numeric) {
                return "[" + String.join(", ", tokens) + "]";
            }
            return "[" + tokens.stream()
                    .map(s -> "\"" + stripOuterQuotes(s).replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("") + "]";
        }

        private static String stripOuterQuotes(String raw) {
            String value = raw.trim();
            if (value.length() >= 2 &&
                    ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
                return value.substring(1, value.length() - 1);
            }
            return value;
        }

        private static String stripStringQuotes(String type, String value) {
            if (!"String".equals(type)) {
                return value;
            }
            String trimmed = value.trim();
            if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                return trimmed.substring(1, trimmed.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
            }
            return trimmed;
        }
    }
}
