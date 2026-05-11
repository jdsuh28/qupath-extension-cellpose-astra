package qupath.ext.astra;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.event.ActionEvent;
import javafx.scene.Node;
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
import qupath.lib.gui.scripting.languages.GroovyLanguage;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.scripting.ScriptParameters;

import javax.script.ScriptException;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private static final Map<String, List<String>> OPTIONS = createOptions();
    private static final Map<String, String> LAST_CONFIGURED_SCRIPTS = new LinkedHashMap<>();
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
        String savedScript = LAST_CONFIGURED_SCRIPTS.get(scriptName);
        boolean restoredSettings = applyPersistentSettings(scriptName, constants);
        if (!restoredSettings && savedScript != null) {
            applySavedConstantValues(constants, extractEditableConstants(savedScript));
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(qupath.getStage());
        dialog.setTitle(scriptName);
        dialog.setHeaderText(null);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        RunFeedback feedback = new RunFeedback(scriptName);
        dialog.getDialogPane().setContent(createContent(qupath, scriptName, constants, !restoredSettings && savedScript == null, feedback));
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
            LAST_CONFIGURED_SCRIPTS.put(scriptName, configuredScript);
            savePersistentSettings(scriptName, constants);
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

    private static void applySavedConstantValues(List<EditableConstant> constants, List<EditableConstant> savedConstants) {
        Map<String, EditableConstant> savedByName = new LinkedHashMap<>();
        savedConstants.forEach(c -> savedByName.put(c.name, c));
        constants.forEach(c -> {
            EditableConstant saved = savedByName.get(c.name);
            if (saved != null) {
                c.setDisplayValue(saved.displayValue);
            }
        });
    }

    private static boolean applyPersistentSettings(String scriptName, List<EditableConstant> constants) {
        Preferences node = settingsNode(scriptName);
        boolean restored = false;
        for (EditableConstant constant : constants) {
            String value = node.get(constant.name, null);
            if (value != null) {
                constant.setDisplayValue(value);
                restored = true;
            }
        }
        return restored;
    }

    private static void savePersistentSettings(String scriptName, List<EditableConstant> constants) {
        Preferences node = settingsNode(scriptName);
        for (EditableConstant constant : constants) {
            try {
                node.put(constant.name, constant.currentDisplayValue());
            } catch (RuntimeException e) {
                logger.debug("Skipping invalid in-progress ASTRA setting {} for {}", constant.name, scriptName);
            }
        }
    }

    private static void clearPersistentSettings(String scriptName) {
        try {
            settingsNode(scriptName).clear();
        } catch (BackingStoreException e) {
            logger.warn("Unable to clear ASTRA settings for {}", scriptName, e);
        }
    }

    private static Preferences settingsNode(String scriptName) {
        return SETTINGS.node(scriptName.replaceAll("[^A-Za-z0-9_.-]", "_"));
    }

    private static Node createContent(QuPathGUI qupath, String scriptName, List<EditableConstant> constants, boolean applyChannelDefaults, RunFeedback feedback) {
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

        VBox basic = sectionShell("Basic", "Start here. These are the normal run controls: target, scope, channels, model source, thresholds, and outputs.");
        for (String group : orderedGroups(constants, false)) {
            List<EditableConstant> groupConstants = constants.stream()
                    .filter(c -> !c.advanced && group.equals(groupFor(c.name)))
                    .toList();
            basic.getChildren().add(createSection(scriptName, group, groupConstants, true, constants));
        }

        VBox advanced = sectionShell("Advanced", "Defaults are intentionally conservative. Open these only for deliberate tuning, diagnostics, or publication-specific overrides.");
        for (String group : orderedGroups(constants, true)) {
            List<EditableConstant> groupConstants = constants.stream()
                    .filter(c -> c.advanced && group.equals(groupFor(c.name)))
                    .toList();
            advanced.getChildren().add(createSection(scriptName, group, groupConstants, false, constants));
        }

        body.getChildren().addAll(basic, advanced, feedback.node());

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(980.0);
        scroll.setPrefViewportHeight(700.0);
        scroll.setStyle("-fx-background: " + PAPER + "; -fx-background-color: " + PAPER + ";");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        root.getChildren().addAll(header, scroll);
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

    private static CollapsibleSection createSection(String scriptName, String title, List<EditableConstant> constants, boolean expanded, List<EditableConstant> allConstants) {
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
            Tooltip tooltip = new Tooltip(helpFor(constant.name));
            tooltip.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px;");
            info.setTooltip(tooltip);
            installReliableTooltip(info, tooltip);
            labelBox.getChildren().addAll(label, info);
            grid.add(labelBox, 0, row);

            Node editor = constant.createEditor();
            constant.addChangeListener(() -> savePersistentSettings(scriptName, allConstants));
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
        if (names.isEmpty()) {
            return;
        }
        for (EditableConstant constant : constants) {
            switch (constant.name) {
                case "CHANNEL_DAPI" -> constant.setDisplayString(preferredChannel(names, "DAPI", "Hoechst", "DAPI"));
                case "CHANNEL_WGA" -> constant.setDisplayString(preferredChannel(names, "AF488", "FITC", "WGA"));
                case "CHANNEL_ASMA" -> constant.setDisplayString(preferredChannel(names, "AF555", "Cy3", "aSMA", "ASMA"));
                case "CHANNEL_CD31" -> constant.setDisplayString(preferredChannel(names, "AF647", "Cy5", "CD31"));
                case "CHANNELS_FOR_NUCLEUS" -> constant.setDisplayList(List.of(preferredChannel(names, "DAPI", "Hoechst", "DAPI")));
                case "CHANNELS_FOR_CELL", "CELLPOSE_CELL_CHANNELS" -> constant.setDisplayList(names);
                default -> {
                }
            }
        }
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
        };
        byName.values().forEach(c -> c.addOptionListener(update));
        update.run();
    }

    private static boolean isSelected(Map<String, EditableConstant> constants, String name, String expected) {
        EditableConstant constant = constants.get(name);
        return constant == null || expected.equals(constant.optionValue());
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
        qupath.getThreadPoolManager().getSingleThreadExecutor(AstraPipelineLauncher.class).submit(() -> {
            try {
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
                feedback.success("Run completed.");
                Platform.runLater(() -> Dialogs.showInfoNotification("ASTRA " + scriptName, "Run completed."));
            } catch (ScriptException e) {
                logger.error("ASTRA {} failed.", scriptName, e);
                feedback.error(String.valueOf(e.getMessage()));
                Platform.runLater(() -> Dialogs.showErrorMessage("ASTRA " + scriptName, String.valueOf(e.getMessage())));
            } catch (Throwable t) {
                logger.error("ASTRA {} failed.", scriptName, t);
                feedback.error(t.getClass().getSimpleName() + ": " + t.getMessage());
                Platform.runLater(() -> Dialogs.showErrorMessage("ASTRA " + scriptName, t.getClass().getSimpleName() + ": " + t.getMessage()));
            } finally {
                Platform.runLater(() -> runButton.setDisable(false));
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

    private static Map<String, List<String>> createOptions() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        List<String> targets = List.of("NUCLEUS", "CELL", "BOTH");
        out.put("TRAIN_TARGET", targets);
        out.put("TUNE_TARGET", targets);
        out.put("VALIDATE_TARGET", targets);
        List<String> modes = List.of("TEST", "FAST", "BALANCED", "FULL", "ULTRA");
        out.put("TRAINING_MODE", modes);
        out.put("SEARCH_MODE", modes);
        out.put("VALIDATION_MODE", modes);
        out.put("SEARCH_STYLE", List.of("STAGED", "JOINT"));
        out.put("SCORE_METRIC", List.of("DICE_PQ", "DICE", "IOU_PQ"));
        out.put("MODEL_SOURCE", List.of("MODEL_NAME", "SAVED", "FILE"));
        out.put("NUC_MODEL_SOURCE", List.of("", "MODEL_NAME", "SAVED", "FILE"));
        out.put("CELL_MODEL_SOURCE", List.of("", "MODEL_NAME", "SAVED", "FILE"));
        out.put("PARAM_SOURCE", List.of("MANUAL", "BEST_PARAMS_FILE"));
        out.put("NUC_PARAM_SOURCE", List.of("", "MANUAL", "BEST_PARAMS_FILE"));
        out.put("CELL_PARAM_SOURCE", List.of("", "MANUAL", "BEST_PARAMS_FILE"));
        out.put("IMAGE_SCOPE", List.of("ALL_IMAGES", "CURRENT_IMAGE", "SELECTED_IMAGES_BY_NAME"));
        out.put("POSITIVITY_METHOD", List.of("MEAN_INTENSITY"));
        out.put("THRESHOLD_MODE", List.of("LOG_GAUSSIAN_MIXTURE", "GAUSSIAN_MIXTURE", "KDE_VALLEY", "AUTO_OTSU_PER_CHANNEL", "RANGE_PERCENT", "MANUAL"));
        out.put("BACKGROUND_MODE", List.of("MANUAL_OFFSET", "NONE"));
        out.put("WALL_MARKER_OVERLAP_COMPARTMENT", List.of("cytoplasm", "cell", "nucleus"));
        out.put("ENDOTHELIUM_MARKER_OVERLAP_COMPARTMENT", List.of("cell", "cytoplasm", "nucleus"));
        out.put("PRINT_PARAM_MODE", List.of("ALL_TAGGED", "BEST_ONLY", "NONE"));
        out.put("ASSIGN_METHOD_DEFAULT", List.of("AUTO", "GREEDY", "OPTIMAL"));
        return Collections.unmodifiableMap(out);
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

    private static final class RunFeedback {

        private final VBox box;
        private final Label status;
        private final ProgressIndicator progress;
        private final TextArea output;

        private RunFeedback(String scriptName) {
            box = new VBox(8.0);
            box.setPadding(new Insets(14.0));
            box.setStyle("-fx-background-color: #102a3a; -fx-border-color: #284f60; -fx-border-radius: 7; -fx-background-radius: 7;");

            HBox header = new HBox(10.0);
            header.setAlignment(Pos.CENTER_LEFT);
            progress = new ProgressIndicator();
            progress.setPrefSize(18.0, 18.0);
            progress.setVisible(false);
            progress.setManaged(false);

            status = new Label("Ready to run " + scriptName + ".");
            status.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 13px; -fx-font-weight: 900; -fx-text-fill: white;");
            header.getChildren().addAll(progress, status);

            output = new TextArea();
            output.setEditable(false);
            output.setWrapText(true);
            output.setPrefRowCount(7);
            output.setStyle("-fx-font-family: " + MONO_FONT_STACK + "; -fx-font-size: 11.5px; -fx-control-inner-background: #071923; -fx-text-fill: #eaf7f4; -fx-highlight-fill: #1f7a7a; -fx-border-color: #4d7583; -fx-border-radius: 4; -fx-background-radius: 4;");
            VBox.setVgrow(output, Priority.ALWAYS);

            box.getChildren().addAll(header, output);
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
                appendLine("ASTRA run started.");
            });
        }

        private void info(String message) {
            append("[INFO] " + message + "\n");
        }

        private void success(String message) {
            Platform.runLater(() -> {
                status.setText(message);
                status.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 13px; -fx-font-weight: 900; -fx-text-fill: #bdf2d0;");
                progress.setVisible(false);
                progress.setManaged(false);
                appendLine("[DONE] " + message);
            });
        }

        private void error(String message) {
            Platform.runLater(() -> {
                status.setText("Run failed.");
                status.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 13px; -fx-font-weight: 900; -fx-text-fill: #ffb8aa;");
                progress.setVisible(false);
                progress.setManaged(false);
                appendLine("[ERROR] " + message);
            });
        }

        private void append(String text) {
            Platform.runLater(() -> {
                output.appendText(text);
                output.positionCaret(output.getLength());
            });
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
            this.displayValue = value;
            this.defaultDisplayValue = value;
        }

        private Node createEditor() {
            if (editor == null) {
                editor = buildEditor();
            }
            return editor;
        }

        private Node buildEditor() {
            List<String> options = OPTIONS.get(name);
            if (options != null) {
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
            displayValue = "[" + channels.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(s -> "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("") + "]";
        }

        private String optionValue() {
            Node activeEditor = createEditor();
            if (activeEditor instanceof ComboBox<?> comboBox) {
                return String.valueOf(comboBox.getValue());
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
