package qupath.ext.astra;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
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
import qupath.lib.gui.scripting.languages.GroovyLanguage;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.scripting.ScriptParameters;

import javax.script.ScriptException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
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

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(qupath.getStage());
        dialog.setTitle("ASTRA " + scriptName);
        dialog.setHeaderText(null);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(createContent(qupath, scriptName, constants));
        dialog.getDialogPane().setStyle("-fx-background-color: #f6f8fb;");
        dialog.setResizable(true);

        ButtonType result = dialog.showAndWait().orElse(ButtonType.CANCEL);
        if (result != ButtonType.OK) {
            return;
        }

        String configuredScript;
        try {
            configuredScript = applyConstants(scriptText, constants);
        } catch (RuntimeException e) {
            Dialogs.showErrorMessage("ASTRA " + scriptName, e.getMessage());
            return;
        }

        executeAsync(qupath, scriptName, configuredScript);
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

            out.add(new EditableConstant(type, name, value, suffix, start, end, isAdvanced(name)));
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

    private static Node createContent(QuPathGUI qupath, String scriptName, List<EditableConstant> constants) {
        VBox root = new VBox(14.0);
        root.setPadding(new Insets(0));
        root.setStyle("-fx-background-color: #f6f8fb;");

        VBox header = new VBox(6.0);
        header.setPadding(new Insets(22.0, 24.0, 20.0, 24.0));
        header.setStyle("-fx-background-color: linear-gradient(to right, #123047, #1f6f78);");
        Label title = new Label("ASTRA " + scriptName);
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: 700; -fx-text-fill: white;");
        Label subtitle = new Label("Review the essentials, confirm channel names, and expand advanced settings only when needed.");
        subtitle.setWrapText(true);
        subtitle.setStyle("-fx-font-size: 12px; -fx-text-fill: #d9f2f2;");
        header.getChildren().addAll(title, subtitle);

        VBox body = new VBox(14.0);
        body.setPadding(new Insets(0, 18.0, 18.0, 18.0));
        body.getChildren().add(createChannelPanel(qupath));

        VBox basic = new VBox(10.0);
        basic.getChildren().add(sectionHeader("Basic setup", "Only the controls most users should touch for a normal run."));
        for (String group : orderedGroups(constants, false)) {
            List<EditableConstant> groupConstants = constants.stream()
                    .filter(c -> !c.advanced && group.equals(groupFor(c.name)))
                    .toList();
            basic.getChildren().add(createSection(group, groupConstants, true));
        }

        VBox advancedContent = new VBox(8.0);
        for (String group : orderedGroups(constants, true)) {
            List<EditableConstant> groupConstants = constants.stream()
                    .filter(c -> c.advanced && group.equals(groupFor(c.name)))
                    .toList();
            advancedContent.getChildren().add(createSection(group, groupConstants, false));
        }
        TitledPane advancedPane = new TitledPane("Advanced settings", advancedContent);
        advancedPane.setExpanded(false);
        advancedPane.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: #153243;");

        body.getChildren().addAll(basic, advancedPane);

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(980.0);
        scroll.setPrefViewportHeight(700.0);
        scroll.setStyle("-fx-background: #f6f8fb; -fx-background-color: #f6f8fb;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        root.getChildren().addAll(header, scroll);
        return root;
    }

    private static Node sectionHeader(String titleText, String subtitleText) {
        VBox box = new VBox(2.0);
        Label title = new Label(titleText);
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: 700; -fx-text-fill: #153243;");
        Label subtitle = new Label(subtitleText);
        subtitle.setStyle("-fx-font-size: 11px; -fx-text-fill: #536a76;");
        subtitle.setWrapText(true);
        box.getChildren().addAll(title, subtitle);
        return box;
    }

    private static TitledPane createSection(String title, List<EditableConstant> constants, boolean expanded) {
        GridPane grid = new GridPane();
        grid.setPadding(new Insets(14.0));
        grid.setHgap(12.0);
        grid.setVgap(10.0);
        grid.setStyle("-fx-background-color: white; -fx-border-color: #d9e2e7; -fx-border-radius: 6; -fx-background-radius: 6;");

        int row = 0;
        for (EditableConstant constant : constants) {
            HBox labelBox = new HBox(6.0);
            Label label = new Label(prettyName(constant.name));
            label.setMinWidth(220.0);
            label.setWrapText(true);
            label.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: #233642;");
            Label info = new Label("i");
            info.setMinSize(18.0, 18.0);
            info.setMaxSize(18.0, 18.0);
            info.setStyle("-fx-alignment: center; -fx-font-size: 11px; -fx-font-weight: 700; -fx-text-fill: #1f6f78; -fx-border-color: #78aeb4; -fx-border-radius: 9; -fx-background-radius: 9; -fx-background-color: #e9f5f5;");
            Tooltip.install(info, new Tooltip(helpFor(constant.name)));
            labelBox.getChildren().addAll(label, info);
            grid.add(labelBox, 0, row);

            Node editor = constant.createEditor();
            GridPane.setHgrow(editor, Priority.ALWAYS);
            grid.add(editor, 1, row++);
        }

        TitledPane pane = new TitledPane(title, grid);
        pane.setExpanded(expanded);
        pane.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: #153243;");
        return pane;
    }

    private static Node createChannelPanel(QuPathGUI qupath) {
        VBox panel = new VBox(8.0);
        panel.setPadding(new Insets(14.0));
        panel.setStyle("-fx-background-color: #fffaf0; -fx-border-color: #e4c66f; -fx-border-radius: 6; -fx-background-radius: 6;");

        Label title = new Label("Open image channels");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: 700; -fx-text-fill: #6b4e00;");

        FlowPane chips = new FlowPane(8.0, 8.0);
        chips.setStyle("-fx-padding: 2 0 0 0;");
        if (qupath.getImageData() == null || qupath.getImageData().getServer() == null) {
            Label empty = new Label("No image is open. Channel-dependent fields still use the script defaults.");
            empty.setStyle("-fx-font-size: 12px; -fx-text-fill: #725f2a;");
            panel.getChildren().addAll(title, empty);
            return panel;
        }

        List<ImageChannel> channels = qupath.getImageData().getServer().getMetadata().getChannels();
        if (channels.isEmpty()) {
            Label empty = new Label("The current image did not report channel metadata.");
            empty.setStyle("-fx-font-size: 12px; -fx-text-fill: #725f2a;");
            panel.getChildren().addAll(title, empty);
            return panel;
        }

        for (ImageChannel channel : channels) {
            HBox chip = new HBox(6.0);
            chip.setPadding(new Insets(5.0, 8.0, 5.0, 8.0));
            chip.setStyle("-fx-background-color: white; -fx-border-color: #e1d2a1; -fx-border-radius: 14; -fx-background-radius: 14;");
            Rectangle swatch = new Rectangle(12.0, 12.0);
            swatch.setArcHeight(4.0);
            swatch.setArcWidth(4.0);
            swatch.setStyle("-fx-fill: " + channelColor(channel) + "; -fx-stroke: #31404a; -fx-stroke-width: 0.5;");
            Label name = new Label(channel.getName());
            name.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: #24323a;");
            chip.getChildren().addAll(swatch, name);
            chips.getChildren().add(chip);
        }

        panel.getChildren().addAll(title, chips);
        return panel;
    }

    private static void executeAsync(QuPathGUI qupath, String scriptName, String configuredScript) {
        qupath.getThreadPoolManager().getSingleThreadExecutor(AstraPipelineLauncher.class).submit(() -> {
            try {
                logger.info("ASTRA {} started from configuration dialog.", scriptName);
                ScriptParameters params = ScriptParameters.builder()
                        .setScript(configuredScript)
                        .setProject(qupath.getProject())
                        .setImageData(qupath.getImageData())
                        .setDefaultImports(QPEx.getCoreClasses())
                        .setDefaultStaticImports(Collections.singletonList(QPEx.class))
                        .useLogWriters(logger)
                        .build();
                Object result = GroovyLanguage.getInstance().execute(params);
                if (result != null) {
                    logger.info("ASTRA {} result: {}", scriptName, result);
                }
                Platform.runLater(() -> Dialogs.showInfoNotification("ASTRA " + scriptName, "Run completed."));
            } catch (ScriptException e) {
                logger.error("ASTRA {} failed.", scriptName, e);
                Platform.runLater(() -> Dialogs.showErrorMessage("ASTRA " + scriptName, String.valueOf(e.getMessage())));
            } catch (Throwable t) {
                logger.error("ASTRA {} failed.", scriptName, t);
                Platform.runLater(() -> Dialogs.showErrorMessage("ASTRA " + scriptName, t.getClass().getSimpleName() + ": " + t.getMessage()));
            }
        });
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
                "USE_NUCLEI",
                "CHANNEL_DAPI",
                "CHANNEL_WGA",
                "CHANNEL_ASMA",
                "CHANNEL_CD31",
                "CHANNELS_FOR_NUCLEUS",
                "CHANNELS_FOR_CELL",
                "CELLPOSE_CELL_CHANNELS",
                "COLOCALIZATION_CHECKS",
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

    private static String helpFor(String name) {
        return switch (name) {
            case "CLASS_ANALYSIS_REGION", "CLASS_ROI" ->
                    "QuPath annotation class used as the analysis region. This must match the class name in the image exactly.";
            case "USE_WHOLE_IMAGE_IF_NO_REGION" ->
                    "When enabled, ASTRA analyzes the whole image if no matching parent region exists.";
            case "MODEL_SOURCE", "NUC_MODEL_SOURCE", "CELL_MODEL_SOURCE" ->
                    "Select whether Cellpose uses a built-in model name, a saved model record, or a model file path.";
            case "MODEL_NAME", "NUC_MODEL_NAME", "CELL_MODEL_NAME" ->
                    "Cellpose model name. Blank nucleus/cell-specific values inherit the shared model setting.";
            case "MODEL_FILE", "NUC_MODEL_FILE", "CELL_MODEL_FILE" ->
                    "Absolute model file path when the matching model source is FILE.";
            case "PARAM_SOURCE", "NUC_PARAM_SOURCE", "CELL_PARAM_SOURCE" ->
                    "Select manual parameters or a best-parameters file from tuning.";
            case "BEST_PARAMS_FILE", "NUC_BEST_PARAMS_FILE", "CELL_BEST_PARAMS_FILE" ->
                    "Absolute path to the best-parameters file when the matching parameter source uses a file.";
            case "IMAGE_SCOPE" ->
                    "Choose whether this run covers all project images, the current image, or selected image names.";
            case "SELECTED_IMAGE_NAMES" ->
                    "Image names used only when IMAGE_SCOPE is SELECTED_IMAGES_BY_NAME.";
            case "CHANNEL_DAPI", "CHANNEL_WGA", "CHANNEL_ASMA", "CHANNEL_CD31" ->
                    "Channel name expected in the current image. Compare this with the channel chips above.";
            case "CELLPOSE_CELL_CHANNELS" ->
                    "Channels passed to whole-cell detection. Names must match QuPath channel metadata exactly.";
            case "COLOCALIZATION_CHECKS" ->
                    "Defines each output label, measurement compartment, and the channels that must be positive together.";
            case "POSITIVITY_METHOD" ->
                    "Method used to convert channel measurements into positive/negative calls.";
            case "THRESHOLD_MODE" ->
                    "Controls whether positivity thresholds are manual, range-based, or computed from the analyzed population.";
            case "MANUAL_INTENSITY_THRESHOLDS" ->
                    "Absolute intensity thresholds used only when THRESHOLD_MODE is MANUAL.";
            case "RANGE_THRESHOLD_FRACTION_BY_MARKER" ->
                    "Fractions used only when THRESHOLD_MODE is RANGE_PERCENT.";
            case "THRESHOLD_PROVENANCE_BY_MARKER" ->
                    "Audit text describing the source of manual thresholds for publication-facing runs.";
            case "BACKGROUND_MODE" ->
                    "Optional background subtraction mode applied before thresholding.";
            case "BACKGROUND_SUBTRACTION_BY_CHANNEL" ->
                    "Per-channel offsets used when BACKGROUND_MODE is MANUAL_OFFSET.";
            case "EXPORT_RESULTS" ->
                    "Writes CSV outputs and run metadata under the project results folder.";
            case "EXPORT_QC_FIGURES" ->
                    "Writes threshold quality-control figures when supported by the pipeline.";
            case "RESULTS_FOLDER" ->
                    "Project-relative folder where ASTRA writes exported results.";
            case "RESULTS_BASENAME" ->
                    "Optional output filename stem. Leave blank to derive it from the image name.";
            case "OVERWRITE_RESULTS" ->
                    "Allow ASTRA to replace existing result files with the same name.";
            case "USE_GPU" ->
                    "Enable GPU execution for Cellpose when the local runtime supports it.";
            case "TILE_SIZE" ->
                    "Tile size in pixels for Cellpose processing.";
            case "USE_BATCH_MODE" ->
                    "Run Cellpose in batch mode where supported.";
            case "SHOW_GUI_NOTIFICATIONS", "SHOW_VIEWER_PROGRESS", "FOCUS_VIEWER_ON_IMAGE", "FOCUS_VIEWER_ON_ROI" ->
                    "QuPath interface behavior during and after the run.";
            default ->
                    "Advanced ASTRA script constant. Keep the default unless you know this run needs a different value.";
        };
    }

    private static String channelColor(ImageChannel channel) {
        Integer color = channel.getColor();
        if (color == null || channel.isTransparent()) {
            return "#b7c0c7";
        }
        int[] rgb = ColorTools.unpackRGB(color);
        return String.format("#%02x%02x%02x", rgb[0], rgb[1], rgb[2]);
    }

    /**
     * Editable top-level Groovy constant.
     */
    static final class EditableConstant {

        private final String type;
        private final String name;
        private final String value;
        private final String suffix;
        private final int start;
        private final int end;
        private final boolean advanced;
        private Node editor;

        private EditableConstant(String type, String name, String value, String suffix, int start, int end, boolean advanced) {
            this.type = type;
            this.name = name;
            this.suffix = suffix == null ? "" : suffix;
            this.start = start;
            this.end = end;
            this.advanced = advanced;
            this.editor = null;
            this.value = value;
        }

        private Node createEditor() {
            if (editor == null) {
                editor = createEditor(type, value);
            }
            return editor;
        }

        private static Node createEditor(String type, String value) {
            if ("boolean".equals(type)) {
                CheckBox checkBox = new CheckBox();
                checkBox.setSelected(Boolean.parseBoolean(value));
                return checkBox;
            }
            if ("List".equals(type) || "Map".equals(type)) {
                TextArea area = new TextArea(value);
                area.setPrefRowCount(Math.max(3, Math.min(12, value.split("\\R", -1).length + 1)));
                area.setWrapText(false);
                area.setStyle("-fx-font-family: monospace; -fx-font-size: 12px; -fx-control-inner-background: #fbfdff;");
                return area;
            }
            TextField field = new TextField(stripStringQuotes(type, value));
            field.setPrefColumnCount(48);
            field.setStyle("-fx-font-size: 12px; -fx-control-inner-background: #fbfdff;");
            return field;
        }

        private String renderDeclaration() {
            Node activeEditor = createEditor();
            String value;
            if (activeEditor instanceof CheckBox checkBox) {
                value = Boolean.toString(checkBox.isSelected());
            } else if (activeEditor instanceof TextArea area) {
                value = area.getText().trim();
            } else if (activeEditor instanceof TextField field) {
                value = renderFieldValue(field.getText());
            } else {
                throw new IllegalStateException("Unsupported editor for " + name);
            }
            if (value.isBlank()) {
                value = "String".equals(type) ? "\"\"" : value;
            }
            return "final " + type + " " + name + " = " + value + (suffix.isBlank() ? "" : " " + suffix);
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
