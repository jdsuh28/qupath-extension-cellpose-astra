package qupath.ext.astra;

import javafx.beans.property.StringProperty;
import org.controlsfx.control.PropertySheet;
import org.controlsfx.control.action.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.cellpose.CellposeExtension;
import qupath.ext.biop.cellpose.CellposeSetup;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.tools.MenuTools;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ASTRA extension entry point.
 *
 * <p>This extension exposes ASTRA scripts and registers the single runtime
 * Python executable used by all ASTRA Cellpose workflows.
 * Downstream script stacks resolve {@code MODEL_SOURCE}/{@code MODEL_NAME}
 * selection before handing one explicit execution model reference to the Java
 * runtime.</p>
 */
public class AstraCellposeExtension extends CellposeExtension {

    private static final Logger logger = LoggerFactory.getLogger(AstraCellposeExtension.class);

    private static final String ASTRA_PREFERENCE_CATEGORY = "ASTRA/Cellpose";
    private static final String ASTRA_RUNTIME_PYTHON_PATH_KEY = "astraRuntimePythonPath";
    private static final String ASTRA_RUNTIME_PYTHON_PATH_NAME = "ASTRA runtime Python executable";
    private static final String ASTRA_RUNTIME_PYTHON_PATH_DESCRIPTION =
            "Enter the full path to the ASTRA Cellpose Python executable.\n" +
            "This is the only runtime environment used by the ASTRA extension.\n" +
            "Do not include quotes (') or double quotes (\") around the path.";

    private static final Map<String, String> SCRIPT_RESOURCES = createScriptResources();

    private boolean installed;
    private StringProperty runtimePythonPath;

    @Override
    public String getName() {
        return "ASTRA Cellpose extension";
    }

    @Override
    public String getDescription() {
        return "ASTRA Cellpose extension with a single runtime path and downstream-resolved model selection";
    }

    @Override
    public GitHubRepo getRepository() {
        return GitHubRepo.create("ASTRA Cellpose 2D QuPath Extension", "jdsuh28", "qupath-extension-cellpose-astra");
    }

    @Override
    public void installExtension(QuPathGUI qupath) {
        Objects.requireNonNull(qupath, "qupath");
        if (installed) {
            return;
        }

        registerRuntimePreference(qupath);
        installSetupActions(qupath);
        installScripts(qupath);

        installed = true;
    }

    private static Map<String, String> createScriptResources() {
        LinkedHashMap<String, String> scripts = new LinkedHashMap<>();
        scripts.put("Training", "astra/training/src/main/groovy/training.groovy");
        scripts.put("Tuning", "astra/tuning/src/main/groovy/tuning.groovy");
        scripts.put("Validation", "astra/validation/src/main/groovy/validation.groovy");
        scripts.put("Analysis>Vascular", "astra/analysis/src/main/groovy/vascular/vascular.groovy");
        scripts.put("Analysis>Colocalization", "astra/analysis/src/main/groovy/colocalization/colocalization.groovy");
        scripts.put("Analysis>Generate Regions", "astra/tools/src/main/groovy/generateRegions.groovy");
        return Collections.unmodifiableMap(scripts);
    }

    private void installScripts(QuPathGUI qupath) {
        Map<String, String> scriptTexts = new LinkedHashMap<>();
        SCRIPT_RESOURCES.forEach((commandName, resourcePath) -> {
            try (InputStream stream = AstraCellposeExtension.class.getClassLoader().getResourceAsStream(resourcePath)) {
                if (stream == null) {
                    logger.error("Script not found: {}", resourcePath);
                    return;
                }

                scriptTexts.put(commandName, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                logger.error("Failed to register ASTRA script '{}' from {}.", commandName, resourcePath, e);
            }
        });

        scriptTexts.forEach((commandName, script) -> {
            String menuPath = menuPath(commandName);
            String scriptName = actionName(commandName);
            Action runAction = new Action(scriptName, event ->
                    AstraPipelineLauncher.configureAndRun(qupath, scriptName, script));
            MenuTools.addMenuItems(qupath.getMenu(menuPath, true), runAction);
        });

        scriptTexts.forEach((commandName, script) -> {
            String menuPath = menuPath(commandName);
            String scriptName = actionName(commandName);
            Action openAction = new Action(scriptName, event -> openScript(qupath, scriptName, script));
            MenuTools.addMenuItems(qupath.getMenu(scriptArchiveMenuPath(menuPath), true), openAction);
        });
    }

    private static String menuPath(String commandName) {
        int submenuIndex = commandName.lastIndexOf('>');
        return submenuIndex < 0
                ? "Extensions>ASTRA"
                : "Extensions>ASTRA>" + commandName.substring(0, submenuIndex);
    }

    private static String actionName(String commandName) {
        int submenuIndex = commandName.lastIndexOf('>');
        return submenuIndex < 0 ? commandName : commandName.substring(submenuIndex + 1);
    }

    private void openScript(QuPathGUI qupath, String scriptName, String scriptText) {
        var editor = qupath.getScriptEditor();
        if (editor == null) {
            logger.error("No script editor is available.");
            return;
        }
        editor.showScript(scriptName, scriptText);
    }

    private static String scriptArchiveMenuPath(String runtimeMenuPath) {
        String suffix = runtimeMenuPath.substring("Extensions>ASTRA".length());
        return suffix.isBlank()
                ? "Extensions>ASTRA>Scripts"
                : "Extensions>ASTRA>Scripts" + suffix;
    }

    private void registerRuntimePreference(QuPathGUI qupath) {
        CellposeSetup cellposeSetup = CellposeSetup.getInstance();
        runtimePythonPath = PathPrefs.createPersistentPreference(ASTRA_RUNTIME_PYTHON_PATH_KEY, "");
        String normalizedInitialPath = normalizePythonPath(runtimePythonPath.get());
        if (!normalizedInitialPath.equals(runtimePythonPath.get())) {
            runtimePythonPath.set(normalizedInitialPath);
        }

        cellposeSetup.setCellposePythonPath(normalizedInitialPath);
        runtimePythonPath.addListener((observable, previousValue, newValue) ->
                cellposeSetup.setCellposePythonPath(normalizePythonPath(newValue)));

        var propertySheet = qupath.getPreferencePane().getPropertySheet();
        boolean alreadyRegistered = propertySheet.getItems().stream()
                .anyMatch(item -> ASTRA_RUNTIME_PYTHON_PATH_NAME.equals(item.getName())
                        && ASTRA_PREFERENCE_CATEGORY.equals(item.getCategory()));
        if (alreadyRegistered) {
            return;
        }

        PropertySheet.Item runtimePythonPathItem = new PropertyItemBuilder<>(runtimePythonPath, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.GENERAL)
                .name(ASTRA_RUNTIME_PYTHON_PATH_NAME)
                .category(ASTRA_PREFERENCE_CATEGORY)
                .description(ASTRA_RUNTIME_PYTHON_PATH_DESCRIPTION)
                .build();

        propertySheet.getItems().add(runtimePythonPathItem);
    }

    private void installSetupActions(QuPathGUI qupath) {
        Action installRuntime = new Action("Install/Repair Python Runtime", event ->
                AstraRuntimeInstaller.installOrRepairAsync(runtimePythonPath));
        MenuTools.addMenuItems(qupath.getMenu("Extensions>ASTRA", true), installRuntime);
    }

    private static String normalizePythonPath(String rawPath) {
        return rawPath == null ? "" : rawPath.trim();
    }
}
