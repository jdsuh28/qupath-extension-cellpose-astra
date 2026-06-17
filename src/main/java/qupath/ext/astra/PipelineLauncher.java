package qupath.ext.astra;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.StringProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.MenuButton;
import javafx.stage.FileChooser;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Clipboard;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.ColorTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.logging.LogManager;
import qupath.lib.gui.logging.TextAppendable;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.scripting.languages.GroovyLanguage;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.scripting.ScriptParameters;

import javax.script.ScriptException;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
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
final class PipelineLauncher {

    private static final Logger logger = LoggerFactory.getLogger(PipelineLauncher.class);
    private static final Pattern DECLARATION_PATTERN = Pattern.compile(
            "^final\\s+(String|boolean|int|double|List|Map)\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*(.*)$"
    );
    private static final String AUTOSAVE_PROFILE_FILE = "_autosave.json";
    private static final String LAUNCHER_VIEW_FILE = "_launcher_view.json";
    private static final String GUI_RUN_ACTIVE_PROPERTY = "ASTRA_GUI_RUN_ACTIVE";
    private static final String LAUNCHER_STYLESHEET_RESOURCE = "/qupath/ext/astra/astra-launcher.css";
    private static final String FONT_STACK = "\"Inter\", \"Aptos Display\", \"Segoe UI\", \"Helvetica Neue\", Arial, sans-serif";
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
    private static final int SETTINGS_PROFILE_SCHEMA_VERSION = 1;
    private static final double CONTENT_HORIZONTAL_MARGIN = 24.0;
    private static final double PARAMETER_ROW_HEIGHT = 34.0;
    private static final double PARAMETER_ROW_GAP = 8.0;
    private static final double SECTION_CONTENT_GAP = 9.0;
    private static final double CARD_CONTENT_GAP = 8.0;
    private static final String HEADER_MODE_PREFERENCE_KEY = "qupath.ext.astra.headerMode";
    private static final String HEADER_MOTION_PREFERENCE_KEY = "qupath.ext.astra.headerMotion";
    private static final StringProperty HEADER_MODE_PREFERENCE =
            PathPrefs.createPersistentPreference(HEADER_MODE_PREFERENCE_KEY, AnimatedGradientHeader.HeaderMode.DYNAMIC.name());
    private static final StringProperty HEADER_MOTION_PREFERENCE =
            PathPrefs.createPersistentPreference(HEADER_MOTION_PREFERENCE_KEY, AnimatedGradientHeader.MotionSpeed.SMOOTH.name());
    private static final Gson PROFILE_GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int LAUNCHER_VIEW_SCHEMA_VERSION = 1;
    private static final List<String> STANDARD_GROUP_ORDER = GuiPresentation.standardGroups()
            .stream()
            .map(GuiPresentation.StandardGroup::name)
            .toList();
    private static final Map<String, Integer> STANDARD_GROUP_RANK = standardGroupRank();

