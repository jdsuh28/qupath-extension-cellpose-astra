package qupath.ext.astra;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for ASTRA extension registration and archive hygiene.
 */
class AstraExtensionContractTest {

    private static final File ROOT = new File(".").getAbsoluteFile();

    /**
     * Verifies the installed extension service points to the ASTRA entrypoint
     * rather than the upstream BIOP entrypoint.
     *
     * @throws Exception if the service descriptor cannot be read.
     */
    @Test
    void serviceDescriptorRegistersAstraExtensionEntrypoint() throws Exception {
        File service = new File(ROOT,
                "src/main/resources/META-INF/services/qupath.lib.gui.extensions.QuPathExtension");

        assertTrue(service.isFile());
        assertEquals("qupath.ext.astra.AstraCellposeExtension", Files.readString(service.toPath()).trim());
    }

    /**
     * Verifies ASTRA menu registration exposes only ASTRA pipeline scripts.
     *
     * @throws Exception if the private script map cannot be inspected.
     */
    @Test
    @SuppressWarnings("unchecked")
    void scriptResourceMapRegistersOnlyAstraMenuScripts() throws Exception {
        Method method = AstraCellposeExtension.class.getDeclaredMethod("createScriptResources");
        method.setAccessible(true);

        Map<String, String> scripts = (Map<String, String>) method.invoke(null);

        assertEquals("astra/training/src/main/groovy/training.groovy", scripts.get("ASTRA Training"));
        assertEquals("astra/tuning/src/main/groovy/tuning.groovy", scripts.get("ASTRA Tuning"));
        assertEquals("astra/validation/src/main/groovy/validation.groovy", scripts.get("ASTRA Validation"));
        assertEquals("astra/analysis/src/main/groovy/vascular/vascular.groovy", scripts.get("Analysis>Vascular"));
        assertEquals("astra/analysis/src/main/groovy/colocalization/colocalization.groovy", scripts.get("Analysis>Colocalization"));
        assertEquals("astra/tools/src/main/groovy/generateRegions.groovy", scripts.get("ASTRA Generate Regions"));

        scripts.values().forEach(path -> assertTrue(path.startsWith("astra/"), path));
        scripts.values().forEach(path -> assertFalse(path.contains("Cellpose_"), path));
    }

    /**
     * Verifies the ASTRA runtime installer uses the pinned public fork and a
     * deterministic user-local runtime path.
     */
    @Test
    void runtimeInstallerUsesDeterministicAstraRuntime() {
        assertEquals("v4.0.8+astra.2", AstraRuntimeInstaller.DEFAULT_CELLPOSE_REF);
        assertEquals("git+https://github.com/jdsuh28/cellpose-astra.git@v4.0.8+astra.2",
                AstraRuntimeInstaller.cellposePackageSpec());
        assertEquals("cellpose-runtime", AstraRuntimeInstaller.runtimeDirectory().getName());
        assertTrue(AstraRuntimeInstaller.runtimePythonExecutable(new File("runtime")).getPath().contains("runtime"));
    }

    /**
     * Verifies menu actions open a wrapper script that launches the ASTRA
     * parameter dialog and then evaluates the configured pipeline script inside
     * the QuPath script editor.
     */
    @Test
    void pipelineLauncherCreatesScriptEditorWrapper() {
        String wrapper = AstraPipelineLauncher.createWrapperScript(
                "Colocalization",
                "astra/analysis/src/main/groovy/colocalization/colocalization.groovy"
        );

        assertTrue(wrapper.contains("AstraPipelineLauncher.promptForConfiguredScript"));
        assertTrue(wrapper.contains("evaluate(configuredScript)"));
        assertTrue(wrapper.contains("astra/analysis/src/main/groovy/colocalization/colocalization.groovy"));
    }

    /**
     * Verifies the extension archive is quarantined away from active source and
     * resource roots.
     */
    @Test
    void archiveRootDoesNotLeakIntoActiveSources() {
        assertFalse(new File(ROOT, "_legacy").exists());
        assertFalse(new File(ROOT, "_broken").exists());
        assertFalse(new File(ROOT, "_original").exists());
        assertTrue(new File(ROOT, "_archive/README.md").isFile());

        assertActiveTreeDoesNotReferenceArchive(new File(ROOT, "src/main/java"));
        assertActiveTreeDoesNotReferenceArchive(new File(ROOT, "src/main/resources"));
    }

    /**
     * Checks a source tree for active dependencies on archive paths.
     *
     * @param root active tree to inspect.
     */
    private static void assertActiveTreeDoesNotReferenceArchive(File root) {
        if (!root.exists()) {
            return;
        }
        File[] files = root.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                assertActiveTreeDoesNotReferenceArchive(file);
                continue;
            }
            try {
                String text = Files.readString(file.toPath());
                assertFalse(text.contains("_archive/"), file.getPath());
                assertFalse(text.contains("_legacy/"), file.getPath());
                assertFalse(text.contains("_broken/"), file.getPath());
                assertFalse(text.contains("_original/"), file.getPath());
            } catch (Exception e) {
                throw new AssertionError("Failed to inspect " + file.getPath(), e);
            }
        }
    }
}
