/*-
 * ASTRA hook layer for qupath-extension-cellpose.
 *
 * Purpose:
 * - Keep ASTRA-specific behavior out of the upstream BIOP classes as much as possible.
 * - Preserve future mergeability by limiting edits in upstream-owned files to small hook calls.
 * - Centralize ASTRA policy decisions in one place.
 *
 * This class currently provides hooks used by:
 * - qupath.ext.biop.cellpose.Cellpose2D
 * - qupath.ext.biop.cellpose.CellposeBuilder
 *
 * Configuration:
 * - Optional classpath resource: /astra/astra.properties
 * - If the properties file is absent, the hardcoded defaults in this class are used.
 *
 * Notes:
 * - This class is ASTRA-forward by default.
 * - Legacy BIOP behavior is preserved where appropriate through explicit fallbacks.
 * - Reflection is used only where ASTRA must read upstream Cellpose2D fields without widening
 *   the upstream API surface.
 *
 * Supported properties (all optional):
 *   enabled=true|false
 *   skipModelMove=true|false
 *   skipAutomaticQc=true|false
 *   allowLegacyTrainingResultsSave=true|false
 *   trainingDirMode=flat|nested
 *   qcScriptRelativePath=astra/qc/run-cellpose-qc.py
 *   resultsRelativePath=results
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

    /**
     * Classpath location for the optional ASTRA properties resource.
     * Place the file at:
     *   src/main/resources/astra/astra.properties
     */
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
            // Intentionally ignore load failures; hardcoded defaults apply.
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
     * Master enable switch for ASTRA hooks.
     *
     * Resolution order:
     * 1) JVM system property: -Dastra.hooks.disabled=true  -> disables all hooks
     * 2) astra.properties: enabled=...
     * 3) hardcoded default: true
     */
    public static boolean enabled() {
        if (Boolean.getBoolean("astra.hooks.disabled")) return false;
        return propBool("enabled", true);
    }

    /**
     * Resolve the directory used for Cellpose training data.
     *
     * BIOP baseline:
     *   <groundTruthDirectory>/train
     *
     * ASTRA default:
     *   <groundTruthDirectory>
     *
     * Controlled by:
     *   trainingDirMode=flat|nested
     */
    public static File resolveTrainingDirectory(File groundTruthDirectory) {
        Objects.requireNonNull(groundTruthDirectory, "groundTruthDirectory");
        if (enabled() && propStr("trainingDirMode", "flat").equalsIgnoreCase("flat")) {
            return groundTruthDirectory;
        }
        return new File(groundTruthDirectory, "train");
    }

    /**
     * Resolve the directory used for validation/QC inputs.
     *
     * BIOP baseline:
     *   <groundTruthDirectory>/test
     *
     * ASTRA default:
     *   qcDirectory if provided, otherwise preserve baseline
     */
    public static File resolveValidationDirectory(File groundTruthDirectory, File qcDirectory) {
        Objects.requireNonNull(groundTruthDirectory, "groundTruthDirectory");
        if (enabled() && qcDirectory != null) {
            return qcDirectory;
        }
        return new File(groundTruthDirectory, "test");
    }

    /**
     * ASTRA default:
     *   do not automatically promote/move/rename a trained model after training.
     *
     * Rationale:
     *   ASTRA wants the umbrella pipeline to inspect training outputs and select the
     *   desired checkpoint explicitly rather than letting the extension choose one.
     */
    public static boolean skipModelMove(Cellpose2D cp) {
        if (!enabled()) return false;
        return propBool("skipModelMove", true);
    }

    /**
     * ASTRA default:
     *   do not run QC implicitly inside train().
     *
     * Rationale:
     *   ASTRA treats QC as a separate step with an explicit model selection.
     */
    public static boolean skipAutomaticQc(Cellpose2D cp) {
        if (!enabled()) return false;
        return propBool("skipAutomaticQc", true);
    }

    /**
     * Return value used by train() when ASTRA skips model promotion and/or automatic QC.
     *
     * Default:
     *   <trainingDirectory>/models
     *
     * This mirrors Cellpose's native training output location and keeps the return value
     * concrete even when ASTRA disables downstream legacy actions.
     */
    public static File trainingArtifactReturnValue(Cellpose2D cp, File trainingDirectory) {
        return new File(trainingDirectory, "models");
    }

    /**
     * Control whether legacy training-results saving logic is allowed to run.
     *
     * ASTRA default:
     *   false
     *
     * Rationale:
     *   legacy saving expects a concrete promoted modelFile, which ASTRA intentionally skips.
     */
    public static boolean allowLegacyTrainingResultsSave(Cellpose2D cp) {
        if (!enabled()) return true;
        return propBool("allowLegacyTrainingResultsSave", false);
    }

    /**
     * Optional post-parse hook for training results.
     *
     * Current behavior:
     *   no-op
     *
     * Reserved for future ASTRA-side handling if training results need to be captured,
     * redirected, or persisted differently.
     */
    public static void onTrainingResultsParsed(Cellpose2D cp, ResultsTable results) {
        // no-op by default
    }

    /**
     * Resolve the final QC destination folder.
     *
     * BIOP baseline:
     *   <modelDirectory>/QC
     */
    public static File resolveQcFolder(File modelDirectory) {
        return new File(modelDirectory, "QC");
    }

    /**
     * Fail-fast guard for ASTRA QC runs.
     *
     * Requirement:
     *   qcDirectory must be present on the Cellpose2D instance when ASTRA QC is used.
     *
     * Reflection is used so ASTRA can read the field without expanding the upstream API.
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
     * Locate the QC Python script inside the installed extension resources.
     *
     * Preference order:
     * 1) <extensionDir>/<qcScriptRelativePath>
     * 2) <extensionDir>/run-cellpose-qc.py
     *
     * ASTRA default:
     *   astra/qc/run-cellpose-qc.py
     */
    public static File resolveQcPythonFile(File extensionDir) {
        Objects.requireNonNull(extensionDir, "extensionDir");
        String rel = propStr("qcScriptRelativePath", "astra/qc/run-cellpose-qc.py");
        File candidate = new File(extensionDir, rel);
        if (enabled() && candidate.exists()) return candidate;
        return new File(extensionDir, "run-cellpose-qc.py");
    }

    /**
     * Resolve the model path argument used when running Cellpose on validation images for QC.
     *
     * Deterministic precedence:
     * 1) Explicit model string provided to the builder (field: "model"), if non-blank
     * 2) modelFile absolute path, if present (legacy one-shot train()->QC)
     * 3) fail-fast
     *
     * ASTRA intent:
     *   do not guess a model; use the explicit model when present.
     */
    public static String resolveQcModelPathForValidation(Cellpose2D cp) {
        Objects.requireNonNull(cp, "cp");

        String explicit = null;
        try {
            var modelField = Cellpose2D.class.getDeclaredField("model");
            modelField.setAccessible(true);
            explicit = (String) modelField.get(cp);
        } catch (Exception ignored) {
            // Intentionally ignored; explicit model simply remains unavailable.
        }

        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim();
        }

        try {
            var field = Cellpose2D.class.getDeclaredField("modelFile");
            field.setAccessible(true);
            var f = (File) field.get(cp);
            if (f != null) return f.getAbsolutePath();
        } catch (Exception ignored) {
            // Intentionally ignored; fallback simply remains unavailable.
        }

        throw new IllegalStateException(
                "QC requires an explicit model path/name when no promoted modelFile is present."
        );
    }

    /**
     * Resolve the model-name token passed to the QC Python script.
     *
     * Deterministic precedence:
     * 1) explicit builder model string:
     *      - if path-like, use basename
     *      - otherwise use the token as-is
     * 2) modelFile.getName(), if present
     * 3) outputModelName, if present
     * 4) fail-fast
     *
     * This keeps ASTRA QC naming deterministic while still preserving a legacy fallback path.
     */
    public static String resolveQcModelNameForQc(Cellpose2D cp) {
        Objects.requireNonNull(cp, "cp");

        String explicit = null;
        try {
            var modelField = Cellpose2D.class.getDeclaredField("model");
            modelField.setAccessible(true);
            explicit = (String) modelField.get(cp);
        } catch (Exception ignored) {
            // Intentionally ignored; explicit model simply remains unavailable.
        }

        if (explicit != null && !explicit.isBlank()) {
            String s = explicit.trim();
            int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
            if (slash >= 0 && slash < s.length() - 1) return s.substring(slash + 1);
            return s;
        }

        try {
            var field = Cellpose2D.class.getDeclaredField("modelFile");
            field.setAccessible(true);
            var f = (File) field.get(cp);
            if (f != null) return f.getName();
        } catch (Exception ignored) {
            // Intentionally ignored; fallback simply remains unavailable.
        }

        try {
            var outField = Cellpose2D.class.getDeclaredField("outputModelName");
            outField.setAccessible(true);
            var outName = (String) outField.get(cp);
            if (outName != null && !outName.isBlank()) return outName.trim();
        } catch (Exception ignored) {
            // Intentionally ignored; fallback simply remains unavailable.
        }

        throw new IllegalStateException(
                "QC requires an explicit model name/path (or a populated modelFile/outputModelName) "
                        + "to name QC outputs deterministically."
        );
    }

    /**
     * Builder-facing hook for qcDirectory resolution.
     *
     * BIOP baseline:
     *   if qcDirectory is null, use <projectDir>/test
     *
     * ASTRA behavior:
     *   if qcDirectory is provided, use it; otherwise preserve the baseline fallback
     */
    public static File resolveQcDirectory(File quPathProjectDir, File qcDirectory) {
        Objects.requireNonNull(quPathProjectDir, "quPathProjectDir");
        if (enabled() && qcDirectory != null) return qcDirectory;
        return new File(quPathProjectDir, "test");
    }

    /**
     * Builder-facing hook for results directory resolution.
     *
     * Default:
     *   <projectDir>/results
     *
     * Override:
     *   resultsRelativePath=...
     */
    public static File resolveResultsDirectory(File quPathProjectDir) {
        Objects.requireNonNull(quPathProjectDir, "quPathProjectDir");
        String rel = propStr("resultsRelativePath", "results");
        return new File(quPathProjectDir, rel);
    }

    /**
     * Resolve the QC results CSV emitted by the Python QC script before it is moved.
     *
     * BIOP baseline:
     *   <validationDir>/QC-Results/Quality_Control for <modelName>.csv
     */
    public static File resolveQcResultsFile(File validationDirectory, String qcModelName) {
        Objects.requireNonNull(validationDirectory, "validationDirectory");
        if (qcModelName == null || qcModelName.isBlank()) {
            throw new IllegalArgumentException("qcModelName must be non-empty");
        }
        return new File(
                validationDirectory,
                "QC-Results" + File.separator + "Quality_Control for " + qcModelName.trim() + ".csv"
        );
    }

    /**
     * Resolve the final destination of the QC results CSV after any move/copy operation.
     *
     * Default:
     *   <qcFolder>/<originalQcResultsFilename>
     */
    public static File resolveFinalQcResultFile(File qcFolder, File qcResultsFile) {
        Objects.requireNonNull(qcFolder, "qcFolder");
        Objects.requireNonNull(qcResultsFile, "qcResultsFile");
        return new File(qcFolder, qcResultsFile.getName());
    }
}
