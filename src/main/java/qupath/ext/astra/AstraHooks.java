/*-
 * ASTRA hook layer for qupath-extension-cellpose.
 *
 * Purpose:
 * - Keep ASTRA-specific behavior out of upstream BIOP classes as much as possible.
 * - Preserve future mergeability by limiting edits in upstream-owned files to small hook calls.
 * - Centralize ASTRA policy decisions in one place.
 *
 * Configuration:
 * - Optional classpath resource: /astra/astra.properties
 * - If the properties file is absent, the hardcoded defaults in this class are used.
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

    private AstraHooks() {
    }

    private static final String PROPS_PATH = "/astra/astra.properties";
    private static final Properties PROPS = loadProps();

    private static Properties loadProps() {
        Properties props = new Properties();
        try (InputStream in = AstraHooks.class.getResourceAsStream(PROPS_PATH)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
            // Hard-coded defaults apply.
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

    public static boolean enabled() {
        if (Boolean.getBoolean("astra.hooks.disabled")) return false;
        return propBool("enabled", true);
    }

    private static boolean hookEnabled(String key) {
        return enabled() && propBool(key, true);
    }

    private static File requireNonNullDirectory(File directory, String message) {
        if (directory == null) {
            throw new IllegalStateException(message);
        }
        return directory;
    }

    private static File ensureSubdirectoryExists(File parent, String childName) {
        File child = new File(parent, childName);
        if (!child.exists() && !child.mkdirs()) {
            throw new IllegalStateException("Could not create directory: " + child.getAbsolutePath());
        }
        return child;
    }

    private static boolean isUpstreamDefaultTrainingDirectory(File directory) {
        return directory != null && "cellpose-training".equals(directory.getName());
    }

    private static String explicitModel(Cellpose2D cp) {
        try {
            var modelField = Cellpose2D.class.getDeclaredField("model");
            modelField.setAccessible(true);
            String explicit = (String) modelField.get(cp);
            return explicit != null && !explicit.isBlank() ? explicit.trim() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static File modelFile(Cellpose2D cp) {
        try {
            var field = Cellpose2D.class.getDeclaredField("modelFile");
            field.setAccessible(true);
            return (File) field.get(cp);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String basename(String value) {
        if (value == null || value.isBlank()) return null;
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (slash >= 0 && slash < value.length() - 1) return value.substring(slash + 1);
        return value;
    }

    public static File resolveModelDirectory(File rootDirectory, File modelDirectory) {
        Objects.requireNonNull(rootDirectory, "rootDirectory");
        if (modelDirectory != null) return modelDirectory;
        return new File(rootDirectory, "models");
    }

    public static File resolveTrainingRootDirectory(File rootDirectory, File trainingDirectory) {
        Objects.requireNonNull(rootDirectory, "rootDirectory");
        if (hookEnabled("useAstraTrainingRootDirectory")) {
            if (trainingDirectory == null || isUpstreamDefaultTrainingDirectory(trainingDirectory)) {
                return new File(rootDirectory, "training");
            }
            return trainingDirectory;
        }
        if (trainingDirectory != null) return trainingDirectory;
        return new File(rootDirectory, "cellpose-training");
    }

    public static File resolveQcRootDirectory(File rootDirectory, File qcDirectory) {
        Objects.requireNonNull(rootDirectory, "rootDirectory");
        if (qcDirectory != null) return qcDirectory;
        return new File(rootDirectory, hookEnabled("useAstraQcRootDirectory") ? "qc" : "test");
    }

    public static File resolveResultsRootDirectory(File rootDirectory, File resultsDirectory) {
        Objects.requireNonNull(rootDirectory, "rootDirectory");
        if (resultsDirectory != null) return resultsDirectory;
        return new File(rootDirectory, propStr("resultsRelativePath", "results"));
    }

    public static File ensureDirectoryExists(File directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create directory: " + directory.getAbsolutePath());
        }
        return directory;
    }

    public static File resolveTrainingDirectory(File groundTruthDirectory) {
        Objects.requireNonNull(groundTruthDirectory, "groundTruthDirectory");
        if (hookEnabled("useAstraTrainingDirectory") && propStr("trainingDirMode", "flat").equalsIgnoreCase("flat")) {
            return groundTruthDirectory;
        }
        return new File(groundTruthDirectory, "train");
    }

    public static File resolveValidationDirectory(File groundTruthDirectory, File qcDirectory) {
        Objects.requireNonNull(groundTruthDirectory, "groundTruthDirectory");
        if (hookEnabled("useAstraValidationDirectory")) {
            return requireNonNullDirectory(
                    qcDirectory,
                    "ASTRA validation/QC directory resolution requires qcDirectory when ASTRA behavior is enabled."
            );
        }
        return new File(groundTruthDirectory, "test");
    }

    public static boolean skipModelMove(Cellpose2D cp) {
        return hookEnabled("skipModelMove");
    }

    public static boolean skipAutomaticQc(Cellpose2D cp) {
        return hookEnabled("skipAutomaticQc");
    }

    public static boolean saveTrainingGraphAfterTraining(Cellpose2D cp) {
        return hookEnabled("saveTrainingGraphAfterTraining");
    }

    public static File trainingArtifactReturnValue(Cellpose2D cp, File trainingDirectory) {
        return new File(trainingDirectory, "models");
    }

    public static boolean allowLegacyTrainingResultsSave(Cellpose2D cp) {
        if (!enabled()) return true;
        return propBool("saveTrainingResultsAfterTraining", propBool("allowLegacyTrainingResultsSave", true));
    }

    public static void onTrainingResultsParsed(Cellpose2D cp, ResultsTable results) {
        // no-op by default
    }

    public static File resolveQcFolder(File modelDirectory, File resultsDirectory) {
        Objects.requireNonNull(modelDirectory, "modelDirectory");
        if (hookEnabled("useAstraQcFolder")) {
            File root = requireNonNullDirectory(
                    resultsDirectory,
                    "ASTRA QC results routing requires resultsDirectory when ASTRA behavior is enabled."
            );
            return ensureSubdirectoryExists(root, "qc");
        }
        return new File(modelDirectory, "QC");
    }

    public static File resolveTrainingResultsFolder(File modelDirectory, File resultsDirectory) {
        Objects.requireNonNull(modelDirectory, "modelDirectory");
        if (hookEnabled("useAstraTrainingResultsFolder")) {
            File root = requireNonNullDirectory(
                    resultsDirectory,
                    "ASTRA training-results routing requires resultsDirectory when ASTRA behavior is enabled."
            );
            return ensureSubdirectoryExists(root, "training");
        }
        return ensureSubdirectoryExists(modelDirectory, "QC");
    }

    public static void requireQcDirectory(Cellpose2D cp) throws IOException {
        if (!hookEnabled("useAstraValidationDirectory")) return;
        try {
            var field = Cellpose2D.class.getDeclaredField("qcDirectory");
            field.setAccessible(true);
            File qcDir = (File) field.get(cp);
            if (qcDir == null) {
                throw new IOException("ASTRA QC requires qcDirectory to be set by the builder.");
            }
            if (!qcDir.exists() || !qcDir.isDirectory()) {
                throw new IOException("ASTRA QC requires qcDirectory to exist as a directory: " + qcDir.getAbsolutePath());
            }
        } catch (NoSuchFieldException e) {
            throw new IOException("ASTRA QC requires qcDirectory, but Cellpose2D has no qcDirectory field.", e);
        } catch (IllegalAccessException e) {
            throw new IOException("ASTRA QC requires qcDirectory, but it could not be accessed.", e);
        }
    }

    public static File resolveQcPythonFile(File extensionDir) {
        Objects.requireNonNull(extensionDir, "extensionDir");
        String rel = propStr("qcScriptRelativePath", "run-cellpose-qc.py");
        File candidate = new File(extensionDir, rel);
        if (hookEnabled("useAstraQcPythonFile")) {
            if (!candidate.exists()) {
                throw new IllegalStateException(
                        "ASTRA QC script was not found at the configured path: " + candidate.getAbsolutePath());
            }
            return candidate;
        }
        return new File(extensionDir, "run-cellpose-qc.py");
    }

    public static boolean matchesInstalledExtensionJar(String installedJarPath, String extensionVersion) {
        if (installedJarPath == null || extensionVersion == null) return false;
        String path = installedJarPath.replace('\\', '/');
        if (hookEnabled("useAstraExtensionJarMatch")) {
            return path.contains("qupath-extension-cellpose-astra-" + extensionVersion)
                    || path.contains("qupath-extension-cellpose-" + extensionVersion);
        }
        return path.contains("qupath-extension-cellpose-" + extensionVersion);
    }

    public static String resolveQcModelPathForValidation(Cellpose2D cp) {
        Objects.requireNonNull(cp, "cp");
        String explicit = explicitModel(cp);
        if (hookEnabled("useAstraModelResolution") && explicit != null) {
            return explicit;
        }
        File modelFile = modelFile(cp);
        if (modelFile != null) return modelFile.getAbsolutePath();
        if (hookEnabled("useAstraModelResolution")) {
            throw new IllegalStateException(
                    "QC requires an explicit model path/name or a populated modelFile when ASTRA behavior is enabled."
            );
        }
        throw new IllegalStateException("QC requires modelFile for baseline behavior.");
    }

    public static String resolveQcModelNameForQc(Cellpose2D cp) {
        Objects.requireNonNull(cp, "cp");
        String explicit = explicitModel(cp);
        if (hookEnabled("useAstraModelResolution") && explicit != null) {
            return basename(explicit);
        }
        File modelFile = modelFile(cp);
        if (modelFile != null) return modelFile.getName();
        if (hookEnabled("useAstraModelResolution")) {
            throw new IllegalStateException(
                    "QC requires an explicit model path/name or a populated modelFile to name outputs deterministically."
            );
        }
        throw new IllegalStateException("QC requires modelFile for baseline behavior.");
    }

    public static File resolveQcDirectory(File quPathProjectDir, File qcDirectory) {
        Objects.requireNonNull(quPathProjectDir, "quPathProjectDir");
        if (qcDirectory != null) return qcDirectory;
        return new File(quPathProjectDir, hookEnabled("useAstraQcRootDirectory") ? "qc" : "test");
    }

    public static File resolveResultsDirectory(File quPathProjectDir) {
        return resolveResultsDirectory(quPathProjectDir, null);
    }

    public static File resolveResultsDirectory(File quPathProjectDir, File resultsDirectory) {
        Objects.requireNonNull(quPathProjectDir, "quPathProjectDir");
        if (resultsDirectory != null) return resultsDirectory;
        return new File(quPathProjectDir, propStr("resultsRelativePath", "results"));
    }

    public static File resolveQcResultsFile(File validationDirectory, String qcModelName, File qcOutputDirectory) {
        Objects.requireNonNull(validationDirectory, "validationDirectory");
        if (hookEnabled("useAstraQcResultsRouting")) {
            File root = requireNonNullDirectory(
                    qcOutputDirectory,
                    "ASTRA QC results routing requires a QC output directory when ASTRA behavior is enabled."
            );
            return new File(root, "qc_results.csv");
        }
        if (qcModelName == null || qcModelName.isBlank()) {
            throw new IllegalArgumentException("qcModelName must be non-empty");
        }
        return new File(
                validationDirectory,
                "QC-Results" + File.separator + "Quality_Control for " + qcModelName.trim() + ".csv"
        );
    }

    public static String resolveModelDisplayName(Cellpose2D cp) {
        Objects.requireNonNull(cp, "cp");
        String explicit = explicitModel(cp);
        if (explicit != null) return basename(explicit);
        File modelFile = modelFile(cp);
        if (modelFile != null) return modelFile.getName();
        throw new IllegalStateException(
                "Unable to resolve a model display name; explicit model or modelFile is required."
        );
    }

    public static File resolveTrainingResultsFile(File trainingResultsFolder, String modelDisplayName) {
        Objects.requireNonNull(trainingResultsFolder, "trainingResultsFolder");
        if (hookEnabled("useAstraTrainingResultsRouting")) {
            return new File(trainingResultsFolder, "training_results.csv");
        }
        if (modelDisplayName == null || modelDisplayName.isBlank()) {
            throw new IllegalArgumentException("modelDisplayName must be non-empty");
        }
        return new File(trainingResultsFolder, "Training Result - " + modelDisplayName.trim());
    }

    public static File resolveTrainingGraphFile(File trainingResultsFolder, String modelDisplayName) {
        Objects.requireNonNull(trainingResultsFolder, "trainingResultsFolder");
        if (hookEnabled("useAstraTrainingResultsRouting")) {
            return new File(trainingResultsFolder, "training_graph.png");
        }
        if (modelDisplayName == null || modelDisplayName.isBlank()) {
            throw new IllegalArgumentException("modelDisplayName must be non-empty");
        }
        return new File(trainingResultsFolder, "Training Result - " + modelDisplayName.trim() + ".png");
    }

    public static File resolveFinalQcResultFile(File qcFolder, File qcResultsFile) {
        Objects.requireNonNull(qcFolder, "qcFolder");
        Objects.requireNonNull(qcResultsFile, "qcResultsFile");
        return new File(qcFolder, qcResultsFile.getName());
    }
}
