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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ASTRA extension entry point.
 *
 * <p>This extension exposes ASTRA scripts and registers the single runtime
 * Python executable used by all ASTRA Cellpose workflows.</p>
 */
public class AstraCellposeExtension extends CellposeExtension {

    private static final Logger logger = LoggerFactory.getLogger(AstraCellposeExtension.class);

    private static final String ASTRA_PREFERENCE_CATEGORY = "ASTRA/Cellpose";
    private static final String ASTRA_RUNTIME_PYTHON_PATH_KEY = "astraRuntimePythonPath";
    private static final String ASTRA_RUNTIME_PYTHON_PATH_NAME = "ASTRA Cellpose 'python.exe' location";
    private static final String ASTRA_RUNTIME_PYTHON_PATH_DESCRIPTION =
            "Enter the full path to the ASTRA Cellpose Python executable, including 'python.exe'.\n" +
            "This is the only runtime environment used by the ASTRA extension.\n" +
            "Do not include quotes (') or double quotes (\") around the path.";

    private static final Map<String, String> ASTRA_SCRIPTS = createAstraScripts();

    private boolean installed;

    @Override
    public String getName() {
        return "ASTRA Cellpose extension";
    }

    @Override
    public String getDescription() {
        return "ASTRA fork of the BIOP Cellpose extension";
    }

    @Override
    public GitHubRepo getRepository() {
        return GitHubRepo.create("ASTRA Cellpose 2D QuPath Extension", "jdsuh28", "qupath-extension-cellpose-astra");
    }

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (installed) {
            return;
        }

        installAstraScripts(qupath);
        registerAstraRuntimePreference();

        installed = true;
    }

    private static Map<String, String> createAstraScripts() {
        LinkedHashMap<String, String> scripts = new LinkedHashMap<>();
        scripts.put("ASTRA Training", "astra/training/training.groovy");
        scripts.put("ASTRA Validation", "astra/validation/validation.groovy");
        scripts.put("ASTRA Tuning", "astra/tuning/tuning.groovy");
        scripts.put("ASTRA Analysis", "astra/analysis/analysis.groovy");
        scripts.put("ASTRA Training Image", "astra/tools/training_image.groovy");
        return scripts;
    }

    private void installAstraScripts(QuPathGUI qupath) {
        ASTRA_SCRIPTS.forEach((commandName, resourcePath) -> {
            try (InputStream stream = AstraCellposeExtension.class.getClassLoader().getResourceAsStream(resourcePath)) {
                if (stream == null) {
                    logger.error("Script not found: {}", resourcePath);
                    return;
                }

                String script = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                Action action = new Action(commandName, event -> openScript(qupath, commandName, script));
                MenuTools.addMenuItems(qupath.getMenu("Extensions>ASTRA", true), action);
            } catch (Exception e) {
                logger.error("Failed to register ASTRA script '{}' from {}.", commandName, resourcePath, e);
            }
        });
    }

    private void openScript(QuPathGUI qupath, String scriptName, String scriptText) {
        var editor = qupath.getScriptEditor();
        if (editor == null) {
            logger.error("No script editor is available.");
            return;
        }
        editor.showScript(scriptName, scriptText);
    }

    private void registerAstraRuntimePreference() {
        CellposeSetup cellposeSetup = CellposeSetup.getInstance();
        StringProperty runtimePythonPath = PathPrefs.createPersistentPreference(ASTRA_RUNTIME_PYTHON_PATH_KEY, "");

        cellposeSetup.setCellposePythonPath(runtimePythonPath.get());
        runtimePythonPath.addListener((observable, previousValue, newValue) -> cellposeSetup.setCellposePythonPath(newValue));

        PropertySheet.Item runtimePythonPathItem = new PropertyItemBuilder<>(runtimePythonPath, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.GENERAL)
                .name(ASTRA_RUNTIME_PYTHON_PATH_NAME)
                .category(ASTRA_PREFERENCE_CATEGORY)
                .description(ASTRA_RUNTIME_PYTHON_PATH_DESCRIPTION)
                .build();

        QuPathGUI.getInstance()
                .getPreferencePane()
                .getPropertySheet()
                .getItems()
                .add(runtimePythonPathItem);
    }
}
