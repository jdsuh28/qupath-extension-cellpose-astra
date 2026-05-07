package qupath.ext.astra;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.scripting.languages.GroovyLanguage;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.scripting.ScriptParameters;

import javax.script.ScriptException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
        dialog.setHeaderText("Configure " + scriptName);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(createContent(constants));
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

            out.add(new EditableConstant(type, name, value, suffix, start, end));
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

    private static Node createContent(List<EditableConstant> constants) {
        VBox box = new VBox(10.0);
        box.setPadding(new Insets(12.0));
        box.getChildren().add(new Label("Review or adjust the values below, then press OK to run."));

        GridPane grid = new GridPane();
        grid.setHgap(10.0);
        grid.setVgap(8.0);
        int row = 0;
        String lastGroup = "";
        for (EditableConstant constant : constants) {
            String group = groupFor(constant.name);
            if (!group.equals(lastGroup)) {
                if (row > 0) {
                    grid.add(new Separator(), 0, row++, 2, 1);
                }
                Label title = new Label(group);
                title.setStyle("-fx-font-weight: bold;");
                grid.add(title, 0, row++, 2, 1);
                lastGroup = group;
            }

            Label label = new Label(prettyName(constant.name));
            label.setMinWidth(210.0);
            label.setWrapText(true);
            grid.add(label, 0, row);

            Node editor = constant.createEditor();
            GridPane.setHgrow(editor, Priority.ALWAYS);
            grid.add(editor, 1, row++);
        }

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(900.0);
        scroll.setPrefViewportHeight(650.0);
        box.getChildren().add(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return box;
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

    /**
     * Editable top-level Groovy constant.
     */
    static final class EditableConstant {

        private final String type;
        private final String name;
        private final String suffix;
        private final int start;
        private final int end;
        private final Node editor;

        private EditableConstant(String type, String name, String value, String suffix, int start, int end) {
            this.type = type;
            this.name = name;
            this.suffix = suffix == null ? "" : suffix;
            this.start = start;
            this.end = end;
            this.editor = createEditor(type, value);
        }

        private Node createEditor() {
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
                area.setStyle("-fx-font-family: monospace;");
                return area;
            }
            TextField field = new TextField(stripStringQuotes(type, value));
            field.setPrefColumnCount(48);
            return field;
        }

        private String renderDeclaration() {
            String value;
            if (editor instanceof CheckBox checkBox) {
                value = Boolean.toString(checkBox.isSelected());
            } else if (editor instanceof TextArea area) {
                value = area.getText().trim();
            } else if (editor instanceof TextField field) {
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
