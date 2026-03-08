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

public class AstraCellposeExtension extends CellposeExtension {

    private static final Logger logger = LoggerFactory.getLogger(AstraCellposeExtension.class);
    private boolean isInstalled = false;

    private static final LinkedHashMap<String, String> ASTRA_SCRIPTS = new LinkedHashMap<>() {{
        put("ASTRA Training", "astra/training/training.groovy");
        put("ASTRA QC", "astra/qc/qc.groovy");
        put("ASTRA Tuning", "astra/tuning/tuning.groovy");
        put("ASTRA Analysis", "astra/analysis/analysis.groovy");
    }};

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
        if (isInstalled)
            return;

        ASTRA_SCRIPTS.entrySet().forEach(entry -> {
            String resource = entry.getValue();
            String command = entry.getKey();

            try (InputStream stream = AstraCellposeExtension.class
                    .getClassLoader()
                    .getResourceAsStream(resource)) {

                if (stream == null) {
                    logger.error("Script not found: {}", resource);
                    return;
                }

                String script = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

                MenuTools.addMenuItems(
                        qupath.getMenu("Extensions>ASTRA", true),
                        new Action(command, e -> {
                            var editor = qupath.getScriptEditor();
                            if (editor == null) {
                                logger.error("No script editor is available!");
                                return;
                            }
                            editor.showScript(command, script);
                        })
                );

            } catch (Exception e) {
                logger.error(e.getLocalizedMessage(), e);
            }
        });

        CellposeSetup options = CellposeSetup.getInstance();

        StringProperty cellposePath = PathPrefs.createPersistentPreference("cellposePythonPath", "");
        StringProperty cellposeSAMPath = PathPrefs.createPersistentPreference("cellposeSAMPythonPath", "");
        StringProperty omniposePath = PathPrefs.createPersistentPreference("omniposePythonPath", "");
        StringProperty condaPath = PathPrefs.createPersistentPreference("condaPath", "");

        options.setCellposePythonPath(cellposePath.get());
        options.setCellposeSAMPythonPath(cellposeSAMPath.get());
        options.setOmniposePythonPath(omniposePath.get());
        options.setCondaPath(condaPath.get());

        cellposePath.addListener((v, o, n) -> options.setCellposePythonPath(n));
        cellposeSAMPath.addListener((v, o, n) -> options.setCellposeSAMPythonPath(n));
        omniposePath.addListener((v, o, n) -> options.setOmniposePythonPath(n));
        condaPath.addListener((v, o, n) -> options.setCondaPath(n));

        PropertySheet.Item cellposePathItem = new PropertyItemBuilder<>(cellposePath, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.GENERAL)
                .name("Cellpose 'python.exe' location")
                .category("Cellpose/Omnipose")
                .description("Enter the full path to your cellpose environment, including 'python.exe'\nDo not include quotes (') or double quotes (\") around the path.")
                .build();

        PropertySheet.Item cellposeSAMPathItem = new PropertyItemBuilder<>(cellposeSAMPath, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.GENERAL)
                .name("Cellpose SAM 'python.exe' location")
                .category("Cellpose/Omnipose")
                .description("Enter the full path to your cellposeSAM environment, including 'python.exe'\nDo not include quotes (') or double quotes (\") around the path.")
                .build();

        PropertySheet.Item omniposePathItem = new PropertyItemBuilder<>(omniposePath, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.GENERAL)
                .name("Omnipose 'python.exe' location")
                .category("Cellpose/Omnipose")
                .description("Enter the full path to your omnipose environment, including 'python.exe'\nDo not include quotes (') or double quotes (\") around the path.")
                .build();

        PropertySheet.Item condaPathItem = new PropertyItemBuilder<>(condaPath, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.GENERAL)
                .name("'Conda/Mamba' script location (optional)")
                .category("Cellpose/Omnipose")
                .description("The full path to your conda/mamba command, in case you want the extension to use the 'conda activate' command.\ne.g. 'C:\\ProgramData\\Miniconda3\\condabin\\mamba.bat'\nDo not include quotes (') or double quotes (\") around the path.")
                .build();

        QuPathGUI.getInstance().getPreferencePane().getPropertySheet().getItems()
                .addAll(cellposePathItem, cellposeSAMPathItem, omniposePathItem, condaPathItem);

        isInstalled = true;
    }
}
