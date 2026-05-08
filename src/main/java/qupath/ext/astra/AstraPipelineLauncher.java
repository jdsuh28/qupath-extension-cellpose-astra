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
import qupath.lib.common.ColorTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageChannel;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Presents ASTRA script configuration in a QuPath dialog and returns an edited
 * script for execution by the standard QuPath script editor.
 *
 * <p>The base ASTRA Groovy script remains the source of truth.  This launcher
 * extracts editable top-level {@code final} declarations, renders them in a
 * small GUI, rewrites only those declarations, and gives the configured script
 * back to the wrapper running inside QuPath's normal script editor.  That keeps
 * output, errors, and cancellation behavior anchored to the familiar QuPath
 * script console while avoiding hand-editing long parameter blocks.</p>
 */
public final class AstraPipelineLauncher {

    private static final Logger logger = LoggerFactory.getLogger(AstraPipelineLauncher.class);
    private static final Pattern DECLARATION_PATTERN = Pattern.compile(
            "^final\\s+(String|boolean|int|double|List|Map)\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*(.*)$"
    );

    private AstraPipelineLauncher() {
        throw new AssertionError("No instances");
    }

    /**
     * Creates the small wrapper script opened by ASTRA menu actions.
     *
     * @param scriptName user-facing script name.
     * @param resourcePath bundled full pipeline script resource.
     * @return Groovy wrapper script.
     */
    public static String createWrapperScript(String scriptName, String resourcePath) {
        String safeName = scriptName.replace("'", "\\'");
        return "import qupath.ext.astra.AstraPipelineLauncher\n\n" +
                "def configuredScript = AstraPipelineLauncher.promptForConfiguredScript(getQuPath(), " +
                quote(scriptName) + ", " + quote(resourcePath) + ")\n" +
                "if (configuredScript == null) {\n" +
                "    println 'ASTRA " + safeName + " cancelled.'\n" +
                "    return\n" +
                "}\n\n" +
                "println 'ASTRA " + safeName + " started.'\n" +
                "evaluate(configuredScript)\n";
    }

    /**
     * Loads a bundled pipeline script, shows the configuration dialog, and
     * returns the edited script.
     *
     * @param qupath active QuPath GUI.
     * @param scriptName user-facing script name.
     * @param resourcePath bundled full pipeline script resource.
     * @return configured script text, or null when cancelled.
     */
    public static String promptForConfiguredScript(QuPathGUI qupath, String scriptName, String resourcePath) {
        Objects.requireNonNull(resourcePath, "resourcePath");
        try (InputStream stream = AstraPipelineLauncher.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalArgumentException("Missing ASTRA pipeline resource: " + resourcePath);
            }
            String script = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return promptForConfiguredScript(qupath, scriptName, script, resourcePath);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load ASTRA pipeline resource '" + resourcePath + "': " + e.getMessage(), e);
        }
    }

    /**
     * Shows the configuration dialog and returns the edited script.
     *
     * @param qupath active QuPath GUI.
     * @param scriptName user-facing script name.
     * @param scriptText full pipeline script text.
     * @param sourceLabel label used in diagnostics.
     * @return configured script text, or null when cancelled.
     */
    public static String promptForConfiguredScript(QuPathGUI qupath, String scriptName, String scriptText, String sourceLabel) {
        Objects.requireNonNull(qupath, "qupath");
        Objects.requireNonNull(scriptName, "scriptName");
        Objects.requireNonNull(scriptText, "scriptText");

        if (Platform.isFxApplicationThread()) {
            return promptOnApplicationThread(qupath, scriptName, scriptText, sourceLabel);
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                result.set(promptOnApplicationThread(qupath, scriptName, scriptText, sourceLabel));
            } catch (RuntimeException e) {
                failure.set(e);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for ASTRA configuration dialog.", e);
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
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

    private static String promptOnApplicationThread(QuPathGUI qupath, String scriptName, String scriptText, String sourceLabel) {
        List<EditableConstant> constants = extractEditableConstants(scriptText);
        if (constants.isEmpty()) {
            throw new IllegalStateException("No editable ASTRA configuration constants were found in " + sourceLabel + ".");
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(qupath.getStage());
        dialog.setTitle("ASTRA " + scriptName);
        dialog.setHeaderText("Configure " + scriptName);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(createContent(qupath, constants));
        dialog.setResizable(true);

        ButtonType result = dialog.showAndWait().orElse(ButtonType.CANCEL);
        if (result != ButtonType.OK) {
            return null;
        }

        return applyConstants(scriptText, constants);
    }

    private static Node createContent(QuPathGUI qupath, List<EditableConstant> constants) {
        VBox box = new VBox(10.0);
        box.setPadding(new Insets(12.0));
        box.getChildren().add(new Label("Review or adjust the values below, then press OK to run. Output will appear in this script editor tab."));
        box.getChildren().add(createChannelSummary(qupath));

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
        scroll.setPrefViewportWidth(940.0);
        scroll.setPrefViewportHeight(650.0);
        box.getChildren().add(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return box;
    }

    private static Node createChannelSummary(QuPathGUI qupath) {
        VBox box = new VBox(4.0);
        box.setPadding(new Insets(8.0));
        box.setStyle("-fx-border-color: #b8c0cc; -fx-border-radius: 4; -fx-background-color: #f7f9fc;");
        Label title = new Label("Current image channels");
        title.setStyle("-fx-font-weight: bold;");
        box.getChildren().add(title);

        ImageData<?> imageData = qupath.getImageData();
        if (imageData == null || imageData.getServer() == null || imageData.getServer().getMetadata() == null) {
            box.getChildren().add(new Label("No image is currently open. Channel names cannot be previewed."));
            return box;
        }

        List<ImageChannel> channels = imageData.getServer().getMetadata().getChannels();
        if (channels == null || channels.isEmpty()) {
            box.getChildren().add(new Label("No channel metadata found for the current image."));
            return box;
        }

        for (int i = 0; i < channels.size(); i++) {
            ImageChannel channel = channels.get(i);
            Integer color = channel.getColor();
            String colorText = color == null ? "none" : String.format(
                    "#%02X%02X%02X",
                    ColorTools.red(color),
                    ColorTools.green(color),
                    ColorTools.blue(color)
            );
            Label label = new Label((i + 1) + ". " + channel.getName() + "  " + colorText);
            if (color != null) {
                label.setStyle("-fx-text-fill: " + colorText + "; -fx-font-weight: bold;");
            }
            box.getChildren().add(label);
        }
        return box;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
                return quote(value);
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
