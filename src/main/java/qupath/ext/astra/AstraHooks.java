/*-
 * ASTRA hooks for qupath-extension-cellpose.
 *
 * Goal: keep Cellpose2D.java changes minimal by delegating ASTRA-specific behavior here.
 *
 * Defaults are ASTRA-forward, but can be overridden via a classpath resource:
 *   /astra/astra.properties
 *
 * Supported keys (all optional):
 *   enabled=true|false
 *   skipModelMove=true|false
 *   skipAutomaticQc=true|false
 *   trainingDirMode=flat|nested
 *   qcScriptRelativePath=astra/qc/run-cellpose-qc.py
 */

package qupath.ext.astra;

import ij.measure.ResultsTable;
import qupath.ext.biop.cellpose.Cellpose2D;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

public final class AstraHooks {

    private static final String PROPS_PATH = "/astra/astra.properties";

    private static final Properties PROPS = loadProps();

    private AstraHooks() {}

    private static Properties loadProps() {
        var props = new Properties();
        try (InputStream in = AstraHooks.class.getResourceAsStream(PROPS_PATH)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
            // Intentionally ignore; defaults apply
        }
        return props;
    }

    private static boolean propBool(String key, boolean def) {
        String v = PROPS.getProperty(key);
        if (v == null) return def;
        v = v.trim().toLowerCase(Locale.ROOT);
        if (v.equals("true") || v.equals("1") || v.equals("yes")) return true;
        if (v.equals("false") || v.equals("0") || v.equals("no")) return false;
        return def;
    }

    private static String propStr(String key, String def) {
        String v = PROPS.getProperty(key);
        return v == null ? def : v.trim();
    }

    /**
     * Master enable switch.
     */
    public static boolean enabled() {
        // System property can hard-disable regardless of bundled defaults.
        if (Boolean.getBoolean("astra.hooks.disabled")) return false;
        return propBool("enabled", true);
    }

    /**
     * Training directory resolution.
     * - BIOP default: groundTruthDirectory/train
     * - ASTRA default: flat groundTruthDirectory
     */
    public static File resolveTrainingDirectory(File groundTruthDirectory) {
        Objects.requireNonNull(groundTruthDirectory, "groundTruthDirectory");
        if (enabled() && propStr("trainingDirMode", "flat").equalsIgnoreCase("flat")) {
            return groundTruthDirectory;
        }
        return new File(groundTruthDirectory, "train");
    }

    /**
     * Validation/QC directory resolution.
     * - BIOP default: groundTruthDirectory/test
     * - ASTRA default: qcDirectory if provided
     */
    public static File resolveValidationDirectory(File groundTruthDirectory, File qcDirectory) {
        Objects.requireNonNull(groundTruthDirectory, "groundTruthDirectory");
        if (enabled() && qcDirectory != null) {
            return qcDirectory;
        }
        return new File(groundTruthDirectory, "test");
    }

    /**
     * ASTRA default: do NOT move/rename the trained model automatically.
     */
    public static boolean skipModelMove(Cellpose2D cp) {
        if (!enabled()) return false;
        return propBool("skipModelMove", true);
    }

    /**
     * ASTRA default: do NOT run QC implicitly inside train().
     */
    public static boolean skipAutomaticQc(Cellpose2D cp) {
        if (!enabled()) return false;
        return propBool("skipAutomaticQc", true);
    }

    /**
     * Return value for train() when skipping model move/QC.
     * Default: trainingDir/models (Cellpose default output directory).
     */
    public static File trainingArtifactReturnValue(Cellpose2D cp, File trainingDirectory) {
        return new File(trainingDirectory, "models");
    }

    /**
     * Legacy training-results saving requires a concrete modelFile name.
     * ASTRA default: disabled.
     */
    public static boolean allowLegacyTrainingResultsSave(Cellpose2D cp) {
        if (!enabled()) return true;
        return propBool("allowLegacyTrainingResultsSave", false);
    }

    /**
     * Hook after training results parsed (ASTRA can persist elsewhere).
     * Default: no-op.
     */
    public static void onTrainingResultsParsed(Cellpose2D cp, ResultsTable results) {
        // no-op by default
    }

    /**
     * Resolve QC folder destination.
     * BIOP default: modelDirectory/QC
     */
    public static File resolveQcFolder(File modelDirectory) {
        return new File(modelDirectory, "QC");
    }