    private PipelineLauncher() {
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

        List<EditableConstant> constants = editableConstantsForScript(scriptName, scriptText);
        if (constants.isEmpty()) {
            Dialogs.showErrorMessage("ASTRA " + scriptName, "No editable ASTRA configuration constants were found.");
            return;
        }
        String schemaId = schemaIdentity(constants);
        String sourceScriptSha256 = sha256Hex(scriptText);
        SettingsProfileState profileState = SettingsProfileState.scriptDefaults();

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(qupath.getStage());
        dialog.setTitle(scriptName);
        dialog.setHeaderText(null);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        RunFeedback feedback = new RunFeedback(scriptName);
        AtomicReference<Button> runButtonRef = new AtomicReference<>();
        AtomicReference<Button> exportButtonRef = new AtomicReference<>();
        AtomicReference<Button> resetImageButtonRef = new AtomicReference<>();
        AtomicReference<Button> resetProjectButtonRef = new AtomicReference<>();
        Consumer<ActionEvent> exportAction = event -> {
            event.consume();
            String configuredScript;
            try {
                configuredScript = applyConstants(scriptText, constants, profileState, Map.of("SCRIPT_ACTION", "\"EXPORT\""));
            } catch (RuntimeException e) {
                Dialogs.showErrorMessage("ASTRA " + scriptName, e.getMessage());
                return;
            }
            feedback.info(finalConfigSummary(scriptName, schemaId, constants, profileState));
            feedback.info("Standalone export requested from the launcher header.");
            executeAsync(qupath, scriptName, configuredScript, feedback, runButtonRef.get(), exportButtonRef.get());
        };
        Consumer<ActionEvent> resetImageAction = event -> {
            event.consume();
            runHeaderReset(qupath, scriptName, scriptText, constants, profileState, schemaId, feedback, runButtonRef.get(), resetImageButtonRef.get(), "RESET_IMAGE", false);
        };
        Consumer<ActionEvent> resetProjectAction = event -> {
            event.consume();
            runHeaderReset(qupath, scriptName, scriptText, constants, profileState, schemaId, feedback, runButtonRef.get(), resetProjectButtonRef.get(), "RESET_PROJECT", true);
        };
        dialog.getDialogPane().setContent(createContent(qupath, scriptName, constants, true, feedback, schemaId, sourceScriptSha256, profileState, exportAction, exportButtonRef::set, resetImageAction, resetImageButtonRef::set, resetProjectAction, resetProjectButtonRef::set));
        dialog.getDialogPane().setStyle("-fx-background-color: " + PAPER + "; -fx-font-family: " + FONT_STACK + ";");
        installAstraStyles(dialog.getDialogPane());
        dialog.setResizable(true);

        Button runButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        runButtonRef.set(runButton);
        runButton.setText("Run");
        styleButton(runButton, ButtonRole.PRIMARY);
        Button cancelButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        styleButton(cancelButton, ButtonRole.SECONDARY);
        runButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            String configuredScript;
            try {
                configuredScript = applyConstants(scriptText, constants, profileState);
            } catch (RuntimeException e) {
                Dialogs.showErrorMessage("ASTRA " + scriptName, e.getMessage());
                return;
            }
            if (requiresProvisionalVascularConfirmation(constants) && !confirmProvisionalVascularAutomation(qupath, scriptName)) {
                feedback.warn("Provisional vascular automation cancelled before execution.");
                return;
            }
            feedback.info(finalConfigSummary(scriptName, schemaId, constants, profileState));
            executeAsync(qupath, scriptName, configuredScript, feedback, runButton, exportButtonRef.get());
        });

        dialog.showAndWait();
    }

    /**
     * Extracts editable top-level constants from the script's user-edit section.
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
        boolean hasUserEditSection = scriptText.contains("USER EDIT SECTION");
        boolean inUserEditSection = !hasUserEditSection;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (!inUserEditSection) {
                if (trimmed.contains("USER EDIT SECTION")) {
                    inUserEditSection = true;
                }
                offset += line.length() + 1;
                continue;
            }

            if (trimmed.contains("CONFIG BUILD") || trimmed.contains("DO NOT MODIFY BELOW") || trimmed.startsWith("final Map cfg")) {
                break;
            }

            Matcher matcher = DECLARATION_PATTERN.matcher(trimmed);
            if (!matcher.matches()) {
                offset += line.length() + 1;
                continue;
            }

            String type = matcher.group(1);
            String name = matcher.group(2);
            if (isInternalConstantName(name)) {
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
                out.add(new EditableConstant(
                        type,
                        name,
                        value,
                        suffix,
                        start,
                        end,
                        groupFor(name),
                        isAdvanced(name),
                        Integer.MAX_VALUE,
                        scriptOptions.get(name),
                        scriptHelp.get(name),
                        ""
                ));
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

    static List<EditableConstant> editableConstantsForScript(String scriptName, String scriptText) {
        ManifestSet manifests = ManifestSet.load();
        return manifests.runnable(pipelineIdFor(scriptName, scriptText))
                .map(pipeline -> editableConstantsFromContract(pipeline))
                .orElseGet(() -> extractEditableConstants(scriptText));
    }

    @SuppressWarnings("unchecked")
    private static List<EditableConstant> editableConstantsFromContract(Map<String, Object> pipeline) {
        Object raw = pipeline.get("parameters");
        if (!(raw instanceof List<?> params)) {
            return List.of();
        }
        List<EditableConstant> out = new ArrayList<>();
        for (Object item : params) {
            if (!(item instanceof Map<?, ?> rawParam)) {
                continue;
            }
            Map<String, Object> param = (Map<String, Object>) rawParam;
            String name = String.valueOf(param.get("name"));
            if (Boolean.TRUE.equals(param.get("internal")) || Boolean.FALSE.equals(param.get("editable")) || isInternalConstantName(name)) {
                continue;
            }
            String type = String.valueOf(param.getOrDefault("type", "String"));
            String value = resolveContractDefaultValueText(String.valueOf(param.getOrDefault("defaultGroovy", "\"\"")));
            List<String> options = parseStringOptions(String.valueOf(param.getOrDefault("optionsGroovy", "[]")));
            String help = stripGroovyString(String.valueOf(param.getOrDefault("helpGroovy", "")));
            String details = String.valueOf(param.getOrDefault("detailsMarkdown", ""));
            boolean advanced = Boolean.TRUE.equals(param.get("advanced"));
            String group = String.valueOf(param.getOrDefault("group", groupFor(name)));
            int uiOrder = numericInt(param.get("uiOrder"), Integer.MAX_VALUE);
            out.add(new EditableConstant(type, name, value, "", -1, -1, group, advanced, uiOrder, options, help, details));
        }
        return out;
    }

    private static int numericInt(Object raw, int fallback) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String resolveContractDefaultValueText(String expr) {
        String resolved = expr == null ? "" : expr;
        for (Map.Entry<String, String> entry : cellposeDefaultLiteralMap().entrySet()) {
            resolved = resolved.replace("CellposeInferenceDefaults." + entry.getKey(), entry.getValue());
        }
        if ("Math.max(1, Runtime.runtime.availableProcessors() - 1)".equals(resolved.trim())) {
            return String.valueOf(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        }
        return resolved;
    }

    private static Map<String, String> cellposeDefaultLiteralMap() {
        return Map.ofEntries(
                Map.entry("DIAMETER_UM_AUTO", "0.0d"),
                Map.entry("CELLPROB_THRESHOLD", "0.0d"),
                Map.entry("FLOW_THRESHOLD", "0.4d"),
                Map.entry("NITER_AUTO", "0"),
                Map.entry("SAM_SIMPLIFY_DISTANCE_PX", "0.0d"),
                Map.entry("SAM_NORM_PMIN", "1.0d"),
                Map.entry("SAM_NORM_PMAX", "99.0d"),
                Map.entry("MODEL_SOURCE_MODEL_NAME", "\"MODEL_NAME\""),
                Map.entry("MODEL_SOURCE_SAVED", "\"SAVED\""),
                Map.entry("MODEL_SOURCE_FILE", "\"FILE\""),
                Map.entry("MODEL_NAME_CPSAM", "\"cpsam\""),
                Map.entry("COLOCALIZATION_THRESHOLD_MODE", "\"LOG_KDE_VALLEY\""),
                Map.entry("BACKGROUND_MODE_NONE", "\"NONE\"")
        );
    }

    private static String pipelineIdFor(String scriptName, String scriptText) {
        Matcher matcher = Pattern.compile("(?m)^final\\s+String\\s+PIPELINE_ID\\s*=\\s*\"([^\"]+)\"").matcher(scriptText);
        if (matcher.find()) {
            return matcher.group(1);
        }
        String name = scriptName == null ? "" : scriptName.trim().toLowerCase(Locale.ROOT);
        if (name.contains("colocalization")) return "colocalization";
        if (name.contains("vascular")) return "vascular";
        if (name.contains("training")) return "training";
        if (name.contains("tuning")) return "tuning";
        if (name.contains("validation")) return "validation";
        if (name.contains("generate")) return "generate-regions";
        return name;
    }

    private static String stripGroovyString(String value) {
        String text = value == null ? "" : value.trim();
        return EditableConstant.stripStringQuotes("String", text);
    }

    private static boolean isInternalConstantName(String name) {
        return name == null
                || name.startsWith("__")
                || "SCRIPT_ACTION".equals(name)
                || "localRunnerFile".equals(name)
                || "USE_LOCAL_CLASSES".equals(name)
                || "loader".equals(name);
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
        return applyConstants(scriptText, constants, Map.of());
    }

    static String applyConstants(String scriptText, List<EditableConstant> constants, Map<String, String> overrides) {
        if (isCompactEntrypoint(scriptText)) {
            return applyUserOverrides(scriptText, constants, overrides == null ? Map.of() : overrides);
        }
        StringBuilder out = new StringBuilder(scriptText);
        List<EditableConstant> reversed = new ArrayList<>(constants);
        Collections.reverse(reversed);
        Map<String, String> safeOverrides = overrides == null ? Map.of() : overrides;
        Set<String> appliedOverrides = new LinkedHashSet<>();
        for (EditableConstant constant : reversed) {
            String override = safeOverrides.get(constant.name);
            if (override != null) {
                appliedOverrides.add(constant.name);
            }
            out.replace(constant.start, constant.end, constant.renderDeclaration(override));
        }
        for (Map.Entry<String, String> entry : safeOverrides.entrySet()) {
            if (!appliedOverrides.contains(entry.getKey())) {
                replaceHiddenConstant(out, entry.getKey(), entry.getValue());
            }
        }
        return out.toString();
    }

    private static void replaceHiddenConstant(StringBuilder out, String name, String renderedValue) {
        if (name == null || name.isBlank() || renderedValue == null) {
            return;
        }
        Pattern pattern = Pattern.compile("(?m)^(\\s*final\\s+(?:String|Object|List|Map|boolean|int|double)\\s+" + Pattern.quote(name) + "\\s*=\\s*)([^\\r\\n]*?)(\\s*(?://.*)?$)");
        Matcher matcher = pattern.matcher(out);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Could not apply override for hidden ASTRA constant: " + name);
        }
        out.replace(matcher.start(), matcher.end(), matcher.group(1) + renderedValue + matcher.group(3));
    }

    static String applyConstants(String scriptText, List<EditableConstant> constants, SettingsProfileState profileState) {
        return applyConstants(scriptText, constants, profileState, Map.of());
    }

    static String applyConstants(String scriptText, List<EditableConstant> constants, SettingsProfileState profileState, Map<String, String> overrides) {
        if (isCompactEntrypoint(scriptText)) {
            Map<String, String> merged = new LinkedHashMap<>(overrides == null ? Map.of() : overrides);
            merged.putAll(renderSettingsProvenance(constants, profileState, overrides));
            return applyUserOverrides(scriptText, constants, merged);
        }
        return applySettingsProvenanceConstants(applyConstants(scriptText, constants, overrides), constants, profileState, overrides);
    }

    static String applySettingsProvenanceConstants(String scriptText, List<EditableConstant> constants, SettingsProfileState profileState) {
        return applySettingsProvenanceConstants(scriptText, constants, profileState, Map.of());
    }

    static String applySettingsProvenanceConstants(String scriptText, List<EditableConstant> constants, SettingsProfileState profileState, Map<String, String> overrides) {
        Objects.requireNonNull(scriptText, "scriptText");
        SettingsProfileState state = profileState == null ? SettingsProfileState.scriptDefaults() : profileState;
        Map<String, String> provenance = renderSettingsProvenance(constants, state, overrides);

        List<String> missing = missingProvenanceConstants(scriptText, provenance.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("ASTRA settings provenance injection failed; script is missing required constant(s): " + String.join(", ", missing));
        }

        String configured = scriptText;
        for (Map.Entry<String, String> entry : provenance.entrySet()) {
            configured = replaceProvenanceConstant(configured, entry.getKey(), entry.getValue());
        }
        return configured;
    }

    private static Map<String, String> renderSettingsProvenance(List<EditableConstant> constants, SettingsProfileState profileState, Map<String, String> overrides) {
        SettingsProfileState state = profileState == null ? SettingsProfileState.scriptDefaults() : profileState;
        Map<String, String> provenance = new LinkedHashMap<>();
        provenance.put("SETTINGS_SOURCE", quoteGroovy(state.exportSource()));
        provenance.put("SETTINGS_PROFILE_NAME", quoteGroovy(state.profileName));
        provenance.put("SETTINGS_PROFILE_PATH", quoteGroovy(state.profilePath));
        provenance.put("SETTINGS_PROFILE_SHA256", quoteGroovy(state.profileSha256));
        provenance.put("CONFIGURED_CONSTANTS_SHA256", quoteGroovy(configuredConstantsSha256(constants, overrides)));
        provenance.put("MANUAL_EDIT_AFTER_PROFILE_LOAD", String.valueOf(state.manualEditAfterLoad()));
        return provenance;
    }

    private static boolean isCompactEntrypoint(String scriptText) {
        return scriptText != null && scriptText.contains("final Map USER_OVERRIDES");
    }

    private static String applyUserOverrides(String scriptText, List<EditableConstant> constants, Map<String, String> overrides) {
        Map<String, String> values = new LinkedHashMap<>();
        for (EditableConstant constant : constants) {
            String override = overrides == null ? null : overrides.get(constant.name);
            if (override != null) {
                values.put(constant.name, override);
            } else if (!constant.isAtDefaultValue()) {
                values.put(constant.name, constant.currentDisplayValue());
            }
        }
        for (Map.Entry<String, String> entry : (overrides == null ? Map.<String, String>of() : overrides).entrySet()) {
            values.putIfAbsent(entry.getKey(), entry.getValue());
        }

        StringBuilder rendered = new StringBuilder("final Map USER_OVERRIDES = [\n");
        values.forEach((name, value) -> rendered.append("        ").append(name).append(": ").append(value).append(",\n"));
        rendered.append("]\n");

        Pattern pattern = Pattern.compile("(?s)final\\s+Map\\s+USER_OVERRIDES\\s*=\\s*\\[.*?\\]\\s*(?=\\s*final\\s+ClassLoader)");
        Matcher matcher = pattern.matcher(scriptText);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Could not apply ASTRA overrides; compact script is missing USER_OVERRIDES before the class loader bootstrap.");
        }
        return matcher.replaceFirst(Matcher.quoteReplacement(rendered.toString()));
    }

    private static List<String> missingProvenanceConstants(String scriptText, Collection<String> requiredNames) {
        List<String> missing = new ArrayList<>();
        for (String name : requiredNames) {
            Pattern pattern = Pattern.compile("(?m)^final\\s+(String|boolean)\\s+" + Pattern.quote(name) + "\\s*=");
            if (!pattern.matcher(scriptText).find()) {
                missing.add(name);
            }
        }
        return missing;
    }

    private static String configuredConstantsSha256(List<EditableConstant> constants) {
        return configuredConstantsSha256(constants, Map.of());
    }

    private static String configuredConstantsSha256(List<EditableConstant> constants, Map<String, String> overrides) {
        Map<String, String> safeOverrides = overrides == null ? Map.of() : overrides;
        Map<String, String> values = new TreeMap<>();
        for (EditableConstant constant : constants) {
            values.put(constant.name, safeOverrides.getOrDefault(constant.name, constant.currentDisplayValue()));
        }
        return sha256Hex(PROFILE_GSON.toJson(values));
    }

    private static String replaceProvenanceConstant(String scriptText, String name, String renderedValue) {
        Pattern pattern = Pattern.compile("(?m)^final\\s+(String|boolean)\\s+" + Pattern.quote(name) + "\\s*=\\s*[^\\r\\n]*(\\R?)");
        Matcher matcher = pattern.matcher(scriptText);
        if (!matcher.find()) {
            throw new IllegalStateException("ASTRA settings provenance injection failed; script is missing required constant: " + name);
        }
        String type = matcher.group(1);
        String newline = matcher.group(2);
        return matcher.replaceFirst(Matcher.quoteReplacement("final " + type + " " + name + " = " + renderedValue + newline));
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

    static File settingsProfileDirectory(File projectBase, String scriptName) {
        Objects.requireNonNull(projectBase, "projectBase");
        return new File(new File(new File(projectBase, "astra"), "settings"), settingsPipelineName(scriptName));
    }

    static File autosaveSettingsFile(File projectBase, String scriptName) {
        return new File(settingsProfileDirectory(projectBase, scriptName), AUTOSAVE_PROFILE_FILE);
    }

    static File launcherViewFile(File projectBase, String scriptName) {
        return new File(settingsProfileDirectory(projectBase, scriptName), LAUNCHER_VIEW_FILE);
    }

    static String settingsPipelineName(String scriptName) {
        String normalized = scriptName == null ? "" : scriptName.toLowerCase(Locale.ROOT);
        if (normalized.contains("colocalization")) {
            return "colocalization";
        }
        if (normalized.contains("vascular")) {
            return "vascular";
        }
        if (normalized.contains("training")) {
            return "training";
        }
        if (normalized.contains("tuning")) {
            return "tuning";
        }
        if (normalized.contains("validation")) {
            return "validation";
        }
        return normalized.replaceAll("[^a-z0-9_.-]+", "_").replaceAll("^_+|_+$", "");
    }

    static SettingsProfile createSettingsProfile(String scriptName, String schemaId, String sourceScriptSha256, List<EditableConstant> constants) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> modelReferences = new LinkedHashMap<>();
        for (EditableConstant constant : constants) {
            String value = constant.currentDisplayValue();
            values.put(constant.name, value);
            if (constant.name.contains("MODEL")) {
                modelReferences.put(constant.name, value);
            }
        }
        return new SettingsProfile(
                SETTINGS_PROFILE_SCHEMA_VERSION,
                settingsPipelineName(scriptName),
                scriptName,
                schemaId,
                sourceScriptSha256,
                runtimeProperty("astra.base.version", "unknown"),
                runtimeProperty("Implementation-Version", "unknown"),
                Instant.now().toString(),
                values,
                modelReferences,
                ""
        );
    }

    static void writeSettingsProfile(File file, SettingsProfile profile) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
            PROFILE_GSON.toJson(profile, writer);
        }
    }

    static void writeAutosaveSettings(File file, String scriptName, String schemaId, String sourceScriptSha256,
                                      List<EditableConstant> constants) throws IOException {
        SettingsProfile profile = createSettingsProfile(scriptName, schemaId, sourceScriptSha256, constants);
        profile.notes = "ASTRA automatic last-working-settings autosave.";
        writeSettingsProfile(file, profile);
    }

    static boolean restoreAutosaveSettings(File file, String scriptName, String schemaId, String sourceScriptSha256,
                                           List<EditableConstant> constants, SettingsProfileState profileState,
                                           Consumer<String> info, Consumer<String> warn) {
        if (file == null || !file.isFile()) {
            return false;
        }
        try {
            SettingsProfile profile = readSettingsProfile(file);
            applySettingsProfile(profile, scriptName, schemaId, sourceScriptSha256, constants);
            profileState.loadedAutosave(file.getName(), file.getAbsolutePath(), sha256File(file));
            if (info != null) {
                info.accept("Restored ASTRA autosave: " + file.getAbsolutePath());
            }
            return true;
        } catch (IOException | RuntimeException e) {
            if (warn != null) {
                warn.accept("Ignoring ASTRA autosave at " + file.getAbsolutePath() + ": " + e.getMessage());
            }
            return false;
        }
    }

    static void clearAutosaveSettings(File file) throws IOException {
        if (file != null) {
            Files.deleteIfExists(file.toPath());
        }
    }

    static SettingsProfile readSettingsProfile(File file) throws IOException {
        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            SettingsProfile profile = PROFILE_GSON.fromJson(reader, SettingsProfile.class);
            if (profile == null) {
                throw new IOException("Settings profile is empty: " + file);
            }
            return profile;
        } catch (JsonSyntaxException e) {
            throw new IOException("Settings profile is not valid JSON: " + file, e);
        }
    }

    static void applySettingsProfile(SettingsProfile profile, String scriptName, String schemaId, String sourceScriptSha256, List<EditableConstant> constants) {
        Objects.requireNonNull(profile, "profile");
        if (profile.schema_version != SETTINGS_PROFILE_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported ASTRA settings profile schema version: " + profile.schema_version);
        }
        if (!Objects.equals(profile.pipeline_name, settingsPipelineName(scriptName))) {
            throw new IllegalArgumentException("Settings profile is for pipeline '" + profile.pipeline_name + "', not '" + settingsPipelineName(scriptName) + "'.");
        }
        if (!Objects.equals(profile.script_schema_id, schemaId)) {
            throw new IllegalArgumentException("Settings profile schema does not match the current script.");
        }
        if (!Objects.equals(profile.source_script_sha256, sourceScriptSha256)) {
            throw new IllegalArgumentException("Settings profile source script hash does not match the current bundled script.");
        }
        Map<String, EditableConstant> byName = new LinkedHashMap<>();
        constants.forEach(c -> byName.put(c.name, c));
        for (String key : profile.constants.keySet()) {
            if (!byName.containsKey(key)) {
                throw new IllegalArgumentException("Settings profile contains unknown ASTRA constant: " + key);
            }
        }
        for (Map.Entry<String, String> entry : profile.constants.entrySet()) {
            byName.get(entry.getKey()).setDisplayValue(entry.getValue());
        }
    }

    static List<String> missingProfileChannels(SettingsProfile profile, List<String> openedChannels) {
        if (profile == null || profile.constants == null || openedChannels == null || openedChannels.isEmpty()) {
            return List.of();
        }
        Set<String> opened = Set.copyOf(openedChannels);
        List<String> referenced = new ArrayList<>();
        for (Map.Entry<String, String> entry : profile.constants.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if ("COLOCALIZATION_CHECKS".equals(key)) {
                parseColocalizationChecks(value).forEach(check -> referenced.addAll(check.channels()));
            } else if (key.contains("CHANNEL")) {
                if (value != null && value.trim().startsWith("[")) {
                    referenced.addAll(EditableConstant.csvValues(value));
                } else if (value != null) {
                    String channel = EditableConstant.stripOuterQuotes(value.trim());
                    if (!channel.isBlank()) {
                        referenced.add(channel);
                    }
                }
            }
        }
        return referenced.stream()
                .filter(channel -> !opened.contains(channel))
                .distinct()
                .sorted()
                .toList();
    }

    private static String runtimeProperty(String name, String fallback) {
        Package pkg = PipelineLauncher.class.getPackage();
        if ("Implementation-Version".equals(name) && pkg != null && pkg.getImplementationVersion() != null) {
            return pkg.getImplementationVersion();
        }
        return fallback;
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    static String sha256File(File file) throws IOException {
        return sha256Hex(Files.readString(file.toPath()));
    }

    static String finalConfigSummary(String scriptName, String schemaId, List<EditableConstant> constants) {
        return finalConfigSummary(scriptName, schemaId, constants, SettingsProfileState.scriptDefaults());
    }

    static String finalConfigSummary(String scriptName, String schemaId, List<EditableConstant> constants, SettingsProfileState profileState) {
        Map<String, EditableConstant> byName = new LinkedHashMap<>();
        constants.forEach(c -> byName.put(c.name, c));
        List<String> lines = new ArrayList<>();
        lines.add("Resolved configuration:");
        lines.add("  pipeline: " + scriptName);
        lines.add("  schema: " + schemaId.substring(0, Math.min(12, schemaId.length())));
        lines.add("  settings source: " + profileState.summary());
        appendSummary(lines, byName, "IMAGE_SCOPE", "  image scope");
        appendSummary(lines, byName, "SELECTED_IMAGE_NAMES", "  selected images");
        appendSummary(lines, byName, "TRAIN_TARGET", "  training target");
        appendSummary(lines, byName, "TUNE_TARGET", "  tuning target");
        appendSummary(lines, byName, "VALIDATE_TARGET", "  validation target");
        appendSummary(lines, byName, "DETECTION_TARGET", "  detection target");
        appendEffectiveModelSummary(lines, byName, effectiveTargetKey(byName), "NUCLEUS", "nucleus");
        appendEffectiveModelSummary(lines, byName, effectiveTargetKey(byName), "CELL", "cell");
        appendSummary(lines, byName, "NUCLEUS_SEGMENTATION_CHANNELS", "  nucleus segmentation channels");
        appendSummary(lines, byName, "CELL_SEGMENTATION_CHANNELS", "  cell segmentation channels");
        appendSummary(lines, byName, "CHANNELS_FOR_NUCLEUS", "  nucleus channels");
        appendSummary(lines, byName, "CHANNELS_FOR_CELL", "  cell channels");
        appendSummary(lines, byName, "COLOCALIZATION_CHECKS", "  colocalization checks");
        appendSummary(lines, byName, "THRESHOLD_MODE", "  threshold mode");
        appendSummary(lines, byName, "THRESHOLD_SCOPE", "  threshold scope");
        appendSummary(lines, byName, "THRESHOLD_SELECTED_IMAGE_NAMES", "  threshold source images");
        appendSummary(lines, byName, "BACKGROUND_MODE", "  background mode");
        appendSummary(lines, byName, "RESULTS_FOLDER", "  results folder");
        return String.join("\n", lines);
    }

    private static String effectiveTargetKey(Map<String, EditableConstant> constants) {
        for (String key : List.of("TRAIN_TARGET", "TUNE_TARGET", "VALIDATE_TARGET", "DETECTION_TARGET")) {
            if (constants.containsKey(key)) {
                return key;
            }
        }
        return "";
    }

    private static void appendEffectiveModelSummary(List<String> lines, Map<String, EditableConstant> constants, String targetKey, String target, String label) {
        String selectedTarget = targetKey == null || targetKey.isBlank() ? "BOTH" : rawString(constants, targetKey, "BOTH");
        if ("NUCLEUS".equals(target) && "CELL".equals(selectedTarget)) {
            lines.add("  effective " + label + " model: not used for " + targetKey + "=CELL");
            return;
        }
        if ("CELL".equals(target) && "NUCLEUS".equals(selectedTarget)) {
            lines.add("  effective " + label + " model: not used for " + targetKey + "=NUCLEUS");
            return;
        }
        String prefix = "NUCLEUS".equals(target) ? "NUC_" : "CELL_";
        String source = rawString(constants, prefix + "MODEL_SOURCE", "");
        String valueName = rawString(constants, prefix + "MODEL_NAME", "");
        String valueFile = rawString(constants, prefix + "MODEL_FILE", "");
        String valueSavedId = rawString(constants, prefix + "SAVED_MODEL_ID", "");
        String value = "SAVED".equals(source)
                ? valueSavedId
                : ("FILE".equals(source)
                ? valueFile
                : valueName);
        lines.add("  effective " + label + " model source: " + source);
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

    static boolean requiresProvisionalVascularConfirmation(List<EditableConstant> constants) {
        for (EditableConstant constant : constants) {
            if ("MODES_TO_RUN".equals(constant.name)) {
                List<String> modes = EditableConstant.csvValues(constant.currentDisplayValue());
                return modes.contains("AUTO_BUILD_CLASSIFIERS") || modes.contains("AUTO_SELECT_ROIS");
            }
        }
        return false;
    }

    private static void runHeaderReset(QuPathGUI qupath, String scriptName, String scriptText, List<EditableConstant> constants,
                                       SettingsProfileState profileState, String schemaId, RunFeedback feedback,
                                       Button runButton, Button actionButton, String resetMode, boolean projectReset) {
        if (!confirmAnalysisReset(qupath, scriptName, projectReset)) {
            feedback.warn("Analysis reset cancelled before execution.");
            return;
        }
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("SCRIPT_ACTION", quoteGroovy(resetMode));
        if (projectReset) {
            List<String> names = projectImageNames(qupath);
            if (names.isEmpty()) {
                Dialogs.showErrorMessage("ASTRA " + scriptName, "Project reset requires an open project with image entries.");
                return;
            }
            overrides.put("IMAGE_SCOPE", quoteGroovy("PROJECT_IMAGE_SELECTION"));
            overrides.put("SELECTED_IMAGE_NAMES", renderStringList(names));
        } else {
            overrides.put("IMAGE_SCOPE", quoteGroovy("CURRENT_IMAGE"));
        }
        String configuredScript;
        try {
            configuredScript = applyConstants(scriptText, constants, profileState, overrides);
        } catch (RuntimeException e) {
            Dialogs.showErrorMessage("ASTRA " + scriptName, e.getMessage());
            return;
        }
        feedback.info(finalConfigSummary(scriptName, schemaId, constants, profileState));
        feedback.warn((projectReset ? "Project" : "Image") + " reset requested from the launcher header. ASTRA will remove only ledger-recorded object IDs and measurement keys; exported files are not deleted.");
        executeAsync(qupath, scriptName, configuredScript, feedback, runButton, actionButton);
    }

    private static boolean confirmAnalysisReset(QuPathGUI qupath, String scriptName, boolean projectReset) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "", ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(qupath.getStage());
        alert.setTitle("ASTRA " + scriptName);
        alert.setHeaderText(projectReset ? "Reset recorded ASTRA state for the project?" : "Reset recorded ASTRA state for the current image?");
        alert.setContentText("""
                This is destructive for ASTRA-generated hierarchy state.

                ASTRA will delete only objects recorded by QuPath object ID in the analysis ledger and remove only recorded ASTRA measurement keys.
                User ROI, Trace, analysis-region annotations, unledgered objects, and exported CSV/QC files are preserved.
                """);
        installAstraStyles(alert.getDialogPane());
        alert.getDialogPane().setStyle("-fx-background-color: " + PAPER + "; -fx-font-family: " + FONT_STACK + ";");
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    private static void installProjectImageNameSelector(QuPathGUI qupath, List<EditableConstant> constants) {
        List<String> imageNames = projectImageNames(qupath);
        constants.stream()
                .filter(c -> ("SELECTED_IMAGE_NAMES".equals(c.name) || "THRESHOLD_SELECTED_IMAGE_NAMES".equals(c.name)) && "List".equals(c.type))
                .forEach(c -> c.setCustomEditor(new ProjectImageSelectionEditor(imageNames, c.displayValue)));
    }

    private static void installProjectAssetSelectors(QuPathGUI qupath, List<EditableConstant> constants) {
        File projectBase = projectBaseDirectoryOrNull(qupath);
        Map<String, EditableConstant> byName = new LinkedHashMap<>();
        constants.forEach(c -> byName.put(c.name, c));
        installModelAssetSelectors(projectBase, byName, "NUC", "nucleus");
        installModelAssetSelectors(projectBase, byName, "CELL", "cell");
        AssetDiscovery classifiers = discoverPixelClassifiers(projectBase);
        for (String name : List.of("CLASSIFIER_LUMEN", "CLASSIFIER_SMOOTH_MUSCLE", "CLASSIFIER_ENDOTHELIUM")) {
            EditableConstant constant = byName.get(name);
            if (constant != null) {
                constant.setCustomEditor(assetBackedCombo(constant, classifiers));
            }
        }
    }

    private static void installModelAssetSelectors(File projectBase, Map<String, EditableConstant> byName,
                                                   String prefix, String targetFolder) {
        EditableConstant savedId = byName.get(prefix + "_SAVED_MODEL_ID");
        if (savedId != null) {
            savedId.setCustomEditor(assetBackedCombo(savedId,
                    AssetDiscovery.fromValues(discoverSavedModelIds(projectBase, targetFolder).validIds())));
        }
        EditableConstant modelFile = byName.get(prefix + "_MODEL_FILE");
        if (modelFile != null) {
            modelFile.setCustomEditor(assetBackedCombo(modelFile, discoverModelFiles(projectBase, targetFolder)));
        }
        EditableConstant bestParams = byName.get(prefix + "_BEST_PARAMS_FILE");
        if (bestParams != null) {
            bestParams.setCustomEditor(assetBackedCombo(bestParams, discoverBestParamsFiles(projectBase, targetFolder)));
        }
    }

    static AssetDiscovery discoverPixelClassifiers(File projectBase) {
        File root = projectBase == null ? null : new File(new File(projectBase, "classifiers"), "pixel_classifiers");
        return discoverNamedAssets(root, "", Set.of(".json", ".classifier"));
    }

    static AssetDiscovery discoverModelFiles(File projectBase, String targetFolder) {
        File root = modelRoot(projectBase, targetFolder);
        return discoverPathAssets(projectBase, root, Set.of(".cpm", ".pt", ".pth", ".model", ".torch"));
    }

    static AssetDiscovery discoverBestParamsFiles(File projectBase, String targetFolder) {
        File root = modelRoot(projectBase, targetFolder);
        if (root == null || !root.isDirectory()) {
            return AssetDiscovery.empty();
        }
        LinkedHashMap<String, String> labels = new LinkedHashMap<>();
        File[] modelDirs = root.listFiles(File::isDirectory);
        if (modelDirs != null) {
            Arrays.stream(modelDirs)
                    .filter(dir -> visibleModelName(dir.getName()))
                    .sorted(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER))
                    .forEach(dir -> {
                        File params = new File(new File(dir, "tuning"), "best_params.json");
                        if (params.isFile()) {
                            labels.put(params.getAbsolutePath(), dir.getName() + " tuning best parameters");
                        }
                    });
        }
        return new AssetDiscovery(labels);
    }

    private static File modelRoot(File projectBase, String targetFolder) {
        return projectBase == null ? null : new File(new File(new File(projectBase, "astra"), "models"), targetFolder);
    }

    private static AssetDiscovery discoverNamedAssets(File root, String suffixToStrip, Set<String> extensions) {
        if (root == null || !root.isDirectory()) {
            return AssetDiscovery.empty();
        }
        LinkedHashMap<String, String> labels = new LinkedHashMap<>();
        File[] files = root.listFiles();
        if (files != null) {
            Arrays.stream(files)
                    .filter(File::isFile)
                    .filter(file -> hasAllowedExtension(file.getName(), extensions))
                    .sorted(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER))
                    .forEach(file -> {
                        String value = stripAssetExtension(file.getName(), suffixToStrip, extensions);
                        labels.put(value, value);
                    });
        }
        return new AssetDiscovery(labels);
    }

    private static AssetDiscovery discoverPathAssets(File projectBase, File root, Set<String> extensions) {
        if (root == null || !root.isDirectory()) {
            return AssetDiscovery.empty();
        }
        LinkedHashMap<String, String> labels = new LinkedHashMap<>();
        try (var stream = Files.walk(root.toPath())) {
            stream.filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .filter(file -> hasAllowedExtension(file.getName(), extensions))
                    .sorted(Comparator.comparing(File::getAbsolutePath, String.CASE_INSENSITIVE_ORDER))
                    .forEach(file -> labels.put(file.getAbsolutePath(), projectRelativeLabel(projectBase, file)));
        } catch (IOException e) {
            logger.warn("Unable to discover ASTRA assets under {}", root, e);
        }
        return new AssetDiscovery(labels);
    }

    private static boolean hasAllowedExtension(String name, Set<String> extensions) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return extensions.stream().anyMatch(lower::endsWith);
    }

    private static String stripAssetExtension(String fileName, String suffixToStrip, Set<String> extensions) {
        String value = fileName;
        for (String extension : extensions) {
            if (value.toLowerCase(Locale.ROOT).endsWith(extension)) {
                value = value.substring(0, value.length() - extension.length());
                break;
            }
        }
        if (suffixToStrip != null && !suffixToStrip.isBlank() && value.endsWith(suffixToStrip)) {
            value = value.substring(0, value.length() - suffixToStrip.length());
        }
        return value;
    }

    private static String projectRelativeLabel(File projectBase, File file) {
        if (projectBase != null) {
            try {
                Path base = projectBase.toPath().toAbsolutePath().normalize();
                Path path = file.toPath().toAbsolutePath().normalize();
                if (path.startsWith(base)) {
                    return base.relativize(path).toString();
                }
            } catch (RuntimeException ignored) {
                // Fall through to a stable absolute label.
            }
        }
        return file.getAbsolutePath();
    }

    private static void installColocalizationRunModeEditor(String scriptName, List<EditableConstant> constants) {
        if (!GuiPresentation.supportsAnalysisHeaderActions(scriptName)) {
            return;
        }
        constants.stream()
                .filter(c -> "MODES_TO_RUN".equals(c.name) && "List".equals(c.type))
                .findFirst()
                .ifPresent(c -> c.setCustomEditor(new StageModeEditor(
                        GuiPresentation.visibleRunModeOptions(scriptName, c.options),
                        c.displayValue
                )));
    }

    private static List<String> projectImageNames(QuPathGUI qupath) {
        if (qupath == null || qupath.getProject() == null) {
            return List.of();
        }
        return qupath.getProject().getImageList().stream()
                .map(entry -> String.valueOf(entry.getImageName()))
                .filter(name -> !name.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    static List<String> targetModelControlNames(String detectionTarget) {
        String target = detectionTarget == null ? "BOTH" : detectionTarget.trim().toUpperCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        if (!"CELL".equals(target)) {
            names.addAll(List.of("NUC_MODEL_SOURCE", "NUC_MODEL_NAME", "NUC_MODEL_FILE", "NUC_SAVED_MODEL_ID"));
        }
        if (!"NUCLEUS".equals(target)) {
            names.addAll(List.of("CELL_MODEL_SOURCE", "CELL_MODEL_NAME", "CELL_MODEL_FILE", "CELL_SAVED_MODEL_ID"));
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

    private static Node createContent(QuPathGUI qupath, String scriptName, List<EditableConstant> constants, boolean applyChannelDefaults,
                                      RunFeedback feedback, String schemaId, String sourceScriptSha256, SettingsProfileState profileState,
                                      Consumer<ActionEvent> exportAction, Consumer<Button> exportButtonSink,
                                      Consumer<ActionEvent> resetImageAction, Consumer<Button> resetImageButtonSink,
                                      Consumer<ActionEvent> resetProjectAction, Consumer<Button> resetProjectButtonSink) {
        List<ImageChannel> imageChannels = imageChannels(qupath);
        if (applyChannelDefaults) {
            applyImageChannelDefaults(constants, imageChannels);
        }
        boolean colocalization = isColocalizationConfig(constants);
        constants.forEach(EditableConstant::markDefault);
        SettingsAutosave autosave = SettingsAutosave.create(qupath, scriptName, schemaId, sourceScriptSha256, constants, profileState, feedback);
        autosave.restoreIfAvailable();
        LauncherViewState launcherViewState = LauncherViewState.load(
                projectBaseDirectoryOrNull(qupath),
                scriptName,
                schemaId,
                sourceScriptSha256
        );
        installColocalizationRunModeEditor(scriptName, constants);
        installProjectImageNameSelector(qupath, constants);
        installProjectAssetSelectors(qupath, constants);

        VBox root = new VBox(14.0);
        root.setPadding(new Insets(0));
        root.setStyle("-fx-background-color: " + PAPER + "; -fx-font-family: " + FONT_STACK + ";");

        VBox header = new VBox(12.0);
        header.setPadding(new Insets(22.0, CONTENT_HORIZONTAL_MARGIN, 20.0, CONTENT_HORIZONTAL_MARGIN));
        HBox titleRow = new HBox(12.0);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        VBox titleBlock = new VBox(4.0);
        Label title = new Label(scriptName);
        title.getStyleClass().add("astra-header-title");
        Label subtitle = new Label(descriptionFor(scriptName));
        subtitle.setWrapText(true);
        subtitle.getStyleClass().add("astra-header-subtitle");
        titleBlock.getChildren().addAll(title, subtitle);
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox actionCluster = new HBox(8.0);
        actionCluster.setAlignment(Pos.CENTER_RIGHT);
        addStyleClass(actionCluster, "astra-header-actions");
        Button reset = new Button("Reset settings");
        reset.setFocusTraversable(false);
        styleButton(reset, ButtonRole.HEADER);
        reset.setOnAction(event -> {
            constants.forEach(EditableConstant::resetEditor);
            profileState.resetToScriptDefaults();
            autosave.clear();
            feedback.info("Settings reset to the current script/image-aware defaults. No QuPath hierarchy data was changed.");
        });
        Button saveProfile = new Button("Save settings profile");
        saveProfile.setFocusTraversable(false);
        styleButton(saveProfile, ButtonRole.HEADER);
        saveProfile.setOnAction(event -> saveSettingsProfileWithDialog(qupath, scriptName, schemaId, sourceScriptSha256, constants, profileState, autosave, feedback));
        Button loadProfile = new Button("Load settings profile");
        loadProfile.setFocusTraversable(false);
        styleButton(loadProfile, ButtonRole.HEADER);
        loadProfile.setOnAction(event -> loadSettingsProfileWithDialog(qupath, scriptName, schemaId, sourceScriptSha256, constants, profileState, autosave, feedback));
        actionCluster.getChildren().addAll(reset, saveProfile, loadProfile);
        if (GuiPresentation.supportsAnalysisHeaderActions(scriptName)) {
            Button resetImage = new Button("Reset Image");
            resetImage.setFocusTraversable(false);
            styleButton(resetImage, ButtonRole.DANGER);
            resetImage.setTooltip(new Tooltip("Analysis action: delete only ASTRA objects and measurements recorded for the current image in the analysis ledger."));
            resetImage.setOnAction(resetImageAction::accept);
            if (resetImageButtonSink != null) {
                resetImageButtonSink.accept(resetImage);
            }
            Button resetProject = new Button("Reset Project");
            resetProject.setFocusTraversable(false);
            styleButton(resetProject, ButtonRole.DANGER);
            resetProject.setTooltip(new Tooltip("Analysis action: delete only ASTRA objects and measurements recorded for every project image in the analysis ledger."));
            resetProject.setOnAction(resetProjectAction::accept);
            if (resetProjectButtonSink != null) {
                resetProjectButtonSink.accept(resetProject);
            }
            actionCluster.getChildren().addAll(resetImage, resetProject);
        }
        if (GuiPresentation.supportsHeaderExport(scriptName)) {
            Button export = new Button("Export");
            export.setFocusTraversable(false);
            styleButton(export, ButtonRole.SUCCESS);
            export.setTooltip(new Tooltip("Analysis action: write result files only if the current hierarchy matches the last successful QUANTIFY."));
            export.setOnAction(exportAction::accept);
            if (exportButtonSink != null) {
                exportButtonSink.accept(export);
            }
            actionCluster.getChildren().add(export);
        }
        Button outputToggle = new Button(launcherViewState.outputVisible() ? "Hide Output" : "Show Output");
        outputToggle.setFocusTraversable(false);
        styleButton(outputToggle, ButtonRole.HEADER);
        actionCluster.getChildren().add(outputToggle);
        titleRow.getChildren().addAll(titleBlock, titleSpacer, actionCluster);
        header.getChildren().addAll(titleRow, createPipelineFlow(scriptName));
        AnimatedGradientHeader animatedHeader = new AnimatedGradientHeader(header);
        actionCluster.getChildren().add(createHeaderOptionsMenu(animatedHeader));

        VBox body = new VBox(14.0);
        body.setPadding(new Insets(0, 0, 18.0, 0));
        body.getChildren().add(createChannelPanel(imageChannels));
        List<SettingsSectionModel> routineSections = new ArrayList<>();
        if (colocalization) {
            routineSections.add(new SettingsSectionModel(
                    "Colocalization Setup",
                    "Target, segmentation, marker checks, thresholds, and background.",
                    "Guided",
                    createColocalizationPanel(qupath, constants, imageChannels, autosave),
                    false
            ));
        }
        for (String group : orderedGroups(constants, false)) {
            List<EditableConstant> groupConstants = constants.stream()
                    .filter(c -> !c.advanced && group.equals(c.group))
                    .filter(c -> !isHandledByColocalizationPanel(c.name, colocalization))
                    .sorted(Comparator.comparingInt(EditableConstant::uiOrder))
                    .toList();
            if (!groupConstants.isEmpty()) {
                routineSections.add(new SettingsSectionModel(
                        group,
                        groupDashboardDescription(group),
                        groupDashboardBadge(group, groupConstants),
                        createSection(group, groupConstants, true, autosave),
                        false
                ));
            }
        }

        List<SettingsSectionModel> advancedSections = new ArrayList<>();
        for (String group : orderedGroups(constants, true)) {
            List<EditableConstant> groupConstants = constants.stream()
                    .filter(c -> c.advanced && group.equals(c.group))
                    .filter(c -> !isHandledByColocalizationPanel(c.name, colocalization))
                    .sorted(Comparator.comparingInt(EditableConstant::uiOrder))
                    .toList();
            if (!groupConstants.isEmpty()) {
                Node section = "Advanced".equalsIgnoreCase(group)
                        ? createUngroupedSection(groupConstants, false, autosave)
                        : createSection(group, groupConstants, false, autosave);
                advancedSections.add(new SettingsSectionModel(
                        group,
                        groupDashboardDescription(group),
                        "Advanced",
                        section,
                        true
                ));
            }
        }

        body.getChildren().add(createSettingsNavigator("Settings Dashboard",
                "Choose one settings group at a time, or switch to All Settings for a full review.",
                routineSections,
                launcherViewState));
        if (!advancedSections.isEmpty()) {
            Node advanced = createSettingsNavigator("Advanced Settings",
                    "Developer controls for deliberate tuning, diagnostics, and publication-specific overrides.",
                    advancedSections,
                    launcherViewState);
            if (GuiPresentation.advancedControlsLockedByDefault()) {
                advanced.setVisible(false);
                advanced.setManaged(false);
                VBox advancedUnlock = createAdvancedUnlockPanel(advanced);
                body.getChildren().addAll(advancedUnlock, advanced);
            } else {
                body.getChildren().add(advanced);
            }
        }

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(720.0);
        scroll.setPrefViewportHeight(700.0);
        scroll.setStyle("-fx-background: " + PAPER + "; -fx-background-color: " + PAPER + ";");
        addStyleClass(scroll, "astra-settings-scroll");
        HBox.setHgrow(scroll, Priority.ALWAYS);

        HBox workspace = new HBox(14.0);
        workspace.setPadding(new Insets(CONTENT_HORIZONTAL_MARGIN, CONTENT_HORIZONTAL_MARGIN, 18.0, CONTENT_HORIZONTAL_MARGIN));
        workspace.setStyle("-fx-background-color: " + PAPER + ";");
        addStyleClass(workspace, "astra-launcher-workspace");
        Node feedbackNode = feedback.node();
        setNodeVisibleManaged(feedbackNode, launcherViewState.outputVisible());
        outputToggle.setText(launcherViewState.outputVisible() ? "Hide Output" : "Show Output");
        outputToggle.setOnAction(event -> {
            boolean outputVisible = !feedbackNode.isManaged();
            setNodeVisibleManaged(feedbackNode, outputVisible);
            launcherViewState.setOutputVisible(outputVisible);
            launcherViewState.save();
            outputToggle.setText(outputVisible ? "Hide Output" : "Show Output");
        });
        workspace.getChildren().addAll(scroll, feedbackNode);
        VBox.setVgrow(workspace, Priority.ALWAYS);

        root.getChildren().addAll(animatedHeader, workspace);
        return root;
    }

    private static void saveSettingsProfileWithDialog(QuPathGUI qupath, String scriptName, String schemaId, String sourceScriptSha256,
                                                      List<EditableConstant> constants, SettingsProfileState profileState,
                                                      SettingsAutosave autosave, RunFeedback feedback) {
        File profileDir;
        try {
            profileDir = settingsProfileDirectory(projectBaseDirectory(qupath), scriptName);
            Files.createDirectories(profileDir.toPath());
        } catch (RuntimeException | IOException e) {
            Dialogs.showErrorMessage("ASTRA Settings Profile", "Unable to resolve ASTRA project settings directory:\n" + e.getMessage());
            return;
        }
        TextInputDialog nameDialog = new TextInputDialog("default");
        nameDialog.initOwner(qupath.getStage());
        nameDialog.setTitle("Save ASTRA Settings Profile");
        nameDialog.setHeaderText("Save settings profile");
        nameDialog.setContentText("Profile name:");
        installAstraStyles(nameDialog.getDialogPane());
        nameDialog.getDialogPane().setStyle("-fx-background-color: " + PAPER + "; -fx-font-family: " + FONT_STACK + ";");
        String profileName = nameDialog.showAndWait()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .orElse(null);
        if (profileName == null) {
            return;
        }
        String safeName = profileName.replaceAll("[^A-Za-z0-9_.-]+", "_");
        File target = new File(profileDir, safeName.endsWith(".json") ? safeName : safeName + ".json");
        try {
            SettingsProfile profile = createSettingsProfile(scriptName, schemaId, sourceScriptSha256, constants);
            writeSettingsProfile(target, profile);
            String hash = sha256File(target);
            profileState.loadedProfile(target.getName(), target.getAbsolutePath(), hash);
            autosave.saveCurrent();
            feedback.info("Saved ASTRA settings profile: " + target.getAbsolutePath());
        } catch (IOException | RuntimeException e) {
            Dialogs.showErrorMessage("ASTRA Settings Profile", "Unable to save settings profile:\n" + e.getMessage());
        }
    }

    private static void loadSettingsProfileWithDialog(QuPathGUI qupath, String scriptName, String schemaId, String sourceScriptSha256,
                                                      List<EditableConstant> constants, SettingsProfileState profileState,
                                                      SettingsAutosave autosave, RunFeedback feedback) {
        File profileDir;
        try {
            profileDir = settingsProfileDirectory(projectBaseDirectory(qupath), scriptName);
            Files.createDirectories(profileDir.toPath());
        } catch (RuntimeException | IOException e) {
            Dialogs.showErrorMessage("ASTRA Settings Profile", "Unable to resolve ASTRA project settings directory:\n" + e.getMessage());
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Load ASTRA Settings Profile");
        chooser.setInitialDirectory(profileDir);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ASTRA settings profiles", "*.json"));
        File selected = chooser.showOpenDialog(qupath.getStage());
        if (selected == null) {
            return;
        }
        try {
            SettingsProfile profile = readSettingsProfile(selected);
            applySettingsProfile(profile, scriptName, schemaId, sourceScriptSha256, constants);
            constants.forEach(EditableConstant::syncEditorFromValue);
            profileState.loadedProfile(selected.getName(), selected.getAbsolutePath(), sha256File(selected));
            autosave.saveCurrent();
            feedback.info("Loaded ASTRA settings profile: " + selected.getAbsolutePath());
            List<String> missingChannels = missingProfileChannels(profile, imageChannels(qupath).stream().map(ImageChannel::getName).filter(Objects::nonNull).toList());
            if (!missingChannels.isEmpty()) {
                feedback.warn("Loaded profile references channels not present in the open image: " + missingChannels);
            }
        } catch (IOException | RuntimeException e) {
            Dialogs.showErrorMessage("ASTRA Settings Profile", "Unable to load settings profile:\n" + e.getMessage());
        }
    }

    private static File projectBaseDirectory(QuPathGUI qupath) {
        if (qupath.getProject() == null || qupath.getProject().getPath() == null) {
            throw new IllegalStateException("Open a QuPath project before saving or loading ASTRA settings profiles.");
        }
        Path projectPath = qupath.getProject().getPath();
        Path base = Files.isDirectory(projectPath) ? projectPath : projectPath.getParent();
        if (base == null) {
            throw new IllegalStateException("Unable to resolve the QuPath project directory for ASTRA settings profiles.");
        }
        return base.toFile();
    }

    private static File projectBaseDirectoryOrNull(QuPathGUI qupath) {
        try {
            return projectBaseDirectory(qupath);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static VBox sectionShell(String titleText, String subtitleText) {
        VBox box = new VBox(SECTION_CONTENT_GAP);
        box.setPadding(new Insets(14.0));
        addStyleClass(box, "astra-section-shell");
        Label title = new Label(titleText);
        title.getStyleClass().add("astra-section-title");
        Label subtitle = new Label(subtitleText);
        subtitle.getStyleClass().add("astra-section-subtitle");
        subtitle.setWrapText(true);
        box.getChildren().addAll(title, subtitle);
        return box;
    }

    private static VBox createAdvancedUnlockPanel(Node advanced) {
        VBox box = sectionShell("Advanced Locked", GuiPresentation.advancedControlsDescription());
        TextField phrase = new TextField();
        phrase.setPromptText("Unlock phrase");
        phrase.setPrefColumnCount(18);
        addStyleClass(phrase, "astra-input");
        Button unlock = new Button("Unlock advanced");
        unlock.setFocusTraversable(false);
        styleButton(unlock, ButtonRole.HEADER);
        Label status = new Label("");
        status.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-text-fill: " + CORAL + ";");
        Runnable tryUnlock = () -> {
            String expected = GuiPresentation.advancedUnlockPhrase();
            if (expected.equals(phrase.getText() == null ? "" : phrase.getText().trim())) {
                advanced.setVisible(true);
                advanced.setManaged(true);
                box.setVisible(false);
                box.setManaged(false);
            } else {
                status.setText("Enter the developer unlock phrase exactly.");
            }
        };
        unlock.setOnAction(event -> tryUnlock.run());
        phrase.setOnAction(event -> tryUnlock.run());
        HBox row = new HBox(10.0, phrase, unlock);
        row.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(row, status);
        return box;
    }

    private static Node createSettingsNavigator(String titleText, String subtitleText,
                                                List<SettingsSectionModel> sections,
                                                LauncherViewState launcherViewState) {
        VBox root = sectionShell(titleText, subtitleText);
        if (sections == null || sections.isEmpty()) {
            Label empty = new Label("No settings are available for this view.");
            empty.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-text-fill: " + MUTED + ";");
            root.getChildren().add(empty);
            return root;
        }

        HBox viewRow = new HBox(8.0);
        viewRow.setAlignment(Pos.CENTER_LEFT);
        Button dashboard = new Button("Dashboard");
        Button allSettings = new Button("All Settings");
        styleButton(dashboard, ButtonRole.SECONDARY);
        styleButton(allSettings, ButtonRole.SECONDARY);
        viewRow.getChildren().addAll(dashboard, allSettings);

        VBox host = new VBox(SECTION_CONTENT_GAP);
        host.setFillWidth(true);

        Runnable[] showDashboard = new Runnable[1];
        Runnable[] showAll = new Runnable[1];
        Consumer<SettingsSectionModel> showOne = section -> {
            markSettingsViewButtons(dashboard, allSettings, LauncherViewMode.DASHBOARD);
            VBox focused = new VBox(SECTION_CONTENT_GAP);
            focused.setFillWidth(true);
            HBox top = new HBox(10.0);
            top.setAlignment(Pos.CENTER_LEFT);
            Button back = new Button("Back to Dashboard");
            styleButton(back, ButtonRole.SECONDARY);
            Label title = new Label(section.title());
            title.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 16px; -fx-font-weight: 900; -fx-text-fill: " + INK + ";");
            top.getChildren().addAll(back, title);
            back.setOnAction(event -> showDashboard[0].run());
            detachFromParent(section.content());
            focused.getChildren().setAll(top, section.content());
            host.getChildren().setAll(focused);
        };

        showDashboard[0] = () -> {
            markSettingsViewButtons(dashboard, allSettings, LauncherViewMode.DASHBOARD);
            launcherViewState.setViewMode(LauncherViewMode.DASHBOARD);
            launcherViewState.save();
            FlowPane cards = new FlowPane(12.0, 12.0);
            addStyleClass(cards, "astra-card-dashboard");
            cards.setPrefWrapLength(640.0);
            for (SettingsSectionModel section : sections) {
                cards.getChildren().add(createSettingsCard(section, () -> showOne.accept(section)));
            }
            host.getChildren().setAll(cards);
        };
        showAll[0] = () -> {
            markSettingsViewButtons(dashboard, allSettings, LauncherViewMode.ALL_SETTINGS);
            launcherViewState.setViewMode(LauncherViewMode.ALL_SETTINGS);
            launcherViewState.save();
            VBox all = new VBox(SECTION_CONTENT_GAP);
            all.setFillWidth(true);
            sections.forEach(section -> {
                detachFromParent(section.content());
                all.getChildren().add(section.content());
            });
            host.getChildren().setAll(all);
        };
        dashboard.setOnAction(event -> showDashboard[0].run());
        allSettings.setOnAction(event -> showAll[0].run());
        if (launcherViewState.viewMode() == LauncherViewMode.ALL_SETTINGS) {
            showAll[0].run();
        } else {
            showDashboard[0].run();
        }

        root.getChildren().addAll(viewRow, host);
        return root;
    }

    private static void markSettingsViewButtons(Button dashboard, Button allSettings,
                                                LauncherViewMode active) {
        setToggleActive(dashboard, active == LauncherViewMode.DASHBOARD);
        setToggleActive(allSettings, active == LauncherViewMode.ALL_SETTINGS);
    }

    private static void setToggleActive(Node node, boolean active) {
        if (node == null) {
            return;
        }
        if (active) {
            addStyleClass(node, "astra-button-toggle-active");
        } else {
            node.getStyleClass().remove("astra-button-toggle-active");
        }
    }

    private static Button createSettingsCard(SettingsSectionModel section, Runnable openAction) {
        GuiPresentation.StandardGroup group = GuiPresentation.standardGroup(section.title());
        Button card = new Button();
        card.setFocusTraversable(false);
        card.setMinSize(205.0, 118.0);
        card.setPrefSize(215.0, 126.0);
        card.setMaxWidth(230.0);
        addStyleClass(card, "astra-settings-card");
        addStyleClass(card, "astra-settings-card-theme-" + cssToken(group.accentTheme()));
        addStyleClass(card, "astra-settings-card-importance-" + cssToken(group.importance()));
        if (section.advanced()) {
            addStyleClass(card, "astra-settings-card-advanced");
        }
        Rectangle accent = new Rectangle(4.0, 88.0);
        addStyleClass(accent, "astra-settings-card-accent");
        addStyleClass(accent, "astra-accent-" + cssToken(group.accentTheme()));
        VBox content = new VBox(7.0);
        content.setAlignment(Pos.TOP_LEFT);
        Label title = new Label(section.title());
        title.setWrapText(true);
        title.getStyleClass().add("astra-settings-card-title");
        Label description = new Label(section.description());
        description.setWrapText(true);
        description.getStyleClass().add("astra-settings-card-description");
        Label badge = new Label(section.badge());
        badge.getStyleClass().add(section.advanced() ? "astra-badge-advanced" : "astra-badge");
        badge.getStyleClass().add("astra-badge-importance-" + cssToken(group.importance()));
        content.getChildren().addAll(badge, title, description);
        HBox shell = new HBox(10.0, accent, content);
        shell.setAlignment(Pos.TOP_LEFT);
        card.setGraphic(shell);
        card.setOnAction(event -> openAction.run());
        return card;
    }

    private static String cssToken(String text) {
        if (text == null || text.isBlank()) {
            return "default";
        }
        return text.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }

    private static void detachFromParent(Node node) {
        if (node == null || node.getParent() == null) {
            return;
        }
        if (node.getParent() instanceof Pane pane) {
            pane.getChildren().remove(node);
        }
    }

    private static String groupDashboardDescription(String group) {
        return GuiPresentation.standardGroup(group).description();
    }

    private static String groupDashboardBadge(String group, List<EditableConstant> constants) {
        long changed = constants.stream().filter(c -> !c.isAtDefaultValue()).count();
        if (changed > 0) {
            return changed + " changed";
        }
        if (constants.stream().anyMatch(c -> c.name.contains("MODEL"))) {
            return "Models";
        }
        if (constants.stream().anyMatch(c -> c.name.contains("CLASSIFIER"))) {
            return "Project assets";
        }
        if ("Runtime & Performance".equals(group) || "Diagnostics".equals(group)) {
            return GuiPresentation.standardGroup(group).importance();
        }
        return GuiPresentation.standardGroup(group).importance();
    }

    private static boolean isColocalizationConfig(List<EditableConstant> constants) {
        return constants.stream().anyMatch(c -> "COLOCALIZATION_CHECKS".equals(c.name))
                && constants.stream().anyMatch(c -> "DETECTION_TARGET".equals(c.name));
    }

    static boolean isHandledByColocalizationPanel(String name, boolean colocalization) {
        if (!colocalization) {
            return false;
        }
        return Set.of(
                "DETECTION_TARGET",
                "NUC_MODEL_SOURCE", "NUC_MODEL_NAME", "NUC_MODEL_FILE", "NUC_SAVED_MODEL_ID",
                "CELL_MODEL_SOURCE", "CELL_MODEL_NAME", "CELL_MODEL_FILE", "CELL_SAVED_MODEL_ID",
                "NUCLEUS_SEGMENTATION_CHANNELS", "CELL_SEGMENTATION_CHANNELS",
                "COLOCALIZATION_CHECKS",
                "EXPRESSION_CLASSIFICATION_MODE", "DISPLAY_COLOCALIZATION_CHECK",
                "POSITIVITY_METHOD", "PIXEL_POSITIVE_FRACTION_MIN",
                "THRESHOLD_POPULATION",
                "THRESHOLD_MODE", "GMM_COMPONENTS", "OTSU_CLASS_COUNT", "THRESHOLD_SCOPE", "THRESHOLD_SELECTED_IMAGE_NAMES",
                "MATCH_THRESHOLD_IMAGE_NAMES_AGAINST_ORIGINAL",
                "MANUAL_INTENSITY_BOUNDARIES_BY_MARKER", "MANUAL_INTENSITY_THRESHOLDS", "THRESHOLD_PROVENANCE_BY_MARKER",
                "RANGE_THRESHOLD_FRACTIONS_BY_MARKER", "RANGE_THRESHOLD_FRACTION_BY_MARKER",
                "BACKGROUND_MODE", "LOCAL_BACKGROUND_PERCENTILE",
                "BACKGROUND_SUBTRACTION_BY_CHANNEL"
        ).contains(name);
    }

    private static VBox createColocalizationPanel(QuPathGUI qupath, List<EditableConstant> constants, List<ImageChannel> imageChannels, SettingsAutosave autosave) {
        Map<String, EditableConstant> byName = new LinkedHashMap<>();
        constants.forEach(c -> byName.put(c.name, c));
        List<String> channelNames = imageChannels.stream().map(ImageChannel::getName).filter(Objects::nonNull).toList();
        File projectBase = projectBaseDirectoryOrNull(qupath);
        SavedModelDiscovery nucleusModels = discoverSavedModelIds(projectBase, "nucleus");
        SavedModelDiscovery cellModels = discoverSavedModelIds(projectBase, "cell");

        VBox box = sectionShell("Colocalization Setup", "Target-specific controls for segmentation models, segmentation channels, marker checks, thresholding, and background correction.");
        EditableConstant detectionTarget = byName.get("DETECTION_TARGET");

        VBox targetPanel = semanticCard(displayLabel("DETECTION_TARGET"), "Choose whether colocalization runs nucleus segmentation, cell segmentation, or paired nucleus/cell detection.");
        if (detectionTarget != null) {
            Node editor = detectionTarget.createEditor();
            HBox row = labeledRow(displayLabel("DETECTION_TARGET"), editor, 160.0);
            detectionTarget.addChangeListener(autosave::markManualEditAndSave);
            targetPanel.getChildren().add(row);
        }

        VBox modelPanel = semanticCard("Target Models", "Choose nucleus and cell model initializers independently. Generic shared MODEL_* values are not shown as separate colocalization controls.");
        VBox nucleusModel = targetModelGroup("Nucleus Model", byName.get("NUC_MODEL_SOURCE"), byName.get("NUC_MODEL_NAME"), byName.get("NUC_MODEL_FILE"), byName.get("NUC_SAVED_MODEL_ID"), nucleusModels, autosave);
        VBox cellModel = targetModelGroup("Cell Model", byName.get("CELL_MODEL_SOURCE"), byName.get("CELL_MODEL_NAME"), byName.get("CELL_MODEL_FILE"), byName.get("CELL_SAVED_MODEL_ID"), cellModels, autosave);
        modelPanel.getChildren().addAll(nucleusModel, cellModel);

        VBox segmentationPanel = semanticCard("Segmentation Channels", "Choose one or more image channels for the target-specific segmentation inputs.");
        EditableConstant nucChannels = byName.get("NUCLEUS_SEGMENTATION_CHANNELS");
        EditableConstant cellChannels = byName.get("CELL_SEGMENTATION_CHANNELS");
        ChannelCheckboxEditor nucEditor = new ChannelCheckboxEditor("Nucleus segmentation channels", channelNames, nucChannels == null ? "[]" : nucChannels.displayValue);
        ChannelCheckboxEditor cellEditor = new ChannelCheckboxEditor("Cell segmentation channels", channelNames, cellChannels == null ? "[]" : cellChannels.displayValue);
        if (nucChannels != null) {
            nucChannels.setCustomEditor(nucEditor);
            nucEditor.addChangeListener(autosave::markManualEditAndSave);
        }
        if (cellChannels != null) {
            cellChannels.setCustomEditor(cellEditor);
            cellEditor.addChangeListener(autosave::markManualEditAndSave);
        }
        segmentationPanel.getChildren().addAll(nucEditor, cellEditor);

        VBox checksPanel = semanticCard("Colocalization Checks", "Build each positivity check from a label, one compartment, and the channels that must be positive together.");
        EditableConstant checksConstant = byName.get("COLOCALIZATION_CHECKS");
        ColocalizationChecksEditor checksEditor = new ColocalizationChecksEditor(channelNames, checksConstant == null ? "[]" : checksConstant.displayValue);
        if (checksConstant != null) {
            checksConstant.setCustomEditor(checksEditor);
            checksEditor.addChangeListener(autosave::markManualEditAndSave);
        }
        checksPanel.getChildren().add(checksEditor);
        EditableConstant displayCheckConstant = byName.get("DISPLAY_COLOCALIZATION_CHECK");
        installDisplayCheckSelector(checksPanel, displayCheckConstant, checksEditor, autosave);

        List<MarkerKeyMapEditor> markerMapEditors = new ArrayList<>();
        installMarkerKeyMapEditor(byName.get("MANUAL_INTENSITY_BOUNDARIES_BY_MARKER"), MarkerMapValueType.NUMERIC,
                "Manual expression boundaries appear here after checks define marker keys.", markerMapEditors);
        installMarkerKeyMapEditor(byName.get("MANUAL_INTENSITY_THRESHOLDS"), MarkerMapValueType.NUMERIC,
                "Manual threshold values appear here after checks define marker keys.", markerMapEditors);
        installMarkerKeyMapEditor(byName.get("RANGE_THRESHOLD_FRACTIONS_BY_MARKER"), MarkerMapValueType.NUMERIC,
                "Range-percent expression boundary fractions appear here after checks define marker keys.", markerMapEditors);
        installMarkerKeyMapEditor(byName.get("RANGE_THRESHOLD_FRACTION_BY_MARKER"), MarkerMapValueType.NUMERIC,
                "Range-percent threshold fractions appear here after checks define marker keys.", markerMapEditors);
        installMarkerKeyMapEditor(byName.get("THRESHOLD_PROVENANCE_BY_MARKER"), MarkerMapValueType.TEXT,
                "Threshold provenance rows appear here after checks define marker keys.", markerMapEditors);
        installMarkerKeyMapEditor(byName.get("BACKGROUND_SUBTRACTION_BY_CHANNEL"), MarkerMapValueType.NUMERIC,
                "Manual background offsets appear here after checks define marker keys.", markerMapEditors);
        Runnable refreshMarkerKeyEditors = () -> {
            List<String> markerKeys = thresholdedMarkerKeysFromChecks(checksEditor.checks());
            markerMapEditors.forEach(editor -> editor.refresh(markerKeys));
        };
        refreshMarkerKeyEditors.run();
        checksEditor.addChangeListener(() -> {
            refreshMarkerKeyEditors.run();
            autosave.markManualEditAndSave();
        });

        VBox thresholdPanel = semanticCard("Thresholds & Background", "Choose how positivity thresholds and explicit background correction are resolved before running colocalization.");
        Map<String, RowNodes> thresholdRows = new LinkedHashMap<>();
        addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get("EXPRESSION_CLASSIFICATION_MODE"), displayLabel("EXPRESSION_CLASSIFICATION_MODE"), autosave);
        addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get("POSITIVITY_METHOD"), displayLabel("POSITIVITY_METHOD"), autosave);
        addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get("PIXEL_POSITIVE_FRACTION_MIN"), displayLabel("PIXEL_POSITIVE_FRACTION_MIN"), autosave);
        addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get("THRESHOLD_POPULATION"), displayLabel("THRESHOLD_POPULATION"), autosave);
        addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get("THRESHOLD_MODE"), displayLabel("THRESHOLD_MODE"), autosave);
        addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get("GMM_COMPONENTS"), displayLabel("GMM_COMPONENTS"), autosave);
        addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get("OTSU_CLASS_COUNT"), displayLabel("OTSU_CLASS_COUNT"), autosave);
        addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get("THRESHOLD_SCOPE"), displayLabel("THRESHOLD_SCOPE"), autosave);
        addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get("THRESHOLD_SELECTED_IMAGE_NAMES"), displayLabel("THRESHOLD_SELECTED_IMAGE_NAMES"), autosave);
        addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get("MATCH_THRESHOLD_IMAGE_NAMES_AGAINST_ORIGINAL"), displayLabel("MATCH_THRESHOLD_IMAGE_NAMES_AGAINST_ORIGINAL"), autosave);
        addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get("MANUAL_INTENSITY_BOUNDARIES_BY_MARKER"), displayLabel("MANUAL_INTENSITY_BOUNDARIES_BY_MARKER"), autosave);
        addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get("MANUAL_INTENSITY_THRESHOLDS"), displayLabel("MANUAL_INTENSITY_THRESHOLDS"), autosave);
        addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get("THRESHOLD_PROVENANCE_BY_MARKER"), displayLabel("THRESHOLD_PROVENANCE_BY_MARKER"), autosave);
        addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get("RANGE_THRESHOLD_FRACTIONS_BY_MARKER"), displayLabel("RANGE_THRESHOLD_FRACTIONS_BY_MARKER"), autosave);
        addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get("RANGE_THRESHOLD_FRACTION_BY_MARKER"), displayLabel("RANGE_THRESHOLD_FRACTION_BY_MARKER"), autosave);
        addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get("BACKGROUND_MODE"), displayLabel("BACKGROUND_MODE"), autosave);
        addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get("LOCAL_BACKGROUND_PERCENTILE"), displayLabel("LOCAL_BACKGROUND_PERCENTILE"), autosave);
        addColocalizationConstantRow(thresholdPanel, thresholdRows, byName.get("BACKGROUND_SUBTRACTION_BY_CHANNEL"), displayLabel("BACKGROUND_SUBTRACTION_BY_CHANNEL"), autosave);

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
        installColocalizationThresholdVisibility(byName, thresholdRows, thresholdPanel);

        box.getChildren().addAll(targetPanel, modelPanel, segmentationPanel, checksPanel, thresholdPanel);
        return box;
    }

    private static void installMarkerKeyMapEditor(EditableConstant constant, MarkerMapValueType valueType,
                                                   String emptyMessage, List<MarkerKeyMapEditor> editors) {
        if (constant == null) {
            return;
        }
        MarkerKeyMapEditor editor = new MarkerKeyMapEditor(constant.displayValue, valueType, emptyMessage);
        constant.setCustomEditor(editor);
        editors.add(editor);
    }

    private static void installDisplayCheckSelector(VBox checksPanel, EditableConstant displayCheckConstant,
                                                    ColocalizationChecksEditor checksEditor,
                                                    SettingsAutosave autosave) {
        if (displayCheckConstant == null) {
            return;
        }
        ComboBox<String> displayCheck = new ComboBox<>();
        displayCheck.setPromptText("First check");
        displayCheck.setMaxWidth(Double.MAX_VALUE);
        styleComboBox(displayCheck);
        displayCheck.setValue(EditableConstant.stripStringQuotes(displayCheckConstant.type, displayCheckConstant.displayValue));
        displayCheckConstant.setCustomEditor(displayCheck);
        displayCheckConstant.addChangeListener(autosave::markManualEditAndSave);
        Runnable refreshChoices = () -> {
            List<String> labels = checksEditor.checks().stream()
                    .map(ColocalizationCheck::label)
                    .map(label -> label == null ? "" : label.trim())
                    .filter(label -> !label.isBlank())
                    .distinct()
                    .toList();
            String current = displayCheck.getValue();
            displayCheck.getItems().setAll(labels);
            if (labels.isEmpty()) {
                displayCheck.setValue("");
            } else if (current == null || current.isBlank() || !labels.contains(current)) {
                displayCheck.setValue(labels.get(0));
            }
        };
        checksPanel.getChildren().add(labeledRow("Display in QuPath UI", displayCheck, 160.0));
        checksEditor.addChangeListener(refreshChoices);
        refreshChoices.run();
    }

    private static void addColocalizationConstantRow(VBox panel, Map<String, RowNodes> rows, EditableConstant constant,
                                                     String label, SettingsAutosave autosave) {
        if (constant == null) {
            return;
        }
        Node editor = constant.createEditor();
        constant.addChangeListener(autosave::markManualEditAndSave);
        if (constant.name.contains("MATCH_") && constant.name.contains("_AGAINST_ORIGINAL")) {
            Tooltip.install(editor, new Tooltip("Use this when QuPath display names were renamed after import and ASTRA should match against the original imported image names."));
        }
        Node row = editor instanceof MarkerKeyMapEditor || editor instanceof ProjectImageSelectionEditor
                ? labeledVariableBlock(label, editor)
                : labeledRow(label, editor, 180.0);
        panel.getChildren().add(row);
        rows.put(constant.name, new RowNodes(row, row));
    }

    private static void installColocalizationThresholdVisibility(Map<String, EditableConstant> byName,
                                                                 Map<String, RowNodes> rows,
                                                                 VBox panel) {
        Runnable update = () -> {
            Set<String> visibleRows = colocalizationThresholdVisibilityState(
                    optionValue(byName, "EXPRESSION_CLASSIFICATION_MODE"),
                    optionValue(byName, "POSITIVITY_METHOD"),
                    optionValue(byName, "THRESHOLD_MODE"),
                    optionValue(byName, "THRESHOLD_SCOPE"),
                    optionValue(byName, "BACKGROUND_MODE")
            );
            rows.forEach((name, row) -> setVisible(rows, name, visibleRows.contains(name)));

            panel.requestLayout();
            if (panel.getParent() != null) {
                panel.getParent().requestLayout();
            }
        };
        List.of("EXPRESSION_CLASSIFICATION_MODE", "POSITIVITY_METHOD", "THRESHOLD_MODE", "THRESHOLD_SCOPE", "BACKGROUND_MODE").forEach(name -> {
            EditableConstant constant = byName.get(name);
            if (constant != null) {
                constant.addChangeListener(update);
                constant.addOptionListener(update);
            }
        });
        update.run();
    }

    static Set<String> colocalizationThresholdVisibilityState(String expressionMode,
                                                              String positivityMethod,
                                                              String thresholdMode,
                                                              String thresholdScope,
                                                              String backgroundMode) {
        Set<String> rows = new LinkedHashSet<>();
        boolean pixelLevel = "PIXEL_LEVEL_SCORE".equals(expressionMode);
        rows.add("EXPRESSION_CLASSIFICATION_MODE");
        rows.add("THRESHOLD_MODE");
        rows.add("THRESHOLD_SCOPE");
        if (!pixelLevel) {
            rows.add("POSITIVITY_METHOD");
            rows.add("THRESHOLD_POPULATION");
            rows.add("BACKGROUND_MODE");
            if ("PIXEL_POSITIVE_FRACTION".equals(positivityMethod)) {
                rows.add("PIXEL_POSITIVE_FRACTION_MIN");
            }
        }
        if ("SELECTED_IMAGES".equals(thresholdScope)) {
            rows.add("THRESHOLD_SELECTED_IMAGE_NAMES");
            rows.add("MATCH_THRESHOLD_IMAGE_NAMES_AGAINST_ORIGINAL");
        }
        if ("GAUSSIAN_MIXTURE".equals(thresholdMode) || "LOG_GAUSSIAN_MIXTURE".equals(thresholdMode)) {
            rows.add("GMM_COMPONENTS");
        }
        if ("AUTO_OTSU_PER_CHANNEL".equals(thresholdMode)) {
            rows.add("OTSU_CLASS_COUNT");
        }
        if ("MANUAL".equals(thresholdMode)) {
            rows.add(pixelLevel ? "MANUAL_INTENSITY_BOUNDARIES_BY_MARKER" : "MANUAL_INTENSITY_THRESHOLDS");
            rows.add("THRESHOLD_PROVENANCE_BY_MARKER");
        }
        if ("RANGE_PERCENT".equals(thresholdMode)) {
            rows.add(pixelLevel ? "RANGE_THRESHOLD_FRACTIONS_BY_MARKER" : "RANGE_THRESHOLD_FRACTION_BY_MARKER");
        }
        if (!pixelLevel && "MANUAL_OFFSET".equals(backgroundMode)) {
            rows.add("BACKGROUND_SUBTRACTION_BY_CHANNEL");
        }
        if (!pixelLevel && "LOCAL_PERCENTILE".equals(backgroundMode)) {
            rows.add("LOCAL_BACKGROUND_PERCENTILE");
        }
        return rows;
    }

    private static String optionValue(Map<String, EditableConstant> byName, String name) {
        EditableConstant constant = byName.get(name);
        return constant == null ? "" : constant.optionValue();
    }

    private static VBox semanticCard(String titleText, String subtitleText) {
        VBox box = new VBox(CARD_CONTENT_GAP);
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

    private static VBox targetModelGroup(String title, EditableConstant source, EditableConstant name, EditableConstant file,
                                         EditableConstant savedModelId, SavedModelDiscovery savedModelDiscovery, SettingsAutosave autosave) {
        VBox group = new VBox(PARAMETER_ROW_GAP);
        group.setPadding(new Insets(10.0));
        group.setStyle("-fx-background-color: white; -fx-border-color: #d7e2e6; -fx-border-radius: 5; -fx-background-radius: 5;");
        Label label = new Label(title);
        label.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 13px; -fx-font-weight: 900; -fx-text-fill: " + INK + ";");
        group.getChildren().add(label);
        if (savedModelId != null) {
            applySavedModelIdDiscovery(savedModelId, savedModelDiscovery);
        }
        Map<String, RowNodes> rows = new LinkedHashMap<>();
        for (EditableConstant constant : Arrays.asList(source, name, file, savedModelId)) {
            if (constant == null) {
                continue;
            }
            Node editor = constant.createEditor();
            HBox row = labeledRow(displayLabel(constant.name), editor, 160.0);
            group.getChildren().add(row);
            rows.put(constant.name, new RowNodes(row, editor));
            constant.addChangeListener(autosave::markManualEditAndSave);
        }
        if (savedModelDiscovery != null && !savedModelDiscovery.invalidModels().isEmpty()) {
            Label invalid = new Label("Invalid saved model folders: " + savedModelDiscovery.invalidModels());
            invalid.setWrapText(true);
            invalid.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 11px; -fx-text-fill: #9b3126;");
            group.getChildren().add(invalid);
        }
        Runnable update = () -> {
            String selected = source == null ? "" : source.optionValue();
            setVisible(rows, name == null ? "" : name.name, selected.isBlank() || "MODEL_NAME".equals(selected));
            setVisible(rows, file == null ? "" : file.name, "FILE".equals(selected));
            setVisible(rows, savedModelId == null ? "" : savedModelId.name, "SAVED".equals(selected));
        };
        if (source != null) {
            source.addChangeListener(update);
        }
        update.run();
        return group;
    }

    static void applySavedModelIdDiscovery(EditableConstant savedModelId, SavedModelDiscovery discovery) {
        if (savedModelId == null || discovery == null) {
            return;
        }
        savedModelId.setCustomEditor(assetBackedCombo(savedModelId, AssetDiscovery.fromValues(discovery.validIds())));
    }

    private static ComboBox<String> assetBackedCombo(EditableConstant constant, AssetDiscovery discovery) {
        ComboBox<String> combo = new ComboBox<>();
        combo.setEditable(false);
        AssetDiscovery safeDiscovery = discovery == null ? AssetDiscovery.empty() : discovery;
        combo.getItems().addAll(safeDiscovery.values());
        String current = EditableConstant.stripStringQuotes(constant.type, constant.displayValue);
        combo.setValue(current);
        combo.setMaxWidth(Double.MAX_VALUE);
        styleComboBox(combo);
        combo.setButtonCell(assetComboCell(safeDiscovery));
        combo.setCellFactory(list -> assetComboCell(safeDiscovery));
        return combo;
    }

    private static ListCell<String> assetComboCell(AssetDiscovery discovery) {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : discovery.labelFor(item));
                setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-text-fill: " + INK + ";");
            }
        };
    }

    private static void styleComboBox(ComboBox<String> combo) {
        combo.setStyle("");
        addStyleClass(combo, "astra-combo");
        styleComboBoxText(combo);
        if (combo.isEditable()) {
            combo.getEditor().setStyle("");
            addStyleClass(combo.getEditor(), "astra-input");
        }
        combo.skinProperty().addListener((obs, oldSkin, newSkin) -> Platform.runLater(() -> styleComboBoxSubnodes(combo)));
        combo.sceneProperty().addListener((obs, oldScene, newScene) -> Platform.runLater(() -> styleComboBoxSubnodes(combo)));
        Platform.runLater(() -> styleComboBoxSubnodes(combo));
    }

    private static void styleComboBoxText(ComboBox<String> combo) {
        combo.setButtonCell(readableComboCell());
        combo.setCellFactory(list -> readableComboCell());
    }

    private static void styleComboBoxSubnodes(ComboBox<String> combo) {
        Node arrowButton = combo.lookup(".arrow-button");
        if (arrowButton != null) {
            arrowButton.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-background-radius: 0 4 4 0;");
        }
        Node arrow = combo.lookup(".arrow");
        if (arrow != null) {
            arrow.setStyle("-fx-background-color: " + TEAL_DARK + ";");
        }
        Node editor = combo.lookup(".text-field");
        if (editor instanceof TextField textField) {
            textField.setStyle("");
            addStyleClass(textField, "astra-input");
        }
        Node selectedCell = combo.lookup(".list-cell");
        if (selectedCell != null) {
            selectedCell.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-text-fill: " + INK + ";");
        }
    }

    private static ListCell<String> readableComboCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-text-fill: " + INK + ";");
            }
        };
    }

    private static ListCell<String> readableListCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-text-fill: " + INK + ";");
            }
        };
    }

    private static void setNodeVisible(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
        node.setDisable(!visible);
    }

    private static void setNodeVisibleManaged(Node node, boolean visible) {
        if (node == null) {
            return;
        }
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private static Node createPipelineFlow(String scriptName) {
        HBox flow = new HBox(8.0);
        flow.setAlignment(Pos.CENTER_LEFT);
        List<String> stages = GuiPresentation.workflowSequence(scriptName);
        if (stages.isEmpty()) {
            stages = List.of("Training", "Tuning", "Validation", pipelineStage(scriptName));
        }
        String active = GuiPresentation.workflowActiveLabel(scriptName);
        if (active.isBlank()) {
            active = pipelineStage(scriptName);
        }
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

    private static Node createHeaderOptionsMenu(AnimatedGradientHeader animatedHeader) {
        MenuButton options = new MenuButton("Options");
        options.setFocusTraversable(false);
        styleButton(options, ButtonRole.HEADER);
        options.setTooltip(new Tooltip("Header display options."));

        ToggleButton staticMode = headerSegmentButton("Static");
        ToggleButton dynamicMode = headerSegmentButton("Dynamic");
        ToggleGroup modeGroup = new ToggleGroup();
        staticMode.setToggleGroup(modeGroup);
        dynamicMode.setToggleGroup(modeGroup);
        staticMode.setUserData(AnimatedGradientHeader.HeaderMode.STATIC);
        dynamicMode.setUserData(AnimatedGradientHeader.HeaderMode.DYNAMIC);

        ToggleButton slow = headerSegmentButton(AnimatedGradientHeader.MotionSpeed.SLOW.label());
        ToggleButton smooth = headerSegmentButton(AnimatedGradientHeader.MotionSpeed.SMOOTH.label());
        ToggleButton lively = headerSegmentButton(AnimatedGradientHeader.MotionSpeed.LIVELY.label());
        ToggleGroup speedGroup = new ToggleGroup();
        slow.setToggleGroup(speedGroup);
        smooth.setToggleGroup(speedGroup);
        lively.setToggleGroup(speedGroup);
        slow.setUserData(AnimatedGradientHeader.MotionSpeed.SLOW);
        smooth.setUserData(AnimatedGradientHeader.MotionSpeed.SMOOTH);
        lively.setUserData(AnimatedGradientHeader.MotionSpeed.LIVELY);

        HBox modeRow = headerSegmentRow("Header", staticMode, dynamicMode);
        HBox motionRow = headerSegmentRow("Motion", slow, smooth, lively);
        VBox menuContent = new VBox(8.0, modeRow, motionRow);
        menuContent.setPadding(new Insets(8.0));
        menuContent.setStyle("-fx-background-color: white; -fx-background-radius: 6; -fx-border-color: #bfd3d8; -fx-border-radius: 6;");

        AnimatedGradientHeader.HeaderMode initialMode = headerModePreference();
        AnimatedGradientHeader.MotionSpeed initialSpeed = headerMotionPreference();
        modeGroup.selectToggle(initialMode == AnimatedGradientHeader.HeaderMode.STATIC ? staticMode : dynamicMode);
        speedGroup.selectToggle(switch (initialSpeed) {
            case SLOW -> slow;
            case SMOOTH -> smooth;
            case LIVELY -> lively;
        });
        animatedHeader.setHeaderMode(initialMode);
        animatedHeader.setMotionSpeed(initialSpeed);
        setHeaderMotionRowEnabled(motionRow, initialMode == AnimatedGradientHeader.HeaderMode.DYNAMIC);

        modeGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) {
                modeGroup.selectToggle(oldToggle == null ? dynamicMode : oldToggle);
                return;
            }
            AnimatedGradientHeader.HeaderMode mode = (AnimatedGradientHeader.HeaderMode) newToggle.getUserData();
            HEADER_MODE_PREFERENCE.set(mode.name());
            animatedHeader.setHeaderMode(mode);
            setHeaderMotionRowEnabled(motionRow, mode == AnimatedGradientHeader.HeaderMode.DYNAMIC);
        });
        speedGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) {
                speedGroup.selectToggle(oldToggle == null ? smooth : oldToggle);
                return;
            }
            AnimatedGradientHeader.MotionSpeed speed = (AnimatedGradientHeader.MotionSpeed) newToggle.getUserData();
            HEADER_MOTION_PREFERENCE.set(speed.name());
            animatedHeader.setMotionSpeed(speed);
        });
        List.of(staticMode, dynamicMode, slow, smooth, lively)
                .forEach(button -> button.selectedProperty().addListener((obs, wasSelected, isSelected) -> styleHeaderSegmentButton(button)));
        List.of(staticMode, dynamicMode, slow, smooth, lively).forEach(PipelineLauncher::styleHeaderSegmentButton);

        options.getItems().add(new CustomMenuItem(menuContent, false));
        return options;
    }

    private static AnimatedGradientHeader.HeaderMode headerModePreference() {
        try {
            return AnimatedGradientHeader.HeaderMode.valueOf(HEADER_MODE_PREFERENCE.get());
        } catch (RuntimeException e) {
            HEADER_MODE_PREFERENCE.set(AnimatedGradientHeader.HeaderMode.DYNAMIC.name());
            return AnimatedGradientHeader.HeaderMode.DYNAMIC;
        }
    }

    private static AnimatedGradientHeader.MotionSpeed headerMotionPreference() {
        try {
            return AnimatedGradientHeader.MotionSpeed.valueOf(HEADER_MOTION_PREFERENCE.get());
        } catch (RuntimeException e) {
            HEADER_MOTION_PREFERENCE.set(AnimatedGradientHeader.MotionSpeed.SMOOTH.name());
            return AnimatedGradientHeader.MotionSpeed.SMOOTH;
        }
    }

    private static HBox headerSegmentRow(String labelText, ToggleButton... buttons) {
        HBox row = new HBox(7.0);
        row.setAlignment(Pos.CENTER_LEFT);
        Label label = new Label(labelText);
        label.setMinWidth(52.0);
        label.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 11px; -fx-font-weight: 900; -fx-text-fill: " + MUTED + ";");
        HBox controls = new HBox(3.0, buttons);
        controls.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().addAll(label, controls);
        return row;
    }

    private static ToggleButton headerSegmentButton(String text) {
        ToggleButton button = new ToggleButton(text);
        button.setFocusTraversable(false);
        button.setMinWidth(58.0);
        button.setMinHeight(25.0);
        return button;
    }

    private static void styleHeaderSegmentButton(ToggleButton button) {
        boolean selected = button.isSelected();
        button.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 11px; -fx-font-weight: 900; " +
                "-fx-background-color: " + (selected ? TEAL_DARK : "#edf4f5") + "; " +
                "-fx-text-fill: " + (selected ? "white" : TEAL_DARK) + "; " +
                "-fx-border-color: " + (selected ? TEAL_DARK : "#bfd3d8") + "; " +
                "-fx-border-radius: 4; -fx-background-radius: 4;");
    }

    private static void setHeaderMotionRowEnabled(HBox motionRow, boolean enabled) {
        motionRow.setDisable(!enabled);
        motionRow.setOpacity(enabled ? 1.0d : 0.48d);
    }

    private static HBox labeledRow(String labelText, Node editor, double labelWidth) {
        HBox row = new HBox(PARAMETER_ROW_GAP);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinHeight(PARAMETER_ROW_HEIGHT);
        row.setPrefHeight(PARAMETER_ROW_HEIGHT);
        Label label = new Label(labelText);
        label.setMinWidth(labelWidth);
        label.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-font-weight: 800; -fx-text-fill: " + INK + ";");
        if (editor instanceof Region region) {
            region.setMinHeight(PARAMETER_ROW_HEIGHT);
        }
        HBox.setHgrow(editor, Priority.ALWAYS);
        row.getChildren().addAll(label, editor);
        return row;
    }

    private static VBox labeledVariableBlock(String labelText, Node editor) {
        VBox block = new VBox(6.0);
        block.setFillWidth(true);
        Label label = new Label(labelText);
        label.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-font-weight: 800; -fx-text-fill: " + INK + ";");
        if (editor instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        block.getChildren().addAll(label, editor);
        return block;
    }

    private static VBox nestedField(String labelText, Node editor) {
        VBox box = new VBox(4.0);
        Label label = new Label(labelText);
        label.setStyle(nestedLabelStyle());
        if (editor instanceof Region region) {
            region.setMinHeight(PARAMETER_ROW_HEIGHT);
        }
        box.getChildren().addAll(label, editor);
        return box;
    }

    private static String nestedLabelStyle() {
        return "-fx-font-family: " + FONT_STACK + "; -fx-font-size: 10.5px; -fx-font-weight: 900; -fx-text-fill: " + INK + ";";
    }

    private static CollapsibleSection createSection(String title, List<EditableConstant> constants, boolean expanded, SettingsAutosave autosave) {
        GridPane grid = new GridPane();
        grid.setPadding(new Insets(14.0));
        grid.setHgap(12.0);
        grid.setVgap(PARAMETER_ROW_GAP);
        grid.setStyle("-fx-background-color: " + PANEL + "; -fx-border-color: #d7e2e6; -fx-border-radius: 0 0 6 6; -fx-background-radius: 0 0 6 6;");

        int row = 0;
        Map<String, RowNodes> rows = new LinkedHashMap<>();
        for (EditableConstant constant : constants) {
            HBox labelBox = new HBox(7.0);
            labelBox.setAlignment(Pos.CENTER_LEFT);
            addStyleClass(labelBox, "astra-parameter-row");
            addStyleClass(labelBox, row % 2 == 0 ? "astra-parameter-row-even" : "astra-parameter-row-odd");
            Rectangle anchor = new Rectangle(4.0, 18.0);
            addStyleClass(anchor, "astra-parameter-anchor");
            addStyleClass(anchor, "astra-parameter-anchor-" + parameterTypeToken(constant));
            Label label = new Label(displayLabel(constant.name));
            label.setMinWidth(220.0);
            label.setWrapText(true);
            label.getStyleClass().add("astra-parameter-label");
            Button info = new Button("?");
            info.setMinSize(18.0, 18.0);
            info.setMaxSize(18.0, 18.0);
            info.setFocusTraversable(false);
            styleButton(info, ButtonRole.HELP);
            Tooltip tooltip = new Tooltip(constant.helpText());
            tooltip.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px;");
            info.setTooltip(tooltip);
            installReliableTooltip(info, tooltip);
            info.setOnAction(event -> showParameterHelpDialog(constant));
            labelBox.getChildren().addAll(anchor, label, info);
            labelBox.setMinHeight(PARAMETER_ROW_HEIGHT);
            grid.add(labelBox, 0, row);

            Node editor = constant.createEditor();
            addStyleClass(editor, "astra-parameter-editor");
            addStyleClass(editor, row % 2 == 0 ? "astra-parameter-row-even" : "astra-parameter-row-odd");
            constant.addChangeListener(autosave::markManualEditAndSave);
            if (editor instanceof Region region) {
                region.setMinHeight(PARAMETER_ROW_HEIGHT);
            }
            GridPane.setHgrow(editor, Priority.ALWAYS);
            grid.add(editor, 1, row++);
            rows.put(constant.name, new RowNodes(labelBox, editor));
        }
        installConditionalVisibility(constants, rows);

        return new CollapsibleSection(title, grid, expanded);
    }

    private static String parameterTypeToken(EditableConstant constant) {
        String name = constant == null ? "" : constant.name;
        String group = constant == null ? "" : constant.group;
        if (name.contains("CHANNEL")) return "channel";
        if (name.contains("MODEL") || name.contains("CLASSIFIER")) return "model";
        if (name.contains("CELLPROB") || name.contains("FLOW")
                || name.contains("DIAMETER") || name.contains("SEGMENTATION")) {
            return "segmentation";
        }
        if (name.contains("VSMC") || name.contains("LUMEN")
                || name.contains("ENDOTHELIUM") || name.contains("OVERLAP")) {
            return "biological";
        }
        if (name.contains("THRESHOLD") || name.contains("BACKGROUND")
                || name.contains("INTENSITY")) {
            return "threshold";
        }
        if ("Runtime & Performance".equals(group)
                || name.contains("GPU") || name.contains("BATCH")
                || name.contains("THREAD") || name.contains("ASYNC")) {
            return "runtime";
        }
        if (name.contains("EXPORT") || name.contains("RESULTS")
                || name.contains("OUTPUT")) {
            return "export";
        }
        if (name.contains("DEBUG") || name.contains("LOG")
                || name.contains("QC") || name.contains("DIAGNOSTIC")) {
            return "diagnostic";
        }
        return "setting";
    }

    private static void showParameterHelpDialog(EditableConstant constant) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("ASTRA Parameter Help");
        dialog.setHeaderText(displayLabel(constant.name));

        VBox content = new VBox(12.0);
        content.setPadding(new Insets(14.0));
        content.setStyle("-fx-background-color: " + PAPER + "; -fx-font-family: " + FONT_STACK + ";");

        GridPane summary = new GridPane();
        summary.setHgap(12.0);
        summary.setVgap(8.0);
        addHelpSummaryRow(summary, 0, "Parameter", constant.name);
        addHelpSummaryRow(summary, 1, "Current value", safeCurrentDisplayValue(constant));
        addHelpSummaryRow(summary, 2, "Default value", constant.defaultDisplayValue());

        Label quickTitle = new Label("Quick Help");
        quickTitle.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-font-weight: 900; -fx-text-fill: " + TEAL_DARK + ";");
        Label quick = new Label(constant.helpText());
        quick.setWrapText(true);
        quick.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-text-fill: " + INK + ";");

        Label detailTitle = new Label("Details");
        detailTitle.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-font-weight: 900; -fx-text-fill: " + TEAL_DARK + ";");
        TextArea details = new TextArea(constant.detailsText());
        details.setEditable(false);
        details.setWrapText(true);
        details.setPrefRowCount(11);
        details.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px;");

        content.getChildren().addAll(summary, quickTitle, quick, detailTitle, details);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setPrefWidth(620.0);
        installAstraStyles(dialog.getDialogPane());
        dialog.getDialogPane().setStyle("-fx-background-color: " + PAPER + "; -fx-font-family: " + FONT_STACK + ";");
        Node close = dialog.getDialogPane().lookupButton(ButtonType.CLOSE);
        if (close instanceof ButtonBase button) {
            styleButton(button, ButtonRole.SECONDARY);
        }
        dialog.showAndWait();
    }

    private static void addHelpSummaryRow(GridPane grid, int row, String labelText, String valueText) {
        Label label = new Label(labelText);
        label.setMinWidth(110.0);
        label.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 11px; -fx-font-weight: 900; -fx-text-fill: " + MUTED + ";");
        Label value = new Label(valueText == null || valueText.isBlank() ? "(blank)" : valueText);
        value.setWrapText(true);
        value.setStyle("-fx-font-family: " + MONO_FONT_STACK + "; -fx-font-size: 11px; -fx-text-fill: " + INK + ";");
        GridPane.setHgrow(value, Priority.ALWAYS);
        grid.add(label, 0, row);
        grid.add(value, 1, row);
    }

    private static String safeCurrentDisplayValue(EditableConstant constant) {
        try {
            return constant.currentDisplayValue();
        } catch (RuntimeException e) {
            return constant.defaultDisplayValue();
        }
    }

    private static GridPane createUngroupedSection(List<EditableConstant> constants, boolean expanded, SettingsAutosave autosave) {
        GridPane grid = new GridPane();
        grid.setPadding(new Insets(0));
        grid.setHgap(12.0);
        grid.setVgap(PARAMETER_ROW_GAP);
        int row = 0;
        for (EditableConstant constant : constants) {
            Node editor = constant.createEditor();
            Label label = new Label(displayLabel(constant.name));
            label.setMinWidth(170.0);
            label.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-font-weight: 800; -fx-text-fill: " + INK + ";");
            grid.add(label, 0, row);
            grid.add(editor, 1, row);
            GridPane.setHgrow(editor, Priority.ALWAYS);
            constant.addChangeListener(autosave::markManualEditAndSave);
            row++;
        }
        return grid;
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

    static SavedModelDiscovery discoverSavedModelIds(File projectBase, String targetFolder) {
        if (projectBase == null) {
            return new SavedModelDiscovery(List.of(), Map.of());
        }
        File root = new File(new File(new File(projectBase, "astra"), "models"), targetFolder);
        if (!root.isDirectory()) {
            return new SavedModelDiscovery(List.of(), Map.of());
        }
        List<String> valid = new ArrayList<>();
        Map<String, String> invalid = new LinkedHashMap<>();
        File[] entries = root.listFiles();
        if (entries == null) {
            return new SavedModelDiscovery(List.of(), Map.of(root.getName(), "Unable to list model directory."));
        }
        for (File entry : entries) {
            if (entry == null || !visibleModelName(entry.getName())) {
                continue;
            }
            if (entry.isFile() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".cpm")) {
                invalid.put(entry.getName(), "Legacy direct .cpm file; expected <model_id>/model.cpm plus model_metadata.json.");
                continue;
            }
            if (!entry.isDirectory()) {
                continue;
            }
            File metadata = new File(entry, "model_metadata.json");
            if (!metadata.isFile()) {
                invalid.put(entry.getName(), "Missing model_metadata.json.");
                continue;
            }
            try (FileReader reader = new FileReader(metadata, StandardCharsets.UTF_8)) {
                Object parsed = PROFILE_GSON.fromJson(reader, Object.class);
                if (!(parsed instanceof Map<?, ?> map)) {
                    invalid.put(entry.getName(), "model_metadata.json root is not an object.");
                    continue;
                }
                Object modelId = map.get("model_id");
                Object target = map.get("target");
                if (!entry.getName().equals(String.valueOf(modelId))) {
                    invalid.put(entry.getName(), "metadata model_id does not match folder name.");
                    continue;
                }
                if (!targetFolder.equalsIgnoreCase(targetFolderForMetadata(String.valueOf(target)))) {
                    invalid.put(entry.getName(), "metadata target does not match " + targetFolder + ".");
                    continue;
                }
                valid.add(entry.getName());
            } catch (IOException | RuntimeException e) {
                invalid.put(entry.getName(), "Malformed model_metadata.json: " + e.getMessage());
            }
        }
        Collections.sort(valid);
        return new SavedModelDiscovery(List.copyOf(valid), Map.copyOf(invalid));
    }

    private static boolean visibleModelName(String name) {
        return name != null && !name.isBlank() && !name.startsWith(".") && !name.startsWith("._");
    }

    private static String targetFolderForMetadata(String target) {
        String normalized = target == null ? "" : target.trim().toUpperCase(Locale.ROOT);
        if ("NUCLEUS".equals(normalized) || "NUC".equals(normalized)) {
            return "nucleus";
        }
        if ("CELL".equals(normalized)) {
            return "cell";
        }
        return "";
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
        ImageAwareChannelDefaults defaults = resolveImageAwareChannelDefaults(names);
        Map<String, EditableConstant> byName = new LinkedHashMap<>();
        constants.forEach(c -> byName.put(c.name, c));
        for (EditableConstant constant : constants) {
            switch (constant.name) {
                case "CHANNEL_DAPI" -> constant.setDisplayStringAllowEmpty(firstOrNull(defaults.nucleusChannels()));
                case "CHANNEL_WGA" -> constant.setDisplayStringAllowEmpty(firstMatching(names, "wga", "wheat germ"));
                case "CHANNEL_ASMA" -> constant.setDisplayStringAllowEmpty(firstMatching(names, "asma", "a-sma", "αsma", "smooth muscle"));
                case "CHANNEL_CD31" -> constant.setDisplayStringAllowEmpty(firstMatching(names, "cd31", "pecam"));
                case "CHANNELS_FOR_NUCLEUS" -> constant.setDisplayListAllowEmpty(defaults.nucleusChannels());
                case "CHANNELS_FOR_CELL" -> constant.setDisplayListAllowEmpty(defaults.cellChannels());
                case "NUCLEUS_SEGMENTATION_CHANNELS" -> constant.setDisplayListAllowEmpty(defaults.nucleusChannels());
                case "CELL_SEGMENTATION_CHANNELS" -> constant.setDisplayListAllowEmpty(defaults.cellChannels());
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
        ImageAwareChannelDefaults defaults = resolveImageAwareChannelDefaults(names);
        List<String> nucleusChannels = defaults.nucleusChannels();
        List<String> cellChannels = defaults.cellChannels();
        setListConstant(constants, "NUCLEUS_SEGMENTATION_CHANNELS", nucleusChannels);
        setListConstant(constants, "CELL_SEGMENTATION_CHANNELS", cellChannels);
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

    static ImageAwareChannelDefaults resolveImageAwareChannelDefaults(List<String> openedChannels) {
        List<String> names = openedChannels == null ? List.of() : openedChannels.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
        List<String> nucleusChannels = names.stream()
                .filter(PipelineLauncher::isNuclearChannel)
                .toList();
        List<String> nonNuclearChannels = names.stream()
                .filter(name -> !isNuclearChannel(name))
                .toList();
        List<String> cellChannels = nucleusChannels.isEmpty() ? List.of() : nonNuclearChannels;
        List<String> markerChannels = names.stream()
                .filter(name -> !isNuclearChannel(name))
                .toList();
        return new ImageAwareChannelDefaults(nucleusChannels, cellChannels, markerChannels,
                !nucleusChannels.isEmpty(), !cellChannels.isEmpty());
    }

    private static boolean isNuclearChannel(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("dapi") || lower.contains("hoechst");
    }

    private static String firstOrNull(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String firstMatching(List<String> names, String... candidates) {
        for (String candidate : candidates) {
            for (String name : names) {
                if (name.toLowerCase(Locale.ROOT).contains(candidate.toLowerCase(Locale.ROOT))) {
                    return name;
                }
            }
        }
        return null;
    }

    record ImageAwareChannelDefaults(List<String> nucleusChannels,
                                     List<String> cellChannels,
                                     List<String> markerChannels,
                                     boolean hasNucleusMarker,
                                     boolean hasCellCandidate) {
    }

    record SavedModelDiscovery(List<String> validIds, Map<String, String> invalidModels) {
    }

    record AssetDiscovery(Map<String, String> labelsByValue) {
        static AssetDiscovery empty() {
            return new AssetDiscovery(Map.of());
        }

        static AssetDiscovery fromValues(List<String> values) {
            LinkedHashMap<String, String> labels = new LinkedHashMap<>();
            if (values != null) {
                values.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .forEach(value -> labels.put(value, value));
            }
            return new AssetDiscovery(Map.copyOf(labels));
        }

        List<String> values() {
            return labelsByValue.keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }

        String labelFor(String value) {
            return labelsByValue.getOrDefault(value, value);
        }
    }

    private record SettingsSectionModel(String title, String description, String badge, Node content, boolean advanced) {
    }

    private static void installAstraStyles(DialogPane pane) {
        if (pane == null) {
            return;
        }
        var resource = PipelineLauncher.class.getResource(LAUNCHER_STYLESHEET_RESOURCE);
        if (resource != null) {
            String css = resource.toExternalForm();
            if (!pane.getStylesheets().contains(css)) {
                pane.getStylesheets().add(css);
            }
        }
        if (!pane.getStyleClass().contains("astra-dialog-pane")) {
            pane.getStyleClass().add("astra-dialog-pane");
        }
    }

    private static void addStyleClass(Node node, String styleClass) {
        if (node != null && styleClass != null && !styleClass.isBlank()
                && !node.getStyleClass().contains(styleClass)) {
            node.getStyleClass().add(styleClass);
        }
    }

    private static void removeStyleClass(Node node, String styleClass) {
        if (node != null && styleClass != null && !styleClass.isBlank()) {
            node.getStyleClass().remove(styleClass);
        }
    }

    private enum ButtonRole {
        PRIMARY,
        SECONDARY,
        HEADER,
        DANGER,
        SUCCESS,
        SMALL,
        HELP
    }

    private static void styleButton(ButtonBase button, ButtonRole role) {
        if (button == null) {
            return;
        }
        button.getStyleClass().removeIf(name -> name.startsWith("astra-button"));
        button.setStyle("");
        button.getStyleClass().add("astra-button");
        button.getStyleClass().add(switch (role) {
            case PRIMARY -> "astra-button-primary";
            case SECONDARY -> "astra-button-secondary";
            case HEADER -> "astra-button-header";
            case DANGER -> "astra-button-danger";
            case SUCCESS -> "astra-button-success";
            case SMALL -> "astra-button-small";
            case HELP -> "astra-button-help";
        });
    }

    private static String semanticButtonStyle() {
        return buttonStyle(ButtonRole.SMALL, false, false, false);
    }

    private static String settingsHeaderButtonStyle() {
        return buttonStyle(ButtonRole.HEADER, false, false, false);
    }

    private static String analysisHeaderButtonStyle() {
        return buttonStyle(ButtonRole.DANGER, false, false, false);
    }

    private static String exportHeaderButtonStyle() {
        return buttonStyle(ButtonRole.SUCCESS, false, false, false);
    }

    private static String buttonStyle(ButtonRole role, boolean hover, boolean pressed, boolean disabled) {
        String bg;
        String text;
        String border;
        String radius = role == ButtonRole.HELP ? "9" : "5";
        String padding = role == ButtonRole.HELP ? "0" : "6 12";
        int size = role == ButtonRole.HELP || role == ButtonRole.SMALL ? 11 : 12;
        switch (role) {
            case PRIMARY -> {
                bg = pressed ? "#0b444a" : hover ? "#17696d" : TEAL;
                text = "white";
                border = pressed ? "#082f34" : "#17696d";
            }
            case SECONDARY -> {
                bg = pressed ? "#d7e5e8" : hover ? "#ecf4f5" : "#f9fcfd";
                text = TEAL_DARK;
                border = pressed ? "#91adb6" : "#b9cdd3";
            }
            case DANGER -> {
                bg = pressed ? "#ffc9bd" : hover ? "#ffddd5" : "#ffe8e2";
                text = "#7c2417";
                border = "#f0a090";
            }
            case SUCCESS -> {
                bg = pressed ? "#bfe6d0" : hover ? "#dff4e8" : "#ecf9f1";
                text = "#17623b";
                border = "#9fd9b7";
            }
            case SMALL -> {
                bg = pressed ? "#d1e3e7" : hover ? "#eef6f7" : "#e6f0f2";
                text = TEAL_DARK;
                border = "#b5cbd2";
            }
            case HELP -> {
                bg = pressed ? "#0b444a" : hover ? "#17696d" : TEAL;
                text = "white";
                border = pressed ? "#082f34" : TEAL;
            }
            case HEADER -> {
                bg = pressed ? "rgba(230,240,242,0.86)" : hover ? "rgba(255,255,255,0.98)" : "rgba(255,255,255,0.92)";
                text = TEAL_DARK;
                border = "rgba(255,255,255,0.95)";
            }
            default -> {
                bg = "#e6f0f2";
                text = TEAL_DARK;
                border = "#b5cbd2";
            }
        }
        String effect = pressed
                ? " -fx-translate-y: 1; -fx-effect: innershadow(gaussian, rgba(0,0,0,0.18), 4, 0.2, 0, 1);"
                : hover
                ? " -fx-effect: dropshadow(gaussian, rgba(10,47,56,0.16), 6, 0.18, 0, 1);"
                : "";
        String opacity = disabled ? " -fx-opacity: 0.55;" : "";
        return "-fx-font-family: " + FONT_STACK + "; -fx-font-size: " + size + "px; -fx-font-weight: 900; "
                + "-fx-padding: " + padding + "; "
                + "-fx-background-color: " + bg + "; -fx-text-fill: " + text + "; "
                + "-fx-border-color: " + border + "; -fx-border-radius: " + radius + "; "
                + "-fx-background-radius: " + radius + ";"
                + effect + opacity;
    }

    private static String checkBoxStyle() {
        return "-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-text-fill: " + INK + ";";
    }

    private static void styleCheckBox(CheckBox box) {
        box.setStyle(checkBoxStyle());
    }

    static List<ColocalizationCheck> parseColocalizationChecks(String rawValue) {
        String raw = rawValue == null ? "" : rawValue;
        List<ColocalizationCheck> out = new ArrayList<>();
        Pattern entryPattern = Pattern.compile("(?s)\\[\\s*LABEL\\s*:\\s*\"([^\"]*)\"\\s*,\\s*COMPARTMENT\\s*:\\s*\"([^\"]*)\"\\s*,\\s*CHANNELS\\s*:\\s*\\[(.*?)\\](?:\\s*,\\s*EXCLUDED_CHANNELS\\s*:\\s*\\[(.*?)\\])?\\s*\\]");
        Matcher matcher = entryPattern.matcher(raw);
        while (matcher.find()) {
            out.add(new ColocalizationCheck(
                    matcher.group(1),
                    matcher.group(2),
                    EditableConstant.csvValues("[" + matcher.group(3) + "]"),
                    matcher.group(4) == null ? List.of() : EditableConstant.csvValues("[" + matcher.group(4) + "]")
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
                    .append("                CHANNELS   : ").append(renderStringList(check.channels())).append(",\n")
                    .append("                EXCLUDED_CHANNELS: ").append(renderStringList(check.excludedChannels())).append("\n")
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

    static List<String> thresholdedMarkerKeysFromChecks(List<ColocalizationCheck> checks) {
        if (checks == null) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (ColocalizationCheck check : checks) {
            String compartment = check.compartment() == null ? "" : check.compartment().trim();
            Set<String> excluded = new LinkedHashSet<>(check.excludedChannels() == null ? List.of() : check.excludedChannels());
            for (String channel : check.channels()) {
                String marker = channel == null ? "" : channel.trim();
                if (!marker.isBlank() && !compartment.isBlank() && !excluded.contains(marker)) {
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
                .map(PipelineLauncher::quoteGroovy)
                .reduce((a, b) -> a + ", " + b)
                .orElse("") + "]";
    }

    private static String quoteGroovy(String value) {
        String clean = value == null ? "" : value;
        return "\"" + clean.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static Map<String, String> parseMarkerKeyMapValues(String rawValue, MarkerMapValueType valueType) {
        String raw = rawValue == null ? "" : rawValue;
        Map<String, String> values = new LinkedHashMap<>();
        Pattern entryPattern = valueType == MarkerMapValueType.TEXT
                ? Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                : Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"\\s*:\\s*([-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?[dD]?)");
        Matcher matcher = entryPattern.matcher(raw);
        while (matcher.find()) {
            String key = unescapeGroovyStringFragment(matcher.group(1));
            String value = valueType == MarkerMapValueType.TEXT
                    ? unescapeGroovyStringFragment(matcher.group(2))
                    : matcher.group(2).replaceAll("[dD]$", "");
            if (!key.isBlank()) {
                values.put(key, value);
            }
        }
        return values;
    }

    static String renderMarkerKeyMapValues(Map<String, String> values, MarkerMapValueType valueType) {
        Map<String, String> filtered = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((key, value) -> {
                String cleanKey = key == null ? "" : key.trim();
                String cleanValue = value == null ? "" : value.trim();
                if (!cleanKey.isBlank() && !cleanValue.isBlank()) {
                    filtered.put(cleanKey, renderMarkerKeyMapValue(cleanKey, cleanValue, valueType));
                }
            });
        }
        if (filtered.isEmpty()) {
            return "[:]";
        }
        StringBuilder out = new StringBuilder("[\n");
        filtered.forEach((key, value) -> out.append("        ")
                .append(quoteGroovy(key))
                .append(": ")
                .append(value)
                .append(",\n"));
        out.append("]");
        return out.toString();
    }

    private static String renderMarkerKeyMapValue(String key, String value, MarkerMapValueType valueType) {
        if (valueType == MarkerMapValueType.TEXT) {
            return quoteGroovy(value);
        }
        String normalized = value.replaceAll("[dD]$", "");
        double parsed;
        try {
            parsed = Double.parseDouble(normalized);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Marker-key map value for " + key + " must be a finite number.");
        }
        if (!Double.isFinite(parsed)) {
            throw new IllegalArgumentException("Marker-key map value for " + key + " must be finite.");
        }
        return Double.toString(parsed) + "d";
    }

    private static String unescapeGroovyStringFragment(String raw) {
        String value = raw == null ? "" : raw;
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    static String formatGuiLogText(String text) {
        return RunLogParser.formatCleanText(text, RunLogSource.QUPATH, RunLogSeverity.NEUTRAL);
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
            setVisible(rows, "NUC_MODEL_NAME", isSelected(byName, "NUC_MODEL_SOURCE", "MODEL_NAME"));
            setVisible(rows, "NUC_MODEL_FILE", isSelected(byName, "NUC_MODEL_SOURCE", "FILE"));
            setVisible(rows, "CELL_MODEL_NAME", isSelected(byName, "CELL_MODEL_SOURCE", "MODEL_NAME"));
            setVisible(rows, "CELL_MODEL_FILE", isSelected(byName, "CELL_MODEL_SOURCE", "FILE"));
            setVisible(rows, "BEST_PARAMS_FILE", isSelected(byName, "PARAM_SOURCE", "BEST_PARAMS_FILE"));
            setVisible(rows, "NUC_BEST_PARAMS_FILE", isSelected(byName, "NUC_PARAM_SOURCE", "BEST_PARAMS_FILE"));
            setVisible(rows, "CELL_BEST_PARAMS_FILE", isSelected(byName, "CELL_PARAM_SOURCE", "BEST_PARAMS_FILE"));
            setVisible(rows, "SELECTED_IMAGE_NAMES", isSelected(byName, "IMAGE_SCOPE", "PROJECT_IMAGE_SELECTION"));
            setVisible(rows, "MATCH_SELECTED_IMAGE_NAMES_AGAINST_ORIGINAL", isSelected(byName, "IMAGE_SCOPE", "PROJECT_IMAGE_SELECTION"));
            setVisible(rows, "THRESHOLD_SELECTED_IMAGE_NAMES", isSelected(byName, "THRESHOLD_SCOPE", "SELECTED_IMAGES"));
            setVisible(rows, "MATCH_THRESHOLD_IMAGE_NAMES_AGAINST_ORIGINAL", isSelected(byName, "THRESHOLD_SCOPE", "SELECTED_IMAGES"));
            setVisible(rows, "PIXEL_POSITIVE_FRACTION_MIN", isSelected(byName, "POSITIVITY_METHOD", "PIXEL_POSITIVE_FRACTION"));
            setVisible(rows, "MANUAL_INTENSITY_THRESHOLDS", isSelected(byName, "THRESHOLD_MODE", "MANUAL"));
            setVisible(rows, "THRESHOLD_PROVENANCE_BY_MARKER", isSelected(byName, "THRESHOLD_MODE", "MANUAL"));
            setVisible(rows, "RANGE_THRESHOLD_FRACTION_BY_MARKER", isSelected(byName, "THRESHOLD_MODE", "RANGE_PERCENT"));
            setVisible(rows, "BACKGROUND_SUBTRACTION_BY_CHANNEL", isSelected(byName, "BACKGROUND_MODE", "MANUAL_OFFSET"));
            setVisible(rows, "LOCAL_BACKGROUND_PERCENTILE", isSelected(byName, "BACKGROUND_MODE", "LOCAL_PERCENTILE"));
            setEnabled(rows, "USE_PIXEL_SCALING", isChecked(byName, "USE_BATCH_MODE"));
            setEnabled(rows,
                    "ROLLBACK_SUCCESSFUL_REGIONS_ON_FAILURE",
                    isChecked(byName, "STOP_ON_REGION_FAILURE"));
            boolean customPreset = isSelected(byName, "SEGMENTATION_PRESET", "CUSTOM");
            rows.keySet().stream()
                    .filter(PipelineLauncher::isAdvancedDetectorParameter)
                    .forEach(name -> setEnabled(rows, name, customPreset));
            boolean useNuclei = isChecked(byName, "USE_NUCLEI");
            setEnabled(rows, "MIN_VSMC_NUCLEUS_FILLED_SMA_OVERLAP_FRACTION", useNuclei);
            setEnabled(rows, "MULTINUCLEATED_CELL_POLICY", useNuclei);
        };
        List.of(
                "NUC_MODEL_SOURCE",
                "CELL_MODEL_SOURCE",
                "PARAM_SOURCE",
                "NUC_PARAM_SOURCE",
                "CELL_PARAM_SOURCE",
                "IMAGE_SCOPE",
                "THRESHOLD_SCOPE",
                "POSITIVITY_METHOD",
                "THRESHOLD_MODE",
                "BACKGROUND_MODE",
                "USE_BATCH_MODE",
                "STOP_ON_REGION_FAILURE",
                "SEGMENTATION_PRESET",
                "USE_NUCLEI"
        ).forEach(name -> {
            EditableConstant constant = byName.get(name);
            if (constant != null) {
                constant.addOptionListener(update);
                constant.addChangeListener(update);
            }
        });
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

    private static boolean isAdvancedDetectorParameter(String name) {
        if (name == null) {
            return false;
        }
        if (name.startsWith("SAM_")) {
            return true;
        }
        return name.matches("^(NUC|CELL)_(DIAMETER_UM|CELLPROB|FLOW|NITER|MIN_MASK_EQUIVALENT_DIAMETER_UM|NORM_PMIN|NORM_PMAX|SIMPLIFY_DISTANCE_PX)$");
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
        row.editor.setDisable(!visible);
    }

    private static void setEnabled(Map<String, RowNodes> rows, String name, boolean enabled) {
        RowNodes row = rows.get(name);
        if (row == null) {
            return;
        }
        row.label.setDisable(!enabled);
        row.editor.setDisable(!enabled);
        if (enabled) {
            removeStyleClass(row.label, "astra-parameter-row-dependent-disabled");
            removeStyleClass(row.editor, "astra-parameter-row-dependent-disabled");
        } else {
            addStyleClass(row.label, "astra-parameter-row-dependent-disabled");
            addStyleClass(row.editor, "astra-parameter-row-dependent-disabled");
        }
    }

    private static void executeAsync(QuPathGUI qupath, String scriptName, String configuredScript, RunFeedback feedback, Button... actionButtons) {
        feedback.start(configuredScript);
        setActionButtonsDisabled(true, actionButtons);
        Future<?> future = qupath.getThreadPoolManager().getSingleThreadExecutor(PipelineLauncher.class).submit(() -> {
            String previousGuiRunActive = System.getProperty(GUI_RUN_ACTIVE_PROPERTY);
            System.setProperty(GUI_RUN_ACTIVE_PROPERTY, "true");
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
                    showRunFailureDialog(scriptName, feedback, String.valueOf(e.getMessage()));
                }
            } catch (Throwable t) {
                logger.error("ASTRA {} failed.", scriptName, t);
                if (feedback.isCancellationRequested()) {
                    feedback.cancelled("Run cancellation was requested before the script stopped.");
                } else {
                    feedback.error(t.getClass().getSimpleName() + ": " + t.getMessage());
                    showRunFailureDialog(scriptName, feedback, t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            } finally {
                restoreGuiRunActiveProperty(previousGuiRunActive);
                Platform.runLater(() -> setActionButtonsDisabled(false, actionButtons));
            }
        });
        feedback.attachFuture(future);
    }

    private static void setActionButtonsDisabled(boolean disabled, Button... buttons) {
        if (buttons == null) {
            return;
        }
        for (Button button : buttons) {
            if (button != null) {
                button.setDisable(disabled);
            }
        }
    }

    private static void showRunFailureDialog(String scriptName, RunFeedback feedback, String message) {
        if (!feedback.markErrorDialogShown()) {
            return;
        }
        Platform.runLater(() -> Dialogs.showErrorMessage(
                "ASTRA " + scriptName,
                String.valueOf(message) + "\n\nSee the ASTRA run log for full details."
        ));
    }

    private static void restoreGuiRunActiveProperty(String previousValue) {
        if (previousValue == null) {
            System.clearProperty(GUI_RUN_ACTIVE_PROPERTY);
        } else {
            System.setProperty(GUI_RUN_ACTIVE_PROPERTY, previousValue);
        }
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

    static String displayLabel(String name) {
        return GuiPresentation.displayLabel(name);
    }

    private static String titleCaseToken(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1);
    }

    private static List<String> orderedGroups(List<EditableConstant> constants, boolean advanced) {
        Set<String> groups = new LinkedHashSet<>();
        for (EditableConstant constant : constants) {
            if (constant.advanced != advanced) {
                continue;
            }
            groups.add(constant.group);
        }
        return groups.stream()
                .sorted(Comparator
                        .comparingInt((String group) -> STANDARD_GROUP_RANK.getOrDefault(group, Integer.MAX_VALUE))
                        .thenComparing(group -> group))
                .toList();
    }

    private static Map<String, Integer> standardGroupRank() {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (int i = 0; i < STANDARD_GROUP_ORDER.size(); i++) {
            ranks.put(STANDARD_GROUP_ORDER.get(i), i);
        }
        return Map.copyOf(ranks);
    }

    private static String descriptionFor(String scriptName) {
        String manifestDescription = GuiPresentation.description(scriptName);
        if (!manifestDescription.isBlank()) {
            return manifestDescription;
        }
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
                "POSITIVITY_METHOD",
                "THRESHOLD_MODE",
                "THRESHOLD_SCOPE",
                "THRESHOLD_SELECTED_IMAGE_NAMES",
                "BACKGROUND_MODE",
                "USE_BATCH_MODE",
                "USE_PIXEL_SCALING",
                "SHOW_GUI_NOTIFICATIONS",
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
        private final StyledLogView output;
        private final Button killButton;
        private final Timeline elapsedHeartbeat;
        private final String scriptName;
        private final AtomicReference<Future<?>> currentRun = new AtomicReference<>();
        private final AtomicBoolean cancellationRequested = new AtomicBoolean(false);
        private final AtomicBoolean errorDialogShown = new AtomicBoolean(false);

        private RunFeedback(String scriptName) {
            this.scriptName = scriptName;
            box = new VBox(8.0);
            box.setPadding(new Insets(14.0));
            addStyleClass(box, "astra-output-pane");
            box.setPrefWidth(430.0);
            box.setMinWidth(360.0);
            box.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(box, Priority.ALWAYS);

            HBox header = new HBox(10.0);
            header.setAlignment(Pos.CENTER_LEFT);
            addStyleClass(header, "astra-output-header");
            progress = new ProgressIndicator();
            progress.setPrefSize(18.0, 18.0);
            progress.setVisible(false);
            progress.setManaged(false);

            status = new Label("Ready to run " + scriptName + ".");
            status.getStyleClass().add("astra-output-status");
            killButton = new Button("Kill Run");
            killButton.setDisable(true);
            killButton.setFocusTraversable(false);
            styleButton(killButton, ButtonRole.DANGER);
            killButton.setOnAction(event -> requestCancellation());
            HBox.setHgrow(status, Priority.ALWAYS);
            header.getChildren().addAll(progress, status, killButton);

            output = new StyledLogView();
            VBox.setVgrow(output, Priority.ALWAYS);
            elapsedHeartbeat = new Timeline(new KeyFrame(Duration.seconds(1.0), event -> output.refreshTimelineElapsed()));
            elapsedHeartbeat.setCycleCount(Animation.INDEFINITE);

            box.getChildren().addAll(header, output);
            info("Script output and run-scoped QuPath/Cellpose logs appear here. Cellpose subprocess stdout/stderr is captured when it is emitted through QuPath logging.");
        }

        private Node node() {
            return box;
        }

        private void start(String configuredScript) {
            Platform.runLater(() -> {
                elapsedHeartbeat.stop();
                output.beginRun(scriptName, configuredScript);
                status.setText("Running...");
                progress.setVisible(true);
                progress.setManaged(true);
                killButton.setDisable(false);
                cancellationRequested.set(false);
                errorDialogShown.set(false);
                appendMessage(RunLogSource.ASTRA, RunLogSeverity.INFO, "ASTRA run started.");
                elapsedHeartbeat.playFromStart();
            });
        }

        private void attachFuture(Future<?> future) {
            currentRun.set(future);
        }

        private boolean isCancellationRequested() {
            return cancellationRequested.get();
        }

        private boolean markErrorDialogShown() {
            return errorDialogShown.compareAndSet(false, true);
        }

        private void requestCancellation() {
            cancellationRequested.set(true);
            Future<?> future = currentRun.get();
            boolean requested = future != null && future.cancel(true);
            // The launcher owns the Java/Groovy Future, not the active VirtualEnvironmentRunner
            // Process instance inside Cellpose. Keep the status honest unless a future
            // runtime API exposes direct process termination.
            appendMessage(RunLogSource.SYSTEM, RunLogSeverity.CANCELLED, requested
                    ? "Cancellation requested. Java/Groovy task interruption was requested. Native Cellpose process may continue until the current operation exits."
                    : "Cancellation marked. The current Java/Groovy task could not be interrupted directly. Native Cellpose process may continue until the current operation exits.");
            Platform.runLater(() -> {
                status.setText("Cancellation requested.");
                status.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 13px; -fx-font-weight: 900; -fx-text-fill: #ffe0a3;");
                killButton.setDisable(true);
            });
        }

        private void info(String message) {
            appendMessage(RunLogSource.ASTRA, RunLogSeverity.INFO, message);
        }

        private void warn(String message) {
            appendMessage(RunLogSource.ASTRA, RunLogSeverity.WARNING, message);
        }

        private void success(String message) {
            Platform.runLater(() -> {
                elapsedHeartbeat.stop();
                output.refreshTimelineElapsed();
                status.setText(message);
                status.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 13px; -fx-font-weight: 900; -fx-text-fill: #bdf2d0;");
                progress.setVisible(false);
                progress.setManaged(false);
                killButton.setDisable(true);
                output.appendMessage(RunLogSource.ASTRA, RunLogSeverity.SUCCESS, message);
            });
        }

        private void error(String message) {
            Platform.runLater(() -> {
                elapsedHeartbeat.stop();
                output.refreshTimelineElapsed();
                status.setText("Run failed.");
                status.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 13px; -fx-font-weight: 900; -fx-text-fill: #ffb8aa;");
                progress.setVisible(false);
                progress.setManaged(false);
                killButton.setDisable(true);
                output.appendMessage(RunLogSource.ASTRA, RunLogSeverity.ERROR, message);
            });
        }

        private void cancelled(String message) {
            Platform.runLater(() -> {
                elapsedHeartbeat.stop();
                output.refreshTimelineElapsed();
                status.setText("Run cancelled.");
                status.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 13px; -fx-font-weight: 900; -fx-text-fill: #ffe0a3;");
                progress.setVisible(false);
                progress.setManaged(false);
                killButton.setDisable(true);
                output.appendMessage(RunLogSource.ASTRA, RunLogSeverity.CANCELLED, message);
            });
        }

        private void appendMessage(RunLogSource source, RunLogSeverity severity, String text) {
            Platform.runLater(() -> output.appendMessage(source, severity, text));
        }

        private void appendLogText(String text) {
            Platform.runLater(() -> output.appendText(text, RunLogSource.QUPATH, RunLogSeverity.NEUTRAL));
        }

        private void appendScriptText(String text, boolean error) {
            Platform.runLater(() -> output.appendText(text, RunLogSource.SCRIPT,
                    error ? RunLogSeverity.ERROR : RunLogSeverity.NEUTRAL));
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
            feedback.appendScriptText(text, error);
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

    private static void notifyListenersAfterModalClose(List<Runnable> listeners) {
        List<Runnable> snapshot = List.copyOf(listeners);
        Platform.runLater(() -> snapshot.forEach(Runnable::run));
    }

    enum LauncherViewMode {
        DASHBOARD,
        ALL_SETTINGS;

        static LauncherViewMode fromText(String raw) {
            if (raw == null || raw.isBlank()) {
                return DASHBOARD;
            }
            try {
                return LauncherViewMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return DASHBOARD;
            }
        }
    }

    static final class LauncherViewState {

        private final File file;
        private final String pipelineName;
        private final String schemaId;
        private final String sourceScriptSha256;
        private LauncherViewMode viewMode = LauncherViewMode.DASHBOARD;
        private boolean outputVisible = true;

        private LauncherViewState(File file, String scriptName, String schemaId,
                                  String sourceScriptSha256) {
            this.file = file;
            this.pipelineName = settingsPipelineName(scriptName);
            this.schemaId = schemaId == null ? "" : schemaId;
            this.sourceScriptSha256 = sourceScriptSha256 == null ? "" : sourceScriptSha256;
        }

        static LauncherViewState load(File projectBase, String scriptName,
                                      String schemaId, String sourceScriptSha256) {
            File file = projectBase == null ? null : launcherViewFile(projectBase, scriptName);
            LauncherViewState state = new LauncherViewState(file, scriptName, schemaId,
                    sourceScriptSha256);
            state.restoreIfCurrent();
            return state;
        }

        LauncherViewMode viewMode() {
            return viewMode;
        }

        void setViewMode(LauncherViewMode viewMode) {
            this.viewMode = viewMode == null ? LauncherViewMode.DASHBOARD : viewMode;
        }

        boolean outputVisible() {
            return outputVisible;
        }

        void setOutputVisible(boolean outputVisible) {
            this.outputVisible = outputVisible;
        }

        void save() {
            if (file == null) {
                return;
            }
            LauncherViewProfile profile = new LauncherViewProfile();
            profile.schema_version = LAUNCHER_VIEW_SCHEMA_VERSION;
            profile.pipeline_name = pipelineName;
            profile.script_schema_id = schemaId;
            profile.source_script_sha256 = sourceScriptSha256;
            profile.view_mode = viewMode.name();
            profile.output_visible = outputVisible;
            try {
                File parent = file.getParentFile();
                if (parent != null) {
                    Files.createDirectories(parent.toPath());
                }
                try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
                    PROFILE_GSON.toJson(profile, writer);
                }
            } catch (IOException | RuntimeException e) {
                logger.debug("Unable to save ASTRA launcher view state at {}", file, e);
            }
        }

        private void restoreIfCurrent() {
            if (file == null || !file.isFile()) {
                return;
            }
            try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
                LauncherViewProfile profile = PROFILE_GSON.fromJson(reader,
                        LauncherViewProfile.class);
                if (profile == null || profile.schema_version != LAUNCHER_VIEW_SCHEMA_VERSION) {
                    return;
                }
                if (!Objects.equals(profile.pipeline_name, pipelineName)
                        || !Objects.equals(profile.script_schema_id, schemaId)
                        || !Objects.equals(profile.source_script_sha256, sourceScriptSha256)) {
                    return;
                }
                viewMode = LauncherViewMode.fromText(profile.view_mode);
                outputVisible = profile.output_visible;
            } catch (IOException | RuntimeException e) {
                logger.debug("Ignoring invalid ASTRA launcher view state at {}", file, e);
            }
        }
    }

    static final class LauncherViewProfile {
        int schema_version;
        String pipeline_name;
        String script_schema_id;
        String source_script_sha256;
        String view_mode;
        boolean output_visible;
    }

    static final class SettingsAutosave {

        private final File file;
        private final String scriptName;
        private final String schemaId;
        private final String sourceScriptSha256;
        private final List<EditableConstant> constants;
        private final SettingsProfileState profileState;
        private final RunFeedback feedback;
        private Timeline pendingSave;
        private boolean warnedDisabled;

        private SettingsAutosave(File file, String scriptName, String schemaId, String sourceScriptSha256,
                                 List<EditableConstant> constants, SettingsProfileState profileState, RunFeedback feedback) {
            this.file = file;
            this.scriptName = scriptName;
            this.schemaId = schemaId;
            this.sourceScriptSha256 = sourceScriptSha256;
            this.constants = constants;
            this.profileState = profileState;
            this.feedback = feedback;
        }

        static SettingsAutosave create(QuPathGUI qupath, String scriptName, String schemaId, String sourceScriptSha256,
                                       List<EditableConstant> constants, SettingsProfileState profileState, RunFeedback feedback) {
            File autosaveFile = null;
            try {
                autosaveFile = autosaveSettingsFile(projectBaseDirectory(qupath), scriptName);
            } catch (RuntimeException e) {
                if (feedback != null) {
                    feedback.warn("ASTRA settings autosave disabled: " + e.getMessage());
                }
            }
            return new SettingsAutosave(autosaveFile, scriptName, schemaId, sourceScriptSha256, constants, profileState, feedback);
        }

        void restoreIfAvailable() {
            restoreAutosaveSettings(file, scriptName, schemaId, sourceScriptSha256, constants, profileState,
                    feedback == null ? null : feedback::info,
                    feedback == null ? null : feedback::warn);
        }

        void markManualEditAndSave() {
            profileState.markManualEdit();
            scheduleSaveCurrent();
        }

        void scheduleSaveCurrent() {
            if (pendingSave != null) {
                pendingSave.stop();
            }
            pendingSave = new Timeline(new KeyFrame(Duration.millis(350.0), event -> saveCurrent()));
            pendingSave.setCycleCount(1);
            pendingSave.play();
        }

        void saveCurrent() {
            if (pendingSave != null) {
                pendingSave.stop();
                pendingSave = null;
            }
            if (file == null) {
                if (!warnedDisabled && feedback != null) {
                    warnedDisabled = true;
                    feedback.warn("ASTRA settings autosave skipped because no project-local settings path is available.");
                }
                return;
            }
            try {
                writeAutosaveSettings(file, scriptName, schemaId, sourceScriptSha256, constants);
            } catch (IOException | RuntimeException e) {
                if (isTransientAutosaveState(e)) {
                    return;
                }
                if (feedback != null) {
                    feedback.warn("Unable to write ASTRA autosave at " + file.getAbsolutePath() + ": " + e.getMessage());
                }
            }
        }

        private boolean isTransientAutosaveState(Exception e) {
            String message = e.getMessage();
            return message != null && message.contains(" must not be blank.");
        }

        void clear() {
            if (pendingSave != null) {
                pendingSave.stop();
                pendingSave = null;
            }
            if (file == null) {
                return;
            }
            try {
                clearAutosaveSettings(file);
            } catch (IOException e) {
                if (feedback != null) {
                    feedback.warn("Unable to clear ASTRA autosave at " + file.getAbsolutePath() + ": " + e.getMessage());
                }
            }
        }
    }

    static final class SettingsProfileState {

        private String source = "script defaults or manual GUI values";
        private String profileName = "";
        private String profilePath = "";
        private String profileSha256 = "";
        private boolean manualEdit;

        static SettingsProfileState scriptDefaults() {
            return new SettingsProfileState();
        }

        void loadedProfile(String name, String path, String sha256) {
            this.source = "loaded settings profile";
            this.profileName = name;
            this.profilePath = path;
            this.profileSha256 = sha256;
            this.manualEdit = false;
        }

        void loadedAutosave(String name, String path, String sha256) {
            this.source = "autosaved settings";
            this.profileName = name;
            this.profilePath = path;
            this.profileSha256 = sha256;
            this.manualEdit = false;
        }

        void resetToScriptDefaults() {
            this.source = "script defaults or manual GUI values";
            this.profileName = "";
            this.profilePath = "";
            this.profileSha256 = "";
            this.manualEdit = false;
        }

        void markManualEdit() {
            this.manualEdit = true;
        }

        boolean manualEditAfterLoad() {
            return manualEdit && !profilePath.isBlank();
        }

        String exportSource() {
            return profilePath.isBlank() && manualEdit ? "manual GUI values" : source;
        }

        String summary() {
            if (!profilePath.isBlank()) {
                return source + " (" + profileName + ", sha256=" + profileSha256.substring(0, Math.min(12, profileSha256.length()))
                        + (manualEdit ? ", edited after load" : "") + ")";
            }
            return manualEdit ? "manual GUI values" : source;
        }
    }

    static final class SettingsProfile {
        int schema_version;
        String pipeline_name;
        String script_name;
        String script_schema_id;
        String source_script_sha256;
        String astra_base_commit_or_version;
        String astra_extension_commit_or_version;
        String saved_timestamp;
        Map<String, String> constants;
        Map<String, String> model_references;
        String notes;

        SettingsProfile(int schemaVersion, String pipelineName, String scriptName, String scriptSchemaId, String sourceScriptSha256,
                        String baseCommitOrVersion, String extensionCommitOrVersion, String savedTimestamp,
                        Map<String, String> constants, Map<String, String> modelReferences, String notes) {
            this.schema_version = schemaVersion;
            this.pipeline_name = pipelineName;
            this.script_name = scriptName;
            this.script_schema_id = scriptSchemaId;
            this.source_script_sha256 = sourceScriptSha256;
            this.astra_base_commit_or_version = baseCommitOrVersion;
            this.astra_extension_commit_or_version = extensionCommitOrVersion;
            this.saved_timestamp = savedTimestamp;
            this.constants = constants;
            this.model_references = modelReferences;
            this.notes = notes;
        }
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

    record ColocalizationCheck(String label, String compartment, List<String> channels, List<String> excludedChannels) {
    }

    enum MarkerMapValueType {
        NUMERIC,
        TEXT
    }

    private static final class MultiSelectListEditor extends VBox {

        private final Button selector = new Button();
        private final Label summary = new Label();
        private final String titleText;
        private final String emptyMessage;
        private final Function<String, String> display;
        private final LinkedHashSet<String> selected = new LinkedHashSet<>();
        private final List<Runnable> listeners = new ArrayList<>();
        private List<String> choices = List.of();

        private MultiSelectListEditor(String titleText, List<String> choices, List<String> initialSelection,
                                      String emptyMessage, Function<String, String> display) {
            super(7.0);
            this.titleText = titleText == null ? "" : titleText;
            this.emptyMessage = emptyMessage == null || emptyMessage.isBlank()
                    ? "No choices available."
                    : emptyMessage;
            this.display = display == null ? Function.identity() : display;
            selector.setMaxWidth(Double.MAX_VALUE);
            selector.setFocusTraversable(false);
            styleButton(selector, ButtonRole.SECONDARY);
            selector.setOnAction(event -> openSelectionDialog());
            summary.setWrapText(true);
            summary.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 10.5px; -fx-text-fill: " + MUTED + ";");
            if (!this.titleText.isBlank()) {
                Label title = new Label(this.titleText);
                title.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-font-weight: 900; -fx-text-fill: " + INK + ";");
                getChildren().add(title);
            }
            getChildren().addAll(selector, summary);
            setChoices(choices, initialSelection);
        }

        private void setChoices(List<String> newChoices, List<String> newSelected) {
            choices = newChoices == null ? List.of() : List.copyOf(newChoices);
            selected.clear();
            selected.addAll(newSelected == null ? List.of() : newSelected);
            selected.retainAll(choices);
            updateSummary();
        }

        private List<String> selectedValues() {
            return choices.stream()
                    .filter(selected::contains)
                    .toList();
        }

        private void setSelected(List<String> values) {
            selected.clear();
            selected.addAll(values == null ? List.of() : values);
            selected.retainAll(choices);
            updateSummary();
        }

        private void addChangeListener(Runnable listener) {
            listeners.add(listener);
        }

        private void notifyListeners() {
            notifyListenersAfterModalClose(listeners);
        }

        private void updateSummary() {
            List<String> selectedValues = selectedValues();
            if (choices.isEmpty()) {
                selector.setText("No choices available");
                selector.setDisable(true);
                summary.setText(emptyMessage);
            } else if (selectedValues.isEmpty()) {
                selector.setText("Choose");
                selector.setDisable(false);
                summary.setText("None selected.");
            } else if (selectedValues.size() <= 2) {
                selector.setText(selectedValues.stream().map(display).reduce((a, b) -> a + ", " + b).orElse(""));
                selector.setDisable(false);
                summary.setText(selectedValues.size() + " of " + choices.size() + " selected.");
            } else {
                selector.setText(selectedValues.size() + " selected");
                selector.setDisable(false);
                summary.setText(selectedValues.stream()
                        .limit(4)
                        .map(display)
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("") + (selectedValues.size() > 4 ? ", +" + (selectedValues.size() - 4) + " more" : ""));
            }
        }

        private void openSelectionDialog() {
            if (choices.isEmpty()) {
                return;
            }
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle(titleText.isBlank() ? "ASTRA Selection" : "ASTRA " + titleText);
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.APPLY, ButtonType.CANCEL);
            dialog.getDialogPane().setStyle("-fx-background-color: " + PAPER + "; -fx-font-family: " + FONT_STACK + ";");
            installAstraStyles(dialog.getDialogPane());

            LinkedHashSet<String> working = new LinkedHashSet<>(selected);
            TextField filter = new TextField();
            filter.setPromptText("Search");
            addStyleClass(filter, "astra-input");
            ListView<String> list = new ListView<>();
            list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
            list.setPrefSize(420.0, 280.0);
            addStyleClass(list, "astra-list-view");
            list.setCellFactory(view -> new ListCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty ? null : display.apply(item));
                    setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-text-fill: " + INK + ";");
                }
            });
            Label dialogSummary = new Label();
            dialogSummary.setWrapText(true);
            dialogSummary.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");
            AtomicBoolean syncing = new AtomicBoolean(false);

            Runnable syncVisibleSelection = () -> {
                if (syncing.get()) {
                    return;
                }
                LinkedHashSet<String> visibleSelection = new LinkedHashSet<>(list.getSelectionModel().getSelectedItems());
                for (String choice : list.getItems()) {
                    if (visibleSelection.contains(choice)) {
                        working.add(choice);
                    } else {
                        working.remove(choice);
                    }
                }
                dialogSummary.setText(working.size() + " of " + choices.size() + " selected.");
            };
            Runnable refresh = () -> {
                String query = filter.getText() == null ? "" : filter.getText().trim().toLowerCase(Locale.ROOT);
                List<String> visible = choices.stream()
                        .filter(choice -> query.isBlank() || display.apply(choice).toLowerCase(Locale.ROOT).contains(query)
                                || choice.toLowerCase(Locale.ROOT).contains(query))
                        .toList();
                syncing.set(true);
                list.getItems().setAll(visible);
                list.getSelectionModel().clearSelection();
                for (int i = 0; i < visible.size(); i++) {
                    if (working.contains(visible.get(i))) {
                        list.getSelectionModel().select(i);
                    }
                }
                syncing.set(false);
                dialogSummary.setText(working.size() + " of " + choices.size() + " selected.");
            };
            list.getSelectionModel().getSelectedItems().addListener((ListChangeListener<String>) change -> syncVisibleSelection.run());
            filter.textProperty().addListener((obs, oldValue, newValue) -> {
                syncVisibleSelection.run();
                refresh.run();
            });

            Button selectAll = ProjectImageSelectionEditor.smallButton("Select All");
            selectAll.setOnAction(event -> {
                working.addAll(list.getItems());
                refresh.run();
            });
            Button clear = ProjectImageSelectionEditor.smallButton("Clear");
            clear.setOnAction(event -> {
                working.clear();
                refresh.run();
            });
            FlowPane actions = new FlowPane(7.0, 7.0, selectAll, clear);
            Label hint = new Label("Use shift or command/control to select multiple rows, then Apply.");
            hint.setWrapText(true);
            hint.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 10.5px; -fx-text-fill: " + MUTED + ";");
            VBox content = new VBox(9.0, filter, list, actions, dialogSummary, hint);
            content.setPadding(new Insets(12.0));
            dialog.getDialogPane().setContent(content);
            refresh.run();

            dialog.showAndWait().filter(ButtonType.APPLY::equals).ifPresent(button -> {
                syncVisibleSelection.run();
                selected.clear();
                selected.addAll(choices.stream().filter(working::contains).toList());
                updateSummary();
                notifyListeners();
            });
        }
    }

    private static final class ChannelCheckboxEditor extends VBox {

        private final MultiSelectListEditor editor;

        private ChannelCheckboxEditor(String titleText, List<String> channels, String rawValue) {
            this(titleText, channels, rawValue, "Open an image to choose channels without typing.");
        }

        private ChannelCheckboxEditor(String titleText, List<String> channels, String rawValue, String emptyMessage) {
            super(0.0);
            editor = new MultiSelectListEditor(titleText, channels, EditableConstant.csvValues(rawValue), emptyMessage, Function.identity());
            getChildren().add(editor);
        }

        private void setChoices(List<String> channels, List<String> selected) {
            editor.setChoices(channels, selected);
        }

        private List<String> selectedChannels() {
            return editor.selectedValues();
        }

        private void setSelected(List<String> values) {
            editor.setSelected(values);
        }

        private void addChangeListener(Runnable listener) {
            editor.addChangeListener(listener);
        }
    }

    private static final class ColocalizationChecksEditor extends VBox {

        private final List<String> imageChannels;
        private final VBox rows = new VBox(PARAMETER_ROW_GAP);
        private final List<CheckRow> checkRows = new ArrayList<>();
        private final List<Runnable> listeners = new ArrayList<>();

        private ColocalizationChecksEditor(List<String> imageChannels, String rawValue) {
            super(8.0);
            this.imageChannels = List.copyOf(imageChannels);
            List<ColocalizationCheck> parsed = parseColocalizationChecks(rawValue);
            parsed.forEach(this::addRow);
            Button add = new Button("Add check");
            add.setFocusTraversable(false);
            styleButton(add, ButtonRole.SMALL);
            add.setOnAction(event -> {
                addRow(new ColocalizationCheck("", "Nucleus", List.of(), List.of()));
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

        private void setRawValue(String rawValue) {
            rows.getChildren().clear();
            checkRows.clear();
            parseColocalizationChecks(rawValue).forEach(this::addRow);
            notifyListeners();
        }

        private void addChangeListener(Runnable listener) {
            listeners.add(listener);
        }

        private void notifyListeners() {
            listeners.forEach(Runnable::run);
        }

        private final class CheckRow {

            private final VBox node = new VBox(PARAMETER_ROW_GAP);
            private final TextField label = new TextField();
            private final ComboBox<String> compartment = new ComboBox<>();
            private final ChannelCheckboxEditor channelSelector;
            private final ChannelCheckboxEditor exclusionSelector;

            private CheckRow(ColocalizationCheck check) {
                node.setMinHeight(64.0);
                node.setStyle("-fx-background-color: white; -fx-border-color: #d7e2e6; -fx-border-radius: 5; -fx-background-radius: 5; -fx-padding: 8;");
                label.setPromptText("Label");
                label.setText(check.label());
                label.setPrefColumnCount(18);
                label.setMinWidth(180.0);
                addStyleClass(label, "astra-input");
                compartment.getItems().addAll("Nucleus", "Cytoplasm", "Cell");
                compartment.setValue(check.compartment().isBlank() ? "Nucleus" : check.compartment());
                compartment.setMinWidth(120.0);
                compartment.setPrefWidth(130.0);
                styleComboBox(compartment);
                channelSelector = new ChannelCheckboxEditor("", imageChannels, renderStringList(check.channels()));
                exclusionSelector = new ChannelCheckboxEditor("", check.channels(), renderStringList(check.excludedChannels()), "Choose check channels first.");
                channelSelector.addChangeListener(() -> {
                    refreshExclusionChoices();
                    notifyListeners();
                });
                exclusionSelector.addChangeListener(() -> notifyListeners());
                Button remove = new Button("Delete check");
                remove.setTooltip(new Tooltip("Delete check"));
                remove.setMinHeight(28.0);
                remove.setPrefHeight(28.0);
                remove.setMinWidth(92.0);
                remove.setFocusTraversable(false);
                styleButton(remove, ButtonRole.SMALL);
                remove.setOnAction(event -> {
                    checkRows.remove(this);
                    rows.getChildren().remove(node);
                    notifyListeners();
                });
                label.textProperty().addListener((obs, oldValue, newValue) -> notifyListeners());
                compartment.valueProperty().addListener((obs, oldValue, newValue) -> notifyListeners());
                HBox top = new HBox(PARAMETER_ROW_GAP);
                top.setAlignment(Pos.CENTER_LEFT);
                VBox nameField = nestedField("Check name", label);
                VBox compartmentField = nestedField("Compartment", compartment);
                HBox.setHgrow(nameField, Priority.ALWAYS);
                top.getChildren().addAll(nameField, compartmentField, remove);

                HBox selectors = new HBox(PARAMETER_ROW_GAP);
                selectors.setAlignment(Pos.CENTER_LEFT);
                VBox channelField = nestedField("Check channels", channelSelector);
                VBox exclusionField = nestedField("Threshold exclusions", exclusionSelector);
                HBox.setHgrow(channelField, Priority.ALWAYS);
                HBox.setHgrow(exclusionField, Priority.ALWAYS);
                selectors.getChildren().addAll(channelField, exclusionField);
                refreshExclusionChoices();
                node.getChildren().addAll(top, selectors);
            }

            private void refreshExclusionChoices() {
                List<String> selectedChannels = channelSelector.selectedChannels();
                List<String> retainedExclusions = exclusionSelector.selectedChannels().stream()
                        .filter(selectedChannels::contains)
                        .toList();
                exclusionSelector.setChoices(selectedChannels, retainedExclusions);
            }

            private ColocalizationCheck check() {
                return new ColocalizationCheck(
                        label.getText().trim(),
                        String.valueOf(compartment.getValue()).trim(),
                        channelSelector.selectedChannels(),
                        exclusionSelector.selectedChannels().stream()
                                .filter(channelSelector.selectedChannels()::contains)
                                .toList()
                );
            }
        }
    }

    static final class MarkerKeyMapEditor extends VBox {

        private final MarkerMapValueType valueType;
        private final String emptyMessage;
        private final Map<String, String> values = new LinkedHashMap<>();
        private final Map<String, TextField> fields = new LinkedHashMap<>();
        private final List<Runnable> listeners = new ArrayList<>();
        private List<String> markerKeys = List.of();

        MarkerKeyMapEditor(String rawValue, MarkerMapValueType valueType, String emptyMessage) {
            super(7.0);
            this.valueType = Objects.requireNonNull(valueType, "valueType");
            this.emptyMessage = emptyMessage == null || emptyMessage.isBlank()
                    ? "Marker-key rows appear after colocalization checks define marker keys."
                    : emptyMessage;
            values.putAll(parseMarkerKeyMapValues(rawValue, valueType));
            addStyleClass(this, "astra-nested-panel");
        }

        void refresh(List<String> newMarkerKeys) {
            storeFieldValues();
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            if (newMarkerKeys != null) {
                newMarkerKeys.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .forEach(unique::add);
            }
            markerKeys = List.copyOf(unique);
            values.keySet().retainAll(markerKeys);
            rebuildRows();
        }

        private void rebuildRows() {
            fields.clear();
            getChildren().clear();
            if (markerKeys.isEmpty()) {
                Label empty = new Label(emptyMessage);
                empty.setWrapText(true);
                empty.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");
                getChildren().add(empty);
                return;
            }
            for (String key : markerKeys) {
                TextField field = new TextField(values.getOrDefault(key, ""));
                field.setPromptText(valueType == MarkerMapValueType.TEXT ? "Describe source" : "Finite number");
                addStyleClass(field, "astra-input");
                field.textProperty().addListener((obs, oldValue, newValue) -> {
                    values.put(key, newValue == null ? "" : newValue.trim());
                    notifyListeners();
                });
                fields.put(key, field);
                VBox row = nestedField(key.replace("|", " | "), field);
                getChildren().add(row);
            }
        }

        private void storeFieldValues() {
            fields.forEach((key, field) -> values.put(key, field.getText() == null ? "" : field.getText().trim()));
        }

        String render() {
            storeFieldValues();
            Map<String, String> ordered = new LinkedHashMap<>();
            markerKeys.forEach(key -> ordered.put(key, values.getOrDefault(key, "")));
            return renderMarkerKeyMapValues(ordered, valueType);
        }

        void setRawValue(String rawValue) {
            values.clear();
            values.putAll(parseMarkerKeyMapValues(rawValue, valueType));
            values.keySet().retainAll(markerKeys);
            rebuildRows();
        }

        void addChangeListener(Runnable listener) {
            listeners.add(listener);
        }

        List<String> markerKeysForTest() {
            return markerKeys;
        }

        void setValueForTest(String key, String value) {
            if (fields.containsKey(key)) {
                fields.get(key).setText(value);
            } else {
                values.put(key, value);
            }
        }

        private void notifyListeners() {
            listeners.forEach(Runnable::run);
        }
    }

    private static final class ProjectImageSelectionEditor extends VBox {

        private final List<String> allNames;
        private final LinkedHashSet<String> selected = new LinkedHashSet<>();
        private final Label summary = new Label();
        private final List<Runnable> listeners = new ArrayList<>();

        private ProjectImageSelectionEditor(List<String> imageNames, String rawValue) {
            super(7.0);
            this.allNames = List.copyOf(imageNames);
            selected.addAll(EditableConstant.csvValues(rawValue));
            selected.retainAll(allNames);

            summary.setWrapText(true);
            summary.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 11.5px; -fx-text-fill: " + INK + ";");
            Button choose = smallButton("Choose Images...");
            choose.setOnAction(event -> openSelectionDialog());
            Button paste = smallButton("Paste Image Names");
            paste.setTooltip(new Tooltip("Paste one project image name per line, or comma-separated image names."));
            paste.setOnAction(event -> pasteNamesFromClipboard());
            FlowPane actions = new FlowPane(7.0, 7.0, choose, paste);
            Label hint = new Label("Project-scale runs use this explicit selected-image list. Use Choose Images to pick one, several, or all project images.");
            hint.setWrapText(true);
            hint.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 10.5px; -fx-text-fill: " + MUTED + ";");
            getChildren().addAll(summary, actions, hint);
            refreshSummary();
        }

        private static Button smallButton(String text) {
            Button button = new Button(text);
        button.setFocusTraversable(false);
            styleButton(button, ButtonRole.SMALL);
            return button;
        }

        private void openSelectionDialog() {
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("ASTRA Project Image Selection");
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            dialog.getDialogPane().setStyle("-fx-background-color: " + PAPER + "; -fx-font-family: " + FONT_STACK + ";");
            installAstraStyles(dialog.getDialogPane());

            LinkedHashSet<String> working = new LinkedHashSet<>(selected);
            TextField filter = new TextField();
            filter.setPromptText("Filter available project images");
            addStyleClass(filter, "astra-input");
            ListView<String> available = new ListView<>();
            ListView<String> chosen = new ListView<>();
            available.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
            chosen.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
            available.setPrefSize(300.0, 320.0);
            chosen.setPrefSize(300.0, 320.0);
            addStyleClass(available, "astra-list-view");
            addStyleClass(chosen, "astra-list-view");
            available.setCellFactory(list -> readableListCell());
            chosen.setCellFactory(list -> readableListCell());

            Runnable refresh = () -> {
                String query = filter.getText() == null ? "" : filter.getText().trim().toLowerCase(Locale.ROOT);
                available.getItems().setAll(allNames.stream()
                        .filter(name -> !working.contains(name))
                        .filter(name -> query.isBlank() || name.toLowerCase(Locale.ROOT).contains(query))
                        .toList());
                chosen.getItems().setAll(allNames.stream()
                        .filter(working::contains)
                        .toList());
            };

            Button addSelected = transferButton("Add >");
            addSelected.setOnAction(event -> {
                working.addAll(available.getSelectionModel().getSelectedItems());
                refresh.run();
            });
            Button addAll = transferButton("Add All >>");
            addAll.setOnAction(event -> {
                working.addAll(available.getItems());
                refresh.run();
            });
            Button removeSelected = transferButton("< Remove");
            removeSelected.setOnAction(event -> {
                working.removeAll(new ArrayList<>(chosen.getSelectionModel().getSelectedItems()));
                refresh.run();
            });
            Button removeAll = transferButton("<< Remove All");
            removeAll.setOnAction(event -> {
                working.clear();
                refresh.run();
            });

            VBox moveButtons = new VBox(8.0, addSelected, addAll, removeSelected, removeAll);
            moveButtons.setAlignment(Pos.CENTER);
            VBox availableBox = labeledSelector("Available Images", available);
            VBox chosenBox = labeledSelector("Selected Images", chosen);
            HBox chooser = new HBox(12.0, availableBox, moveButtons, chosenBox);
            chooser.setAlignment(Pos.CENTER);
            VBox content = new VBox(10.0, filter, chooser);
            content.setPadding(new Insets(12.0));
            dialog.getDialogPane().setContent(content);
            filter.textProperty().addListener((obs, oldValue, newValue) -> refresh.run());
            refresh.run();

            dialog.showAndWait().filter(ButtonType.OK::equals).ifPresent(button -> {
                selected.clear();
                selected.addAll(allNames.stream().filter(working::contains).toList());
                refreshSummary();
                notifyListeners();
            });
        }

        private static VBox labeledSelector(String labelText, ListView<String> list) {
            Label label = new Label(labelText);
            label.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 11px; -fx-font-weight: 800; -fx-text-fill: " + INK + ";");
            return new VBox(5.0, label, list);
        }

        private static Button transferButton(String text) {
            Button button = smallButton(text);
            button.setMaxWidth(Double.MAX_VALUE);
            return button;
        }

        private void pasteNamesFromClipboard() {
            String text = Clipboard.getSystemClipboard().getString();
            if (text != null && !text.isBlank()) {
                selected.clear();
                selected.addAll(parsePastedNames(text));
                selected.retainAll(allNames);
                refreshSummary();
                notifyListeners();
            }
        }

        private void refreshSummary() {
            List<String> names = selectedNames();
            String preview = names.stream().limit(3).reduce((a, b) -> a + ", " + b).orElse("none");
            if (names.size() > 3) {
                preview += ", +" + (names.size() - 3) + " more";
            }
            summary.setText(names.size() + " of " + allNames.size() + " project image(s) selected: " + preview);
        }

        private List<String> selectedNames() {
            return allNames.stream()
                    .filter(selected::contains)
                    .toList();
        }

        private String render() {
            return renderStringList(selectedNames());
        }

        private void setRawValue(String rawValue) {
            selected.clear();
            selected.addAll(EditableConstant.csvValues(rawValue));
            selected.retainAll(allNames);
            refreshSummary();
        }

        private void addChangeListener(Runnable listener) {
            listeners.add(listener);
        }

        private void notifyListeners() {
            notifyListenersAfterModalClose(listeners);
        }

        private static List<String> parsePastedNames(String text) {
            return Arrays.stream(text.split("[,\\R]"))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
    }

    private static final class StageModeEditor extends VBox {

        private final List<String> orderedModes;
        private final MultiSelectListEditor selector;
        private final List<Runnable> listeners = new ArrayList<>();

        private StageModeEditor(List<String> modes, String rawValue) {
            super(7.0);
            this.orderedModes = List.copyOf(modes);
            selector = new MultiSelectListEditor("", orderedModes, EditableConstant.csvValues(rawValue),
                    "No stages available.", StageModeEditor::displayMode);
            selector.addChangeListener(this::notifyListeners);
            Label hint = new Label("Choose stages in ASTRA's fixed order. Reset and export are separate script actions.");
            hint.setWrapText(true);
            hint.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 10.5px; -fx-text-fill: " + MUTED + ";");
            getChildren().addAll(selector, hint);
        }

        private List<String> selectedModes() {
            return orderedModes.stream()
                    .filter(selector.selectedValues()::contains)
                    .toList();
        }

        private String render() {
            return renderStringList(selectedModes());
        }

        private void setRawValue(String rawValue) {
            selector.setSelected(EditableConstant.csvValues(rawValue));
        }

        private void addChangeListener(Runnable listener) {
            listeners.add(listener);
        }

        private void notifyListeners() {
            listeners.forEach(Runnable::run);
        }

        private static String displayMode(String mode) {
            return GuiPresentation.displayLabel(mode);
        }
    }

    private static final class ListEditor extends VBox {

        private final TextField field;

        private ListEditor(String example) {
            super(5.0);
            field = new TextField(EditableConstant.simpleListToCsv(example));
            field.setPromptText("comma-separated values");
            field.setPrefColumnCount(48);
            addStyleClass(field, "astra-input");
            Label hint = new Label("Enter plain values separated by commas. ASTRA will write the required Groovy list syntax.");
            hint.setWrapText(true);
            hint.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 10.5px; -fx-text-fill: " + MUTED + ";");
            Button restore = new Button("Restore example");
            restore.setFocusTraversable(false);
            styleButton(restore, ButtonRole.SMALL);
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
            addStyleClass(area, "astra-code-area");
            Label hint = new Label("Structured advanced value. Keep keys, brackets, commas, and quotes intact. Use Restore example if the structure is damaged.");
            hint.setWrapText(true);
            hint.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 10.5px; -fx-text-fill: " + MUTED + ";");
            Button restore = new Button("Restore example");
            restore.setFocusTraversable(false);
            styleButton(restore, ButtonRole.SMALL);
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
        private final String group;
        private final boolean advanced;
        private final int uiOrder;
        private final List<String> options;
        private final String help;
        private final String details;
        private Node editor;
        private boolean defaultStyleInstalled;

        private EditableConstant(String type, String name, String value, String suffix, int start, int end, String group, boolean advanced, int uiOrder, List<String> options, String help, String details) {
            this.type = type;
            this.name = name;
            this.suffix = suffix == null ? "" : suffix;
            this.start = start;
            this.end = end;
            this.group = group == null || group.isBlank() ? groupFor(name) : group;
            this.advanced = advanced;
            this.uiOrder = uiOrder;
            this.options = options == null ? List.of() : List.copyOf(options);
            this.help = help == null || help.isBlank()
                    ? "ASTRA did not provide help metadata for this script constant."
                    : help;
            this.details = details == null || details.isBlank() ? this.help : details;
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

        String detailsText() {
            return details;
        }

        int uiOrder() {
            return uiOrder;
        }

        String defaultDisplayValue() {
            return defaultDisplayValue;
        }

        private Node createEditor() {
            if (editor == null) {
                editor = buildEditor();
                installDefaultStateStyle();
            }
            return editor;
        }

        private Node buildEditor() {
            if (!options.isEmpty()) {
                ComboBox<String> comboBox = new ComboBox<>();
                comboBox.getItems().addAll(options);
                comboBox.setValue(stripStringQuotes(type, displayValue));
                comboBox.setMaxWidth(Double.MAX_VALUE);
                styleComboBox(comboBox);
                installOptionDisplay(comboBox);
                return comboBox;
            }
            if ("boolean".equals(type)) {
                CheckBox checkBox = new CheckBox();
                checkBox.setSelected(Boolean.parseBoolean(displayValue));
                addStyleClass(checkBox, "astra-checkbox");
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
            addStyleClass(field, "astra-input");
            return field;
        }

        private String renderDeclaration() {
            return renderDeclaration(null);
        }

        private String renderDeclaration(String overrideValue) {
            if (overrideValue != null) {
                return "final " + type + " " + name + " = " + overrideValue + (suffix.isBlank() ? "" : " " + suffix) + "\n";
            }
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
            } else if (activeEditor instanceof StageModeEditor stageModeEditor) {
                value = stageModeEditor.render();
            } else if (activeEditor instanceof ChannelCheckboxEditor channelEditor) {
                value = renderStringList(channelEditor.selectedChannels());
            } else if (activeEditor instanceof ColocalizationChecksEditor checksEditor) {
                value = checksEditor.render();
            } else if (activeEditor instanceof ProjectImageSelectionEditor imageNamesEditor) {
                value = imageNamesEditor.render();
            } else if (activeEditor instanceof MarkerKeyMapEditor markerMapEditor) {
                value = markerMapEditor.render();
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
            updateDefaultStateStyle();
        }

        void setDisplayValue(String displayValue) {
            this.displayValue = displayValue;
        }

        private void syncEditorFromValue() {
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
            } else if (editor instanceof StageModeEditor stageModeEditor) {
                stageModeEditor.setRawValue(displayValue);
            } else if (editor instanceof ChannelCheckboxEditor channelEditor) {
                channelEditor.setSelected(EditableConstant.csvValues(displayValue));
            } else if (editor instanceof ColocalizationChecksEditor checksEditor) {
                checksEditor.setRawValue(displayValue);
            } else if (editor instanceof ProjectImageSelectionEditor imageNamesEditor) {
                imageNamesEditor.setRawValue(displayValue);
            } else if (editor instanceof MarkerKeyMapEditor markerMapEditor) {
                markerMapEditor.setRawValue(displayValue);
            } else if (editor instanceof CodeEditor codeEditor) {
                codeEditor.setText(displayValue);
            } else if (editor instanceof TextArea area) {
                area.setText(displayValue);
            } else if (editor instanceof TextField field) {
                field.setText(stripStringQuotes(type, displayValue));
            }
            updateDefaultStateStyle();
        }

        String currentDisplayValue() {
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
            } else if (activeEditor instanceof StageModeEditor stageModeEditor) {
                return stageModeEditor.render();
            } else if (activeEditor instanceof ChannelCheckboxEditor channelEditor) {
                return renderStringList(channelEditor.selectedChannels());
            } else if (activeEditor instanceof ColocalizationChecksEditor checksEditor) {
                return checksEditor.render();
            } else if (activeEditor instanceof ProjectImageSelectionEditor imageNamesEditor) {
                return imageNamesEditor.render();
            } else if (activeEditor instanceof MarkerKeyMapEditor markerMapEditor) {
                return markerMapEditor.render();
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

        private boolean isAtDefaultValue() {
            try {
                return Objects.equals(currentDisplayValue(), defaultDisplayValue);
            } catch (RuntimeException e) {
                return false;
            }
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
            } else if (editor instanceof StageModeEditor stageModeEditor) {
                stageModeEditor.setRawValue(displayValue);
            } else if (editor instanceof ChannelCheckboxEditor channelEditor) {
                channelEditor.setSelected(EditableConstant.csvValues(displayValue));
            } else if (editor instanceof ColocalizationChecksEditor checksEditor) {
                checksEditor.setRawValue(displayValue);
            } else if (editor instanceof ProjectImageSelectionEditor imageNamesEditor) {
                imageNamesEditor.setRawValue(displayValue);
            } else if (editor instanceof MarkerKeyMapEditor markerMapEditor) {
                markerMapEditor.setRawValue(displayValue);
            } else if (editor instanceof CodeEditor codeEditor) {
                codeEditor.setText(displayValue);
            } else if (editor instanceof TextArea area) {
                area.setText(displayValue);
            } else if (editor instanceof TextField field) {
                field.setText(stripStringQuotes(type, displayValue));
            }
            updateDefaultStateStyle();
        }

        private void setDisplayString(String channelName) {
            if (!"String".equals(type) || channelName == null || channelName.isBlank()) {
                return;
            }
            displayValue = "\"" + channelName.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }

        private void setDisplayStringAllowEmpty(String channelName) {
            if (!"String".equals(type)) {
                return;
            }
            if (channelName == null || channelName.isBlank()) {
                displayValue = "\"\"";
            } else {
                setDisplayString(channelName);
            }
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
            installDefaultStateStyle();
        }

        private void installDefaultStateStyle() {
            if (editor == null || defaultStyleInstalled) {
                return;
            }
            defaultStyleInstalled = true;
            editor.getProperties().putIfAbsent("astra.baseStyle", editor.getStyle() == null ? "" : editor.getStyle());
            addChangeListener(this::updateDefaultStateStyle);
            updateDefaultStateStyle();
        }

        private void updateDefaultStateStyle() {
            if (editor == null) {
                return;
            }
            Object base = editor.getProperties().getOrDefault("astra.baseStyle", "");
            String baseStyle = String.valueOf(base);
            String changedStyle = " -fx-effect: dropshadow(gaussian, rgba(212,167,44,0.42), 8, 0.2, 0, 0);";
            editor.setStyle(isAtDefaultValue() ? baseStyle : baseStyle + changedStyle);
        }

        private String optionValue() {
            Node activeEditor = editor;
            if (activeEditor instanceof ComboBox<?> comboBox) {
                return String.valueOf(comboBox.getValue());
            }
            if (activeEditor instanceof CheckBox checkBox) {
                return Boolean.toString(checkBox.isSelected());
            }
            if (activeEditor instanceof StageModeEditor stageModeEditor) {
                return stageModeEditor.render();
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
            } else if (activeEditor instanceof StageModeEditor stageModeEditor) {
                stageModeEditor.addChangeListener(listener);
            } else if (activeEditor instanceof ChannelCheckboxEditor channelEditor) {
                channelEditor.addChangeListener(listener);
            } else if (activeEditor instanceof ColocalizationChecksEditor checksEditor) {
                checksEditor.addChangeListener(listener);
            } else if (activeEditor instanceof ProjectImageSelectionEditor imageNamesEditor) {
                imageNamesEditor.addChangeListener(listener);
            } else if (activeEditor instanceof MarkerKeyMapEditor markerMapEditor) {
                markerMapEditor.addChangeListener(listener);
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

        private void installOptionDisplay(ComboBox<String> comboBox) {
            comboBox.setCellFactory(list -> new ListCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty ? null : GuiPresentation.displayOption(name, item));
                    setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-text-fill: " + INK + ";");
                }
            });
            comboBox.setButtonCell(new ListCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty ? null : GuiPresentation.displayOption(name, item));
                    setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-text-fill: " + INK + ";");
                }
            });
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