    /**
     * Hard precondition enforcement for ASTRA QC runs.
     */
    public static void requireQcDirectory(Cellpose2D cp) throws IOException {
        if (!enabled()) return;
        try {
            var field = Cellpose2D.class.getDeclaredField("qcDirectory");
            field.setAccessible(true);
            var qcDir = (File) field.get(cp);
            if (qcDir == null) {
                throw new IOException("ASTRA QC requires qcDirectory to be set by the builder.");
            }
        } catch (NoSuchFieldException e) {
            throw new IOException("ASTRA QC requires qcDirectory, but Cellpose2D has no qcDirectory field.", e);
        } catch (IllegalAccessException e) {
            throw new IOException("ASTRA QC requires qcDirectory, but it could not be accessed.", e);
        }
    }

    /**
     * Locate the QC python script.
     * Preference order:
     *  1) <extensionDir>/<qcScriptRelativePath> (ASTRA default: astra/qc/run-cellpose-qc.py)
     *  2) <extensionDir>/run-cellpose-qc.py (BIOP default)
     */
    public static File resolveQcPythonFile(File extensionDir) {
        Objects.requireNonNull(extensionDir, "extensionDir");
        String rel = propStr("qcScriptRelativePath", "astra/qc/run-cellpose-qc.py");
        File candidate = new File(extensionDir, rel);
        if (enabled() && candidate.exists()) return candidate;
        return new File(extensionDir, "run-cellpose-qc.py");
    }


    /**
     * Resolve the model name argument passed to the QC python script.
     * BIOP default: modelFile.getName()
     * ASTRA default: if modelFile is null, fall back to outputModelName when available,
     * otherwise "UNKNOWN_MODEL".
     */
    public static String resolveModelNameForQc(Cellpose2D cp) {
        if (cp == null) return "UNKNOWN_MODEL";
        try {
            var field = Cellpose2D.class.getDeclaredField("modelFile");
            field.setAccessible(true);
            var f = (File) field.get(cp);
            if (f != null) return f.getName();
        } catch (Exception ignored) {
            // ignore reflection failures
        }
        // Best-effort fallback
        try {
            var outField = Cellpose2D.class.getDeclaredField("outputModelName");
            outField.setAccessible(true);
            var outName = (String) outField.get(cp);
            if (outName != null && !outName.isBlank()) return outName;
        } catch (Exception ignored) {
            // ignore reflection failures
        }
        return "UNKNOWN_MODEL";
    }

    /**
     * Builder-facing hook: resolve qcDirectory.
     *
     * Baseline behavior: if qcDirectory is null, fall back to <projectDir>/test.
     * ASTRA behavior: if qcDirectory is provided, use it; otherwise preserve baseline.
     */
    public static File resolveQcDirectory(File quPathProjectDir, File qcDirectory) {
        Objects.requireNonNull(quPathProjectDir, "quPathProjectDir");
        if (enabled() && qcDirectory != null) return qcDirectory;
        return new File(quPathProjectDir, "test");
    }

    /**
     * Builder-facing hook: resolve results directory.
     *
     * Baseline behavior: preserve original results directory under the project.
     * ASTRA behavior: keep the same unless overridden in properties.
     */
    public static File resolveResultsDirectory(File quPathProjectDir) {
        Objects.requireNonNull(quPathProjectDir, "quPathProjectDir");
        String rel = propStr("resultsRelativePath", "results");
        return new File(quPathProjectDir, rel);
    }

    /**
     * Resolve the QC results file emitted by the python script.
     * BIOP default: <validationDir>/QC-Results/Quality_Control for <modelName>.csv
     */
    public static File resolveQcResultsFile(File validationDirectory, File modelFile) {
        Objects.requireNonNull(validationDirectory, "validationDirectory");
        String modelName = modelFile == null ? "UNKNOWN_MODEL" : modelFile.getName();
        return new File(validationDirectory,
                "QC-Results" + File.separator + "Quality_Control for " + modelName + ".csv");
    }

    /**
     * Resolve final destination for the QC results CSV (after move).
     * Default: qcFolder/<qcResultsName>
     */
    public static File resolveFinalQcResultFile(File qcFolder, File qcResultsFile) {
        Objects.requireNonNull(qcFolder, "qcFolder");
        Objects.requireNonNull(qcResultsFile, "qcResultsFile");
        return new File(qcFolder, qcResultsFile.getName());
    }
}
