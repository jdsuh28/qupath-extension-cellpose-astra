package qupath.ext.astra;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import qupath.ext.biop.cellpose.CellposeSetup;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.Comparator;

/**
 * Installs and registers the ASTRA Python runtime used by Cellpose-SAM.
 *
 * <p>The installer is intentionally ASTRA-specific and leaves upstream BIOP
 * setup untouched. It creates a deterministic user-local conda-prefix
 * environment, installs the ASTRA Cellpose fork, validates imports/startup, and
 * then writes the extension preference consumed by {@link CellposeSetup}.</p>
 *
 * <p>Conda/miniforge is the only supported installer path. It allows ASTRA to
 * bootstrap a pinned Python runtime without relying on system Python.</p>
 */
final class RuntimeInstaller {

    static final String ASTRA_CELLPOSE_REPO = "https://github.com/jdsuh28/cellpose-astra.git";
    static final String DEFAULT_CELLPOSE_REF = "v4.1.1+astra.3";
    static final String DEFAULT_PYTHON_VERSION = "3.10";
    static final String ENVIRONMENT_NAME = "cellpose-astra";
    static final String CONDA_OVERRIDE_OSX = "CONDA_OVERRIDE_OSX";
    static final String MACOS_CONDA_SOLVER_VERSION = "10.15";
    static final String RUNTIME_PIN_PREFIX = "runtime_pin.";

    private static final String RUNTIME_FOLDER_NAME = ENVIRONMENT_NAME;
    private static final String RELEASE_PROPERTIES_RESOURCE = "qupath/ext/astra/release/runtime.properties";
    private static final String RELEASE_MANIFEST_RESOURCE = "astra/rulebook/manifests/release.json";
    private static final String MINIFORGE_FOLDER_NAME = "miniforge";
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(45);
    private static final Duration BOOTSTRAP_TIMEOUT = Duration.ofMinutes(20);
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {}.getType();
    private static final Map<String, String> DEFAULT_RUNTIME_PINS = Map.ofEntries(
            Map.entry("MarkupSafe", "3.0.3"),
            Map.entry("fastremap", "1.20.0"),
            Map.entry("filelock", "3.29.4"),
            Map.entry("fill-voids", "2.1.2"),
            Map.entry("fsspec", "2026.6.0"),
            Map.entry("imagecodecs", "2025.3.30"),
            Map.entry("jinja2", "3.1.6"),
            Map.entry("mpmath", "1.3.0"),
            Map.entry("natsort", "8.4.0"),
            Map.entry("networkx", "3.4.2"),
            Map.entry("numpy", "1.26.4"),
            Map.entry("opencv-python-headless", "4.10.0.84"),
            Map.entry("pillow", "12.2.0"),
            Map.entry("roifile", "2025.12.12"),
            Map.entry("scipy", "1.15.3"),
            Map.entry("segment-anything", "1.0"),
            Map.entry("sympy", "1.14.0"),
            Map.entry("tifffile", "2025.5.10"),
            Map.entry("torch", "2.2.2"),
            Map.entry("torchvision", "0.17.2"),
            Map.entry("tqdm", "4.68.3"),
            Map.entry("typing-extensions", "4.15.0")
    );

    private static final class InstallerGeometry {
        private static final double ROOT_PADDING =
                LauncherGeometryTokens.INTRA_PANEL_MARGIN;
        private static final double HEADER_ROW_GAP =
                LauncherGeometryTokens.LAYOUT_UNIT * 5.0 / 12.0;
        private static final double ROOT_CONTENT_GAP =
                HEADER_ROW_GAP;
        private static final double STATUS_CARD_PADDING =
                LauncherGeometryTokens.LAYOUT_UNIT * 7.0 / 12.0;
        private static final double STATUS_CARD_GAP =
                LauncherGeometryTokens.LAYOUT_UNIT / 3.0;
        private static final double PROGRESS_BAR_HEIGHT =
                LauncherGeometryTokens.LAYOUT_UNIT / 3.0;
        private static final double WINDOW_WIDTH =
                LauncherGeometryTokens.LAYOUT_UNIT * 65.0 / 2.0;
        private static final double WINDOW_HEIGHT =
                LauncherGeometryTokens.LAYOUT_UNIT * 35.0 / 2.0;

        private InstallerGeometry() {
        }
    }

    private RuntimeInstaller() {
    }

    private enum RuntimeSetupKind {
        ALREADY_READY(
                "Runtime ready",
                "The existing ASTRA-managed runtime passed validation.",
                "ASTRA registered the existing managed runtime. You can run Cellpose workflows now."),
        CREATED_RUNTIME(
                "Runtime created",
                "ASTRA created and validated a new managed runtime.",
                "ASTRA registered the new managed runtime. You can run Cellpose workflows now."),
        REPAIRED_RUNTIME(
                "Runtime repaired",
                "ASTRA rebuilt and validated the managed runtime.",
                "ASTRA registered the repaired managed runtime. You can run Cellpose workflows now.");

        private final String title;
        private final String heading;
        private final String nextAction;

        RuntimeSetupKind(String title, String heading, String nextAction) {
            this.title = title;
            this.heading = heading;
            this.nextAction = nextAction;
        }
    }

    private enum RuntimeFailureKind {
        CANCELLED(
                "Runtime setup cancelled",
                "ASTRA stopped the active runtime setup command.",
                "Run ASTRA Runtime Setup again when you are ready."),
        NETWORK_DOWNLOAD_FAILED(
                "Download failed",
                "ASTRA could not download or verify a required runtime installer.",
                "Check the network connection, then run ASTRA Runtime Setup again."),
        CONDA_SOLVER_FAILED(
                "Conda runtime creation failed",
                "ASTRA could not create the pinned Python runtime with conda.",
                "Run ASTRA Runtime Setup again. If this repeats, send the install log."),
        PIP_INSTALL_FAILED(
                "Python package install failed",
                "ASTRA could not install the release-pinned Cellpose-ASTRA Python stack.",
                "Check the network connection, then run ASTRA Runtime Setup again. If this repeats, send the install log."),
        PYTHON_PACKAGE_VALIDATION_FAILED(
                "Runtime validation failed",
                "The managed runtime did not match ASTRA's pinned Python/package requirements.",
                "Run ASTRA Runtime Setup again to recreate the managed runtime."),
        PERMISSION_DELETE_FAILED(
                "Runtime repair needs file access",
                "ASTRA could not remove the broken managed runtime directory.",
                "Close tools using ~/.astra/cellpose-astra, check file permissions, then run repair again."),
        UNEXPECTED_ERROR(
                "Runtime setup failed",
                "ASTRA hit an unexpected runtime setup error.",
                "Run ASTRA Runtime Setup again. If this repeats, send the install log.");

        private final String title;
        private final String heading;
        private final String nextAction;

        RuntimeFailureKind(String title, String heading, String nextAction) {
            this.title = title;
            this.heading = heading;
            this.nextAction = nextAction;
        }
    }

    record RuntimeSetupOutcome(RuntimeSetupKind kind, File python, File logFile) {

        String title() {
            return kind.title;
        }

        String heading() {
            return kind.heading;
        }

        String body() {
            return kind.nextAction + "\n\nRuntime Python:\n" + python.getAbsolutePath()
                    + "\n\nInstall log:\n" + logFile.getAbsolutePath();
        }
    }

    record RuntimeSetupFailure(RuntimeFailureKind kind, Throwable throwable, File logFile) {

        String title() {
            return kind.title;
        }

        String heading() {
            return kind.heading;
        }

        String body() {
            String detail = throwable == null || throwable.getMessage() == null
                    ? "No additional diagnostic message was reported."
                    : throwable.getMessage();
            return kind.nextAction + "\n\nWhat happened:\n" + detail
                    + "\n\nInstall log:\n" + logFile.getAbsolutePath();
        }
    }

    /**
     * Starts an asynchronous install-or-repair run from the QuPath menu.
     *
     * @param runtimePythonPath persistent Cellpose runtime Python preference.
     */
    static void installOrRepairAsync(StringProperty runtimePythonPath) {
        Objects.requireNonNull(runtimePythonPath, "runtimePythonPath");
        boolean proceed = PipelineLauncher.createAstraSuccessConfirmationDialog(
                null,
                "ASTRA Runtime Setup",
                "Create or repair the ASTRA Cellpose runtime?",
                """
                ASTRA will create or repair a local Python runtime, install Cellpose-ASTRA,
                verify the installation, and register it with QuPath.

                This can take several minutes and requires internet access the first time.
                """)
                .showAndWait()
                .filter(ButtonType.OK::equals)
                .isPresent();
        if (!proceed) {
            return;
        }

        InstallProgress progress = InstallProgress.show();
        Thread worker = new Thread(() -> installOrRepair(runtimePythonPath, progress), "ASTRA runtime installer");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Performs the runtime installation and preference update.
     *
     * @param runtimePythonPath persistent Cellpose runtime Python preference.
     */
    private static void installOrRepair(StringProperty runtimePythonPath, InstallProgress progress) {
        File logFile = installLogFile();
        try {
            progress.step("Preparing install log", logFile.getAbsolutePath());
            File runtimeDirectory = runtimeDirectory();
            File python = runtimePythonExecutable(runtimeDirectory);
            boolean managedRuntimeExists = runtimeDirectory.exists();

            progress.step("Checking managed runtime", python.getAbsolutePath());
            if (isValidRuntime(python)) {
                verifyRuntime(python, progress, logFile);
                applyRuntimePath(runtimePythonPath, python);
                RuntimeSetupOutcome outcome = new RuntimeSetupOutcome(RuntimeSetupKind.ALREADY_READY, python, logFile);
                progress.done(outcome);
                showOutcome("ASTRA Runtime Setup", outcome);
                return;
            }

            boolean repairing = managedRuntimeExists;
            if (repairing) {
                progress.step("Repairing managed runtime", runtimeDirectory.getAbsolutePath());
                removeManagedRuntime(runtimeDirectory, progress, logFile);
            } else {
                progress.step("Creating managed runtime", runtimeDirectory.getAbsolutePath());
            }

            if (!runtimeDirectory.exists()) {
                Files.createDirectories(runtimeDirectory.getParentFile().toPath());
            }

            installRuntime(runtimeDirectory, python, progress, logFile);
            verifyRuntime(python, progress, logFile);
            applyRuntimePath(runtimePythonPath, python);
            RuntimeSetupOutcome outcome = new RuntimeSetupOutcome(
                    repairing ? RuntimeSetupKind.REPAIRED_RUNTIME : RuntimeSetupKind.CREATED_RUNTIME,
                    python,
                    logFile);
            progress.done(outcome);
            showOutcome("ASTRA Runtime Setup", outcome);
        } catch (Throwable t) {
            RuntimeSetupFailure failure = new RuntimeSetupFailure(classifyFailure(t), t, logFile);
            progress.failed(failure);
            showFailure("ASTRA Runtime Setup", failure);
        }
    }

    /**
     * Installs the ASTRA runtime into the deterministic conda prefix.
     *
     * @param runtimeDirectory deterministic runtime directory.
     * @param python runtime Python executable.
     * @param progress progress UI/log sink.
     * @param logFile persistent install log.
     * @throws IOException if installation fails.
     * @throws InterruptedException if command execution is interrupted.
     */
    private static void installRuntime(File runtimeDirectory, File python, InstallProgress progress, File logFile) throws IOException, InterruptedException {
        String conda = findOrBootstrapCondaExecutable(progress, logFile);
        progress.step("Creating conda runtime", String.join(" ", condaCreateCommand(conda, runtimeDirectory)));
        runCommand(condaCreateCommand(conda, runtimeDirectory), null, condaCreateEnvironmentOverrides(), progress, logFile);
        progress.step("Upgrading Python packaging tools", python.getAbsolutePath());
        runCommand(List.of(python.getAbsolutePath(), "-m", "pip", "install", "--upgrade", "pip", "setuptools", "wheel"), null, progress, logFile);
        File constraints = writePipConstraintsFile(logFile);
        progress.step("Installing Cellpose-ASTRA", cellposePackageSpec() + "\nconstraints: " + constraints.getAbsolutePath());
        runCommand(pipInstallCellposeCommand(python, constraints), null, progress, logFile);
        progress.step("Checking Python package consistency", python.getAbsolutePath());
        runCommand(pipCheckCommand(python), null, progress, logFile);
    }

    /**
     * Removes the deterministic ASTRA-managed runtime prefix before repair.
     *
     * @param runtimeDirectory deterministic managed runtime directory.
     * @param progress progress UI/log sink.
     * @param logFile persistent install log.
     * @throws IOException if the directory cannot be removed.
     */
    static void removeManagedRuntime(File runtimeDirectory, InstallProgress progress, File logFile) throws IOException {
        if (runtimeDirectory == null || !runtimeDirectory.exists()) {
            return;
        }
        if (!RUNTIME_FOLDER_NAME.equals(runtimeDirectory.getName())) {
            throw new IOException("ASTRA refused to remove a non-managed runtime path: " + runtimeDirectory.getAbsolutePath());
        }
        File expectedParent = new File(System.getProperty("user.home"), ".astra").getCanonicalFile();
        File actualParent = runtimeDirectory.getParentFile() == null ? null : runtimeDirectory.getParentFile().getCanonicalFile();
        if (!expectedParent.equals(actualParent)) {
            throw new IOException("ASTRA refused to remove a runtime outside the managed ~/.astra folder: " + runtimeDirectory.getAbsolutePath());
        }

        progressStep(progress, "Removing broken managed runtime", runtimeDirectory.getAbsolutePath());
        appendLog(logFile, "Removing broken managed runtime: " + runtimeDirectory.getAbsolutePath() + System.lineSeparator());
        try (var stream = Files.walk(runtimeDirectory.toPath())) {
            List<Path> paths = stream
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new IOException("ASTRA could not remove the broken managed runtime directory: "
                    + runtimeDirectory.getAbsolutePath() + ". Close any process using it and try repair again.", e);
        }
    }

    /**
     * Converts a setup exception into a concise user-facing failure category.
     *
     * @param throwable failure.
     * @return failure category.
     */
    static RuntimeFailureKind classifyFailure(Throwable throwable) {
        if (throwable instanceof CancellationException) {
            return RuntimeFailureKind.CANCELLED;
        }
        String text = String.valueOf(throwable == null ? "" : throwable.toString()).toLowerCase(Locale.ROOT);
        if (text.contains("could not remove") || text.contains("refused to remove")
                || text.contains("accessdenied") || text.contains("permission")) {
            return RuntimeFailureKind.PERMISSION_DELETE_FAILED;
        }
        if (text.contains("runtime package mismatch") || text.contains("runtime python version mismatch")
                || text.contains("runtime numpy mismatch") || text.contains("torch/numpy bridge")
                || text.contains("cellpose") && text.contains("validation")) {
            return RuntimeFailureKind.PYTHON_PACKAGE_VALIDATION_FAILED;
        }
        if (text.contains("conda create") || text.contains("solving environment")
                || text.contains("unsatisfiableerror") || text.contains("libmamba")
                || text.contains("creating conda runtime")) {
            return RuntimeFailureKind.CONDA_SOLVER_FAILED;
        }
        if (text.contains("pip install") || text.contains("installing cellpose-astra")) {
            return RuntimeFailureKind.PIP_INSTALL_FAILED;
        }
        if (text.contains("download") || text.contains("checksum") || text.contains("http")
                || text.contains("unknownhost") || text.contains("connection")) {
            return RuntimeFailureKind.NETWORK_DOWNLOAD_FAILED;
        }
        return RuntimeFailureKind.UNEXPECTED_ERROR;
    }

    /**
     * Builds the pinned pip package spec for the released ASTRA Cellpose fork.
     *
     * @return pip install spec containing a tag or immutable ref.
     */
    static String cellposePackageSpec() {
        return "git+" + ASTRA_CELLPOSE_REPO + "@" + pinnedCellposeRef();
    }

    /**
     * Resolves the Cellpose-ASTRA ref pinned into the packaged release.
     *
     * @return release-pinned Cellpose-ASTRA ref, or the development fallback.
     */
    static String pinnedCellposeRef() {
        return releaseProperty("cellpose_astra_ref", DEFAULT_CELLPOSE_REF);
    }

    /**
     * Resolves the Python version pinned into the packaged release.
     *
     * @return release-pinned Python major/minor version, or the development
     * fallback.
     */
    static String pinnedPythonVersion() {
        return releaseProperty("python_version", DEFAULT_PYTHON_VERSION);
    }

    /**
     * Resolves all release-pinned Python packages for the managed runtime.
     *
     * @return deterministic package/version map sorted by package name.
     */
    static Map<String, String> runtimePins() {
        Properties properties = releaseProperties();
        Map<String, String> pins = new TreeMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith(RUNTIME_PIN_PREFIX)) {
                String packageName = name.substring(RUNTIME_PIN_PREFIX.length()).trim();
                String version = properties.getProperty(name, "").trim();
                if (!packageName.isEmpty() && !version.isEmpty()) {
                    pins.put(packageName, version);
                }
            }
        }
        if (pins.isEmpty()) {
            pins.putAll(DEFAULT_RUNTIME_PINS);
        }
        return Collections.unmodifiableMap(pins);
    }

    /**
     * Writes a pip constraints file from release-pinned runtime metadata.
     *
     * @param logFile install log used to choose a colocated diagnostics path.
     * @return generated constraints file.
     * @throws IOException if writing fails.
     */
    static File writePipConstraintsFile(File logFile) throws IOException {
        File dir = logFile == null ? Files.createTempDirectory("astra-runtime-constraints").toFile() : logFile.getParentFile();
        Files.createDirectories(dir.toPath());
        String base = logFile == null ? "cellpose-astra-runtime" : logFile.getName().replaceFirst("\\.log$", "");
        File constraints = new File(dir, base + "-constraints.txt");
        StringBuilder text = new StringBuilder();
        runtimePins().forEach((name, version) -> text.append(name).append("==").append(version).append(System.lineSeparator()));
        Files.writeString(constraints.toPath(), text.toString(), StandardCharsets.UTF_8);
        return constraints;
    }

    /**
     * Builds the constrained Cellpose-ASTRA pip install command.
     *
     * @param python runtime Python executable.
     * @param constraints generated constraints file.
     * @return command tokens.
     */
    static List<String> pipInstallCellposeCommand(File python, File constraints) {
        return List.of(
                python.getAbsolutePath(),
                "-m",
                "pip",
                "install",
                "--upgrade",
                "-c",
                constraints.getAbsolutePath(),
                cellposePackageSpec()
        );
    }

    /**
     * Builds the package consistency check command.
     *
     * @param python runtime Python executable.
     * @return command tokens.
     */
    static List<String> pipCheckCommand(File python) {
        return List.of(python.getAbsolutePath(), "-m", "pip", "check");
    }

    /**
     * Reads a packaged release property with a development fallback.
     *
     * @param key property key.
     * @param fallback value used when release metadata is absent.
     * @return trimmed property value or fallback.
     */
    private static String releaseProperty(String key, String fallback) {
        Properties properties = releaseProperties();
        String value = properties.getProperty(key, "").trim();
        return value.isEmpty() ? fallback : value;
    }

    private static Properties releaseProperties() {
        Properties properties = new Properties();
        try (var stream = RuntimeInstaller.class.getClassLoader().getResourceAsStream(RELEASE_PROPERTIES_RESOURCE)) {
            if (stream == null) {
                return properties;
            }
            properties.load(stream);
        } catch (IOException ignored) {
            return new Properties();
        }
        return properties;
    }

    /**
     * Returns the deterministic ASTRA runtime directory.
     *
     * @return user-local runtime directory.
     */
    static File runtimeDirectory() {
        return new File(new File(System.getProperty("user.home"), ".astra"), RUNTIME_FOLDER_NAME);
    }

    /**
     * Resolves the persistent installer log path.
     *
     * @return timestamped installer log path under {@code ~/.astra/logs/install}.
     */
    static File installLogFile() {
        File dir = new File(new File(new File(System.getProperty("user.home"), ".astra"), "logs"), "install");
        String stamp = Instant.now().toString().replaceAll("[^0-9T]", "").replace("T", "-");
        return new File(dir, "cellpose-astra-install-" + stamp + ".log");
    }

    /**
     * Resolves the virtual-environment Python executable.
     *
     * @param runtimeDirectory virtual-environment directory.
     * @return platform-specific Python executable path.
     */
    static File runtimePythonExecutable(File runtimeDirectory) {
        if (isWindows()) {
            return new File(new File(runtimeDirectory, "Scripts"), "python.exe");
        }
        return new File(new File(runtimeDirectory, "bin"), "python");
    }

    /**
     * Builds the default conda-prefix creation command.
     *
     * @param condaExecutable conda/mamba executable.
     * @param runtimeDirectory deterministic runtime prefix.
     * @return command tokens.
     */
    static List<String> condaCreateCommand(String condaExecutable, File runtimeDirectory) {
        List<String> command = new ArrayList<>();
        command.add(condaExecutable);
        command.add("create");
        if (usesCondaExecutable(condaExecutable)) {
            command.add("--solver=libmamba");
        }
        command.add("-y");
        command.add("-p");
        command.add(runtimeDirectory.getAbsolutePath());
        command.add("python=" + pinnedPythonVersion());
        return List.copyOf(command);
    }

    /**
     * Detects the classic {@code conda} frontend so ASTRA can request the
     * libmamba solver explicitly during environment creation.
     *
     * @param condaExecutable conda-compatible executable name/path.
     * @return true for conda, false for mamba/micromamba.
     */
    static boolean usesCondaExecutable(String condaExecutable) {
        String name = new File(String.valueOf(condaExecutable)).getName().toLowerCase(Locale.ROOT);
        return "conda".equals(name) || "conda.exe".equals(name);
    }

    /**
     * Builds deterministic environment overrides for conda runtime creation.
     *
     * <p>Conda represents the host macOS version as a virtual package named
     * {@code __osx}. Future macOS marketing versions can make old-but-valid
     * Python package constraints look unsatisfiable to the solver. ASTRA
     * targets a conservative macOS solver version for the managed runtime so
     * Python 3.10.x can be resolved reproducibly.</p>
     *
     * @return environment overrides for conda create.
     */
    static Map<String, String> condaCreateEnvironmentOverrides() {
        return condaCreateEnvironmentOverrides(System.getProperty("os.name", ""));
    }

    /**
     * Builds deterministic environment overrides for conda runtime creation.
     *
     * @param osName JVM operating-system name.
     * @return environment overrides for conda create.
     */
    static Map<String, String> condaCreateEnvironmentOverrides(String osName) {
        if (String.valueOf(osName).toLowerCase(Locale.ROOT).contains("mac")
                || String.valueOf(osName).toLowerCase(Locale.ROOT).contains("darwin")) {
            return Map.of(CONDA_OVERRIDE_OSX, MACOS_CONDA_SOLVER_VERSION);
        }
        return Map.of();
    }

    /**
     * Builds validation commands that must pass before the runtime is accepted.
     *
     * @param python runtime Python executable.
     * @return validation command list.
     */
    static List<List<String>> validationCommands(File python) {
        String py = python.getAbsolutePath();
        String required = pinnedPythonVersion();
        return List.of(
                List.of(py, "-c", pythonVersionProbeCode(required)),
                List.of(py, "--version"),
                List.of(py, "-c", "import numpy; print('numpy', numpy.__version__)"),
                List.of(py, "-c", runtimePinProbeCode()),
                List.of(py, "-c", "import torch; print('torch', torch.__version__)"),
                List.of(py, "-c", torchNumpyBridgeProbeCode()),
                List.of(py, "-c", "import cellpose; from cellpose.version import version_str; assert 'astra' in version_str.lower(), version_str; print('cellpose', version_str)"),
                List.of(py, "-c", "import cellpose, cellpose.astra, torch, numpy; print('ASTRA runtime import validation OK')"),
                List.of(py, "-m", "cellpose.astra", "--version")
        );
    }

    static String pythonVersionProbeCode(String required) {
        return "import sys\n"
                + "required='" + pythonString(required) + "'\n"
                + "detected=f'{sys.version_info.major}.{sys.version_info.minor}'\n"
                + "if detected != required:\n"
                + "    raise SystemExit('ASTRA runtime Python version mismatch: required Python " + pythonString(required)
                + ", detected Python ' + detected + '. Use ASTRA Runtime Setup to create the managed Miniforge/conda runtime.')\n"
                + "print('python', detected)\n";
    }

    static String runtimePinProbeCode() {
        StringBuilder pins = new StringBuilder("{");
        runtimePins().forEach((name, version) -> {
            if (pins.length() > 1) {
                pins.append(", ");
            }
            pins.append("'").append(pythonString(name)).append("': '").append(pythonString(version)).append("'");
        });
        pins.append("}");
        return "from importlib import metadata as md\n"
                + "pins=" + pins + "\n"
                + "for name, expected in pins.items():\n"
                + "    actual=md.version(name)\n"
                + "    if actual != expected:\n"
                + "        raise SystemExit(f'ASTRA runtime package mismatch for {name}: required {expected}, detected {actual}. Use ASTRA Runtime Setup to recreate the managed runtime.')\n"
                + "print('runtime pins OK', ','.join(f'{k}=={v}' for k, v in sorted(pins.items())))\n";
    }

    static String torchNumpyBridgeProbeCode() {
        return "import numpy as np\n"
                + "if int(np.__version__.split('.')[0]) >= 2:\n"
                + "    raise SystemExit('ASTRA runtime NumPy mismatch: required NumPy < 2, detected ' + np.__version__ + '. Use ASTRA Runtime Setup to recreate the managed runtime.')\n"
                + "import torch\n"
                + "x=np.zeros((2,), dtype=np.float32)\n"
                + "y=torch.from_numpy(x)\n"
                + "print('torch numpy bridge OK', np.__version__, torch.__version__, tuple(y.shape))\n";
    }

    private static String pythonString(String value) {
        return String.valueOf(value).replace("\\", "\\\\").replace("'", "\\'");
    }

    /**
     * Locates a conda-compatible executable for the default installer path.
     *
     * @return executable name or path.
     * @throws IOException if no conda-compatible executable is available.
     * @throws InterruptedException if probing is interrupted.
     */
    static String findCondaExecutable() throws IOException, InterruptedException {
        String conda = findExistingCondaExecutable();
        if (conda != null) {
            return conda;
        }
        throw new IOException("No conda-compatible executable was found. Install Miniforge/conda or set ASTRA_CONDA. " +
                "ASTRA can also bootstrap its managed Miniforge runtime when downloads are available.");
    }

    /**
     * Locates an existing conda-compatible executable without bootstrapping.
     *
     * @return executable name/path, or null when none can be probed.
     * @throws InterruptedException if probing is interrupted.
     */
    static String findExistingCondaExecutable() throws InterruptedException {
        List<String> candidates = new ArrayList<>();
        String override = System.getenv("ASTRA_CONDA");
        if (override != null && !override.isBlank()) {
            candidates.add(override.trim());
        }
        candidates.add("conda");
        candidates.add("mamba");
        candidates.add("micromamba");

        for (String candidate : candidates) {
            try {
                CommandResult result = runCommand(List.of(candidate, "--version"), null, Duration.ofSeconds(20), null, null);
                if (result.exitCode() == 0) {
                    return candidate;
                }
            } catch (IOException ignored) {
                // Try the next conda-compatible executable.
            }
        }
        return null;
    }

    /**
     * Locates conda, installing ASTRA-private Miniforge when no conda-compatible
     * command exists.
     *
     * @param progress progress UI/log sink.
     * @param logFile persistent install log.
     * @return existing or bootstrapped conda executable.
     * @throws IOException if lookup, download, checksum, or install fails.
     * @throws InterruptedException if command probing or installation is interrupted.
     */
    static String findOrBootstrapCondaExecutable(InstallProgress progress, File logFile) throws IOException, InterruptedException {
        String existing = findExistingCondaExecutable();
        if (existing != null) {
            return existing;
        }

        MiniforgeInstaller installer = miniforgeInstallerForCurrentPlatform();
        File conda = miniforgeCondaExecutable(installer);
        if (isUsableCondaExecutable(conda)) {
            return conda.getAbsolutePath();
        }

        bootstrapMiniforge(installer, progress, logFile);
        if (!isUsableCondaExecutable(conda)) {
            throw new IOException("ASTRA Miniforge install completed, but conda is not usable: " + conda.getAbsolutePath());
        }
        return conda.getAbsolutePath();
    }

    /**
     * Resolves the ASTRA-private Miniforge root.
     *
     * @return user-local Miniforge directory.
     */
    static File miniforgeDirectory() {
        return new File(new File(System.getProperty("user.home"), ".astra"), MINIFORGE_FOLDER_NAME);
    }

    /**
     * Resolves the ASTRA-private Miniforge installer download cache.
     *
     * @return user-local download directory.
     */
    static File miniforgeDownloadDirectory() {
        return new File(new File(new File(System.getProperty("user.home"), ".astra"), "downloads"), MINIFORGE_FOLDER_NAME);
    }

    /**
     * Selects the release-pinned Miniforge installer for the current platform.
     *
     * @return selected installer metadata.
     */
    static MiniforgeInstaller miniforgeInstallerForCurrentPlatform() {
        return miniforgeInstallerForPlatform(platformKey(System.getProperty("os.name", ""), System.getProperty("os.arch", "")));
    }

    /**
     * Selects the release-pinned Miniforge installer for a normalized platform.
     *
     * @param platformKey normalized platform key.
     * @return selected installer metadata.
     */
    static MiniforgeInstaller miniforgeInstallerForPlatform(String platformKey) {
        Map<String, Object> manifest = releaseManifest();
        Map<String, Object> miniforge = mapValue(manifest.get("miniforge"));
        String version = stringValue(miniforge.get("version"));
        Map<String, Object> platform = mapValue(mapValue(miniforge.get("platforms")).get(platformKey));
        if (version.isBlank() || platform.isEmpty()) {
            throw new IllegalStateException("ASTRA release manifest does not support Miniforge platform '" + platformKey + "'.");
        }
        return new MiniforgeInstaller(
                version,
                platformKey,
                stringValue(platform.get("installerType")),
                stringValue(platform.get("fileName")),
                stringValue(platform.get("url")),
                normalizeSha256(stringValue(platform.get("sha256"))),
                stringValue(platform.get("condaExecutable"))
        );
    }

    /**
     * Builds a normalized platform key for release-manifest lookup.
     *
     * @param osName JVM operating-system name.
     * @param osArch JVM architecture name.
     * @return normalized platform key.
     */
    static String platformKey(String osName, String osArch) {
        String os = String.valueOf(osName).toLowerCase(Locale.ROOT);
        String arch = normalizeArch(osArch);
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos-" + arch;
        }
        if (os.contains("win")) {
            return "windows-" + arch;
        }
        if (os.contains("linux")) {
            return "linux-" + arch;
        }
        return os.replaceAll("[^a-z0-9]+", "-") + "-" + arch;
    }

    /**
     * Builds the silent Miniforge install command.
     *
     * @param installer selected installer metadata.
     * @param installerFile downloaded installer.
     * @return command tokens.
     */
    static List<String> miniforgeInstallCommand(MiniforgeInstaller installer, File installerFile) {
        File target = miniforgeDirectory();
        if ("sh".equals(installer.installerType())) {
            return List.of("bash", installerFile.getAbsolutePath(), "-b", "-p", target.getAbsolutePath());
        }
        if ("exe".equals(installer.installerType())) {
            return List.of(
                    installerFile.getAbsolutePath(),
                    "/S",
                    "/InstallationType=JustMe",
                    "/RegisterPython=0",
                    "/AddToPath=0",
                    "/NoRegistry=1",
                    "/D=" + target.getAbsolutePath()
            );
        }
        throw new IllegalStateException("Unsupported Miniforge installer type: " + installer.installerType());
    }

    /**
     * Resolves the conda executable inside ASTRA-private Miniforge.
     *
     * @param installer selected installer metadata.
     * @return expected conda executable path.
     */
    static File miniforgeCondaExecutable(MiniforgeInstaller installer) {
        return new File(miniforgeDirectory(), installer.condaExecutable());
    }

    /**
     * Downloads, verifies, and silently installs ASTRA-private Miniforge.
     *
     * @param installer selected installer metadata.
     * @param progress progress UI/log sink.
     * @param logFile persistent install log.
     * @throws IOException if download, checksum, or install fails.
     * @throws InterruptedException if install is interrupted.
     */
    private static void bootstrapMiniforge(MiniforgeInstaller installer, InstallProgress progress, File logFile) throws IOException, InterruptedException {
        Files.createDirectories(miniforgeDownloadDirectory().toPath());
        Files.createDirectories(miniforgeDirectory().getParentFile().toPath());
        File installerFile = new File(miniforgeDownloadDirectory(), installer.fileName());
        progressStep(progress, "Downloading Miniforge", installer.url());
        downloadFile(installer.url(), installerFile, progress, logFile);
        progressStep(progress, "Verifying Miniforge checksum", installerFile.getAbsolutePath());
        verifySha256(installerFile, installer.sha256());
        progressStep(progress, "Installing ASTRA-private Miniforge", miniforgeDirectory().getAbsolutePath());
        List<String> command = miniforgeInstallCommand(installer, installerFile);
        CommandResult result = runCommand(command, null, BOOTSTRAP_TIMEOUT, progress, logFile);
        if (result.exitCode() != 0) {
            throw new IOException(formatCommandFailure(command, result));
        }
    }

    private static boolean isUsableCondaExecutable(File conda) throws InterruptedException {
        if (conda == null || !conda.isFile()) {
            return false;
        }
        try {
            return runCommand(List.of(conda.getAbsolutePath(), "--version"), null, Duration.ofSeconds(20), null, null).exitCode() == 0;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void downloadFile(String url, File target, InstallProgress progress, File logFile) throws IOException {
        if (progress != null && progress.cancelRequested) {
            throw new CancellationException("ASTRA runtime installation cancelled before Miniforge download.");
        }
        appendLog(logFile, "\n$ download " + url + " -> " + target.getAbsolutePath() + System.lineSeparator());
        URLConnection connection = URI.create(url).toURL().openConnection();
        connection.setConnectTimeout((int) Duration.ofSeconds(30).toMillis());
        connection.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
        try (InputStream stream = connection.getInputStream();
             OutputStream out = Files.newOutputStream(target.toPath())) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = stream.read(buffer)) >= 0) {
                if (progress != null && progress.cancelRequested) {
                    throw new CancellationException("ASTRA runtime installation cancelled during Miniforge download.");
                }
                out.write(buffer, 0, n);
            }
        }
        if (progress != null && progress.cancelRequested) {
            throw new CancellationException("ASTRA runtime installation cancelled after Miniforge download.");
        }
        progressLine(progress, "Downloaded " + target.getAbsolutePath());
    }

    /**
     * Verifies a file against an expected SHA256 digest.
     *
     * @param file file to verify.
     * @param expected expected lowercase SHA256.
     * @throws IOException if hashing fails or the digest differs.
     */
    static void verifySha256(File file, String expected) throws IOException {
        String actual = sha256(file);
        String normalizedExpected = normalizeSha256(expected);
        if (!actual.equalsIgnoreCase(normalizedExpected)) {
            throw new IOException("Miniforge checksum mismatch for " + file.getAbsolutePath()
                    + ": expected " + normalizedExpected + " but got " + actual + ".");
        }
    }

    private static String sha256(File file) throws IOException {
        try (InputStream stream = Files.newInputStream(file.toPath())) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int n;
            while ((n = stream.read(buffer)) >= 0) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this Java runtime.", e);
        }
    }

    private static String normalizeSha256(String value) {
        String normalized = String.valueOf(value == null ? "" : value).trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("sha256:")) {
            normalized = normalized.substring("sha256:".length());
        }
        return normalized;
    }

    private static String normalizeArch(String osArch) {
        String arch = String.valueOf(osArch).trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (arch.equals("amd64") || arch.equals("x64")) {
            return "x86_64";
        }
        if (arch.equals("aarch64")) {
            return "arm64";
        }
        return arch;
    }

    private static Map<String, Object> releaseManifest() {
        try (InputStream stream = RuntimeInstaller.class.getClassLoader().getResourceAsStream(RELEASE_MANIFEST_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled ASTRA release manifest: " + RELEASE_MANIFEST_RESOURCE);
            }
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                Map<String, Object> parsed = GSON.fromJson(reader, MAP_TYPE);
                if (parsed == null) {
                    throw new IllegalStateException("Bundled ASTRA release manifest is empty: " + RELEASE_MANIFEST_RESOURCE);
                }
                return parsed;
            }
        } catch (JsonSyntaxException e) {
            throw new IllegalStateException("Bundled ASTRA release manifest is malformed: " + RELEASE_MANIFEST_RESOURCE, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read bundled ASTRA release manifest: " + RELEASE_MANIFEST_RESOURCE, e);
        }
    }

    private static Map<String, Object> mapValue(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return out;
        }
        return Map.of();
    }

    private static String stringValue(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    /**
     * Tests whether an existing runtime can import required packages.
     *
     * @param python runtime Python executable.
     * @return true when the runtime is present and importable.
     */
    private static boolean isValidRuntime(File python) {
        if (python == null || !python.isFile()) {
            return false;
        }
        try {
            verifyRuntime(python, null, null);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Verifies that the runtime passes all required startup checks before it is
     * accepted: Python executable version, NumPy import/version, torch
     * import/version, Cellpose-ASTRA fork marker, combined import validation,
     * and ASTRA Cellpose startup/version command.
     *
     * @param python runtime Python executable.
     * @throws IOException if any validation command fails.
     * @throws InterruptedException if verification is interrupted.
     */
    private static void verifyRuntime(File python, InstallProgress progress, File logFile) throws IOException, InterruptedException {
        progressStep(progress, "Validating runtime", python.getAbsolutePath());
        for (List<String> command : validationCommands(python)) {
            CommandResult result = runCommand(command, null, Duration.ofMinutes(2), progress, logFile);
            if (result.exitCode() != 0) {
                throw new IOException(formatCommandFailure(command, result));
            }
        }
    }

    /**
     * Updates the QuPath preference and BIOP runtime singleton on the JavaFX thread.
     *
     * @param runtimePythonPath persistent Cellpose runtime Python preference.
     * @param python verified Python executable.
     */
    private static void applyRuntimePath(StringProperty runtimePythonPath, File python) {
        Platform.runLater(() -> {
            String path = python.getAbsolutePath();
            runtimePythonPath.set(path);
            CellposeSetup.getInstance().setCellposePythonPath(path);
        });
    }

    /**
     * Runs a command with the default timeout and requires success.
     *
     * @param command command tokens.
     * @param workingDirectory optional working directory.
     * @throws IOException if the command exits non-zero or cannot start.
     * @throws InterruptedException if interrupted while waiting.
     */
    private static void runCommand(List<String> command, File workingDirectory) throws IOException, InterruptedException {
        CommandResult result = runCommand(command, workingDirectory, Map.of(), COMMAND_TIMEOUT, null, null);
        if (result.exitCode() != 0) {
            throw new IOException(formatCommandFailure(command, result));
        }
    }

    /**
     * Runs a command with progress and persistent log capture.
     *
     * @param command command tokens.
     * @param workingDirectory optional working directory.
     * @param progress progress UI/log sink.
     * @param logFile persistent install log.
     * @throws IOException if the command exits non-zero or cannot start.
     * @throws InterruptedException if interrupted while waiting.
     */
    private static void runCommand(List<String> command, File workingDirectory, InstallProgress progress, File logFile) throws IOException, InterruptedException {
        runCommand(command, workingDirectory, Map.of(), progress, logFile);
    }

    /**
     * Runs a command with explicit environment overrides and fails on nonzero
     * exit.
     *
     * @param command command tokens.
     * @param workingDirectory optional working directory.
     * @param environmentOverrides deterministic environment overrides.
     * @param progress progress UI/log sink.
     * @param logFile persistent install log.
     * @throws IOException if the command fails.
     * @throws InterruptedException if command execution is interrupted.
     */
    private static void runCommand(List<String> command,
                                   File workingDirectory,
                                   Map<String, String> environmentOverrides,
                                   InstallProgress progress,
                                   File logFile) throws IOException, InterruptedException {
        CommandResult result = runCommand(command, workingDirectory, environmentOverrides, COMMAND_TIMEOUT, progress, logFile);
        if (result.exitCode() != 0) {
            throw new IOException(formatCommandFailure(command, result));
        }
    }

    /**
     * Runs a command and captures merged output.
     *
     * @param command command tokens.
     * @param workingDirectory optional working directory.
     * @param timeout maximum run time.
     * @return command result.
     * @throws IOException if the command cannot start.
     * @throws InterruptedException if interrupted while waiting.
     */
    private static CommandResult runCommand(List<String> command, File workingDirectory, Duration timeout, InstallProgress progress, File logFile) throws IOException, InterruptedException {
        return runCommand(command, workingDirectory, Map.of(), timeout, progress, logFile);
    }

    /**
     * Runs a command and captures merged stdout/stderr.
     *
     * @param command command tokens.
     * @param workingDirectory optional working directory.
     * @param environmentOverrides deterministic environment overrides.
     * @param timeout maximum run time.
     * @param progress optional progress sink.
     * @param logFile optional persistent log file.
     * @return command result.
     * @throws IOException if the command cannot start.
     * @throws InterruptedException if interrupted while waiting.
     */
    private static CommandResult runCommand(List<String> command,
                                            File workingDirectory,
                                            Map<String, String> environmentOverrides,
                                            Duration timeout,
                                            InstallProgress progress,
                                            File logFile) throws IOException, InterruptedException {
        if (progress != null && progress.cancelRequested) {
            throw new CancellationException("ASTRA runtime installation cancelled by user before starting command:\n" + String.join(" ", command));
        }
        appendLog(logFile, "\n$ " + String.join(" ", command) + System.lineSeparator());
        if (!environmentOverrides.isEmpty()) {
            appendLog(logFile, "with environment overrides: " + environmentOverrides + System.lineSeparator());
            progressLine(progress, "Using environment overrides: " + environmentOverrides);
        }
        progressLine(progress, "$ " + String.join(" ", command));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.environment().putAll(environmentOverrides);
        if (workingDirectory != null) {
            builder.directory(workingDirectory);
        }

        Process process = builder.start();
        if (progress != null) {
            progress.setCurrentProcess(process);
        }
        StringBuilder output = new StringBuilder();
        try {
            Thread reader = new Thread(() -> readOutput(process, output, progress, logFile), "ASTRA runtime installer output");
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                terminateProcessForCancellation(process, Duration.ofSeconds(2));
                throw new IOException("Command timed out after " + timeout.toMinutes() + " minutes:\n" + String.join(" ", command));
            }
            reader.join(Duration.ofSeconds(2).toMillis());
            if (progress != null && progress.cancelRequested) {
                throw new CancellationException("ASTRA runtime installation cancelled by user after command:\n" + String.join(" ", command));
            }
            return new CommandResult(process.exitValue(), output.toString());
        } finally {
            if (progress != null) {
                progress.clearCurrentProcess(process);
            }
        }
    }

    /**
     * Terminates an active installer process for cancellation.
     *
     * @param process process to stop.
     * @param gracefulWait time to wait after {@link Process#destroy()} before
     * escalating.
     * @return true if a running process was asked to terminate.
     */
    static boolean terminateProcessForCancellation(Process process, Duration gracefulWait) {
        if (process == null || !process.isAlive()) {
            return false;
        }
        process.destroy();
        try {
            if (!process.waitFor(Math.max(1L, gracefulWait.toMillis()), TimeUnit.MILLISECONDS) && process.isAlive()) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        return true;
    }

    /**
     * Reads process output into a bounded diagnostic buffer.
     *
     * @param process running process.
     * @param output destination buffer.
     */
    private static void readOutput(Process process, StringBuilder output, InstallProgress progress, File logFile) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < 20_000) {
                    output.append(line).append(System.lineSeparator());
                }
                appendLog(logFile, line + System.lineSeparator());
                progressLine(progress, line);
            }
        } catch (IOException ignored) {
            // Command output is diagnostic only.
        }
    }

    /**
     * Formats a command failure with bounded output for user-facing diagnostics.
     *
     * @param command failed command.
     * @param result command result.
     * @return diagnostic message.
     */
    static String formatCommandFailure(List<String> command, CommandResult result) {
        return "Command failed with exit code " + result.exitCode() + ":\n" +
                String.join(" ", command) + "\n\nLast output lines:\n" + lastLines(result.output(), 80);
    }

    /**
     * Returns the last lines from command output.
     *
     * @param text command output.
     * @param maxLines maximum line count.
     * @return bounded output tail.
     */
    static String lastLines(String text, int maxLines) {
        String[] lines = String.valueOf(text == null ? "" : text).split("\\R");
        int start = Math.max(0, lines.length - Math.max(1, maxLines));
        return String.join(System.lineSeparator(), java.util.Arrays.copyOfRange(lines, start, lines.length));
    }

    /**
     * Appends text to the persistent install log.
     *
     * @param logFile log file, or null when logging is unavailable.
     * @param text text to append.
     */
    private static void appendLog(File logFile, String text) {
        if (logFile == null) {
            return;
        }
        try {
            Files.createDirectories(logFile.getParentFile().toPath());
            try (FileWriter writer = new FileWriter(logFile, true)) {
                writer.write(text);
            }
        } catch (IOException ignored) {
            // Persistent install logging is diagnostic-only and must not mask the real command failure.
        }
    }

    /**
     * Sends one progress line when a progress sink is available.
     *
     * @param progress progress sink.
     * @param line line to display.
     */
    private static void progressLine(InstallProgress progress, String line) {
        if (progress != null) {
            progress.line(line);
        }
    }

    /**
     * Sends one progress step when a progress sink is available.
     *
     * @param progress progress sink.
     * @param label step label.
     * @param detail step detail.
     */
    private static void progressStep(InstallProgress progress, String label, String detail) {
        if (progress != null) {
            progress.step(label, detail);
        }
    }

    /**
     * Shows a concise successful runtime setup result on the JavaFX thread.
     *
     * @param title dialog title.
     * @param outcome classified successful result.
     */
    private static void showOutcome(String title, RuntimeSetupOutcome outcome) {
        Platform.runLater(() -> PipelineLauncher.showAstraMessage(
                null,
                title,
                outcome.title(),
                outcome.heading() + "\n\n" + outcome.body()));
    }

    /**
     * Shows a classified runtime setup failure on the JavaFX thread.
     *
     * @param title dialog title.
     * @param failure classified failure.
     */
    private static void showFailure(String title, RuntimeSetupFailure failure) {
        Platform.runLater(() -> PipelineLauncher.showAstraErrorMessage(
                null,
                title,
                failure.title() + "\n\n" + failure.heading() + "\n\n" + failure.body()));
    }

    /**
     * Detects Windows path conventions.
     *
     * @return true on Windows.
     */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Captured command result.
     *
     * @param exitCode process exit code.
     * @param output merged stdout/stderr output.
     */
    record CommandResult(int exitCode, String output) {
    }

    /**
     * Release-pinned Miniforge installer metadata.
     *
     * @param version Miniforge release tag.
     * @param platformKey normalized platform key.
     * @param installerType installer command family.
     * @param fileName local download file name.
     * @param url download URL.
     * @param sha256 installer SHA256.
     * @param condaExecutable relative conda executable path under install root.
     */
    record MiniforgeInstaller(String version,
                              String platformKey,
                              String installerType,
                              String fileName,
                              String url,
                              String sha256,
                              String condaExecutable) {
        MiniforgeInstaller {
            if (version == null || version.isBlank()
                    || platformKey == null || platformKey.isBlank()
                    || installerType == null || installerType.isBlank()
                    || fileName == null || fileName.isBlank()
                    || url == null || url.isBlank()
                    || sha256 == null || sha256.isBlank()
                    || condaExecutable == null || condaExecutable.isBlank()) {
                throw new IllegalStateException("Incomplete Miniforge installer metadata for platform " + platformKey + ".");
            }
        }
    }

    /**
     * ASTRA-owned runtime setup progress window.
     */
    private static final class InstallProgress {
        private final long started = System.currentTimeMillis();
        private final Stage stage;
        private final Label phase;
        private final Label detail;
        private final Label elapsed;
        private final Label resultTitle;
        private final Label resultBody;
        private final ProgressBar progressBar;
        private final TextArea log;
        private final Button cancel;
        private final Timeline elapsedTimeline;
        private volatile boolean cancelRequested;
        private volatile Process currentProcess;

        /**
         * Creates a progress sink.
         *
         * @param stage dialog stage.
         * @param phase current phase label.
         * @param detail current detail label.
         * @param elapsed elapsed time label.
         * @param resultTitle status-card title.
         * @param resultBody status-card body.
         * @param progressBar indeterminate setup progress lane.
         * @param cancel cancel button.
         * @param log scrolling log view.
         */
        private InstallProgress(Stage stage,
                                Label phase,
                                Label detail,
                                Label elapsed,
                                Label resultTitle,
                                Label resultBody,
                                ProgressBar progressBar,
                                Button cancel,
                                TextArea log) {
            this.stage = stage;
            this.phase = phase;
            this.detail = detail;
            this.elapsed = elapsed;
            this.resultTitle = resultTitle;
            this.resultBody = resultBody;
            this.progressBar = progressBar;
            this.cancel = cancel;
            this.log = log;
            this.elapsedTimeline = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1.0), event -> refreshElapsed()));
            this.elapsedTimeline.setCycleCount(Animation.INDEFINITE);
        }

        /**
         * Shows the progress dialog.
         *
         * @return progress sink.
         */
        static InstallProgress show() {
            Stage stage = new Stage();
            Label phase = new Label("Preparing runtime setup");
            Label detail = new Label("ASTRA is preparing the managed Cellpose runtime workflow.");
            Label elapsed = new Label("Elapsed 0s");
            Label stepList = new Label("Steps: validate managed runtime -> repair if needed -> install pinned packages -> validate final runtime -> register with QuPath.");
            Label resultTitle = new Label("Runtime setup pending");
            Label resultBody = new Label("Validation, repair, installation, and registration messages will appear here.");
            ProgressBar progressBar = new ProgressBar();
            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            progressBar.setMinHeight(InstallerGeometry.PROGRESS_BAR_HEIGHT);
            progressBar.setPrefHeight(InstallerGeometry.PROGRESS_BAR_HEIGHT);
            TextArea log = new TextArea();
            log.setEditable(false);
            log.setWrapText(false);
            Button cancel = new Button("Cancel");
            Button copyLog = new Button("Copy log");
            TitledPane logPane = new TitledPane("Technical install log", log);
            logPane.setExpanded(false);
            InstallProgress progress = new InstallProgress(stage, phase, detail, elapsed, resultTitle, resultBody, progressBar, cancel, log);
            cancel.setOnAction(event -> {
                progress.requestCancel();
                cancel.setDisable(true);
            });
            copyLog.setOnAction(event -> {
                ClipboardContent content = new ClipboardContent();
                content.putString(log.getText());
                Clipboard.getSystemClipboard().setContent(content);
            });
            VBox root = createInstallProgressRoot(phase, detail, elapsed, stepList, resultTitle, resultBody,
                    progressBar, cancel, copyLog, logPane, log);
            stage.setTitle("ASTRA Runtime Setup");
            Scene scene = new Scene(root, InstallerGeometry.WINDOW_WIDTH, InstallerGeometry.WINDOW_HEIGHT);
            addAstraStylesheet(scene);
            stage.setScene(scene);
            stage.show();
            progress.elapsedTimeline.play();
            return progress;
        }

        /**
         * Records a step.
         *
         * @param label step label.
         * @param detail step detail.
         */
        void step(String label, String detail) {
            line("\n== " + label + " ==\n" + detail);
            Platform.runLater(() -> {
                phase.setText(label);
                this.detail.setText(detail);
                resultTitle.setText("Working");
                resultBody.setText("ASTRA is running: " + label);
                refreshElapsed();
            });
        }

        /**
         * Records a log line.
         *
         * @param text text to append.
         */
        void line(String text) {
            Platform.runLater(() -> log.appendText(text + System.lineSeparator()));
        }

        /**
         * Tracks the currently running installer process so cancellation can
         * interrupt long conda/pip commands.
         *
         * @param process active process.
         */
        void setCurrentProcess(Process process) {
            currentProcess = process;
            if (cancelRequested) {
                terminateProcessForCancellation(process, Duration.ofSeconds(2));
            }
        }

        /**
         * Clears the current process if it matches the completed command.
         *
         * @param process process that completed.
         */
        void clearCurrentProcess(Process process) {
            if (currentProcess == process) {
                currentProcess = null;
            }
        }

        /**
         * Requests cancellation and immediately terminates any active command.
         */
        void requestCancel() {
            cancelRequested = true;
            line("Cancellation requested. Stopping the active install command.");
            Platform.runLater(() -> {
                phase.setText("Cancelling runtime setup");
                detail.setText("ASTRA is stopping the active command.");
                resultTitle.setText(RuntimeFailureKind.CANCELLED.title);
                resultBody.setText(RuntimeFailureKind.CANCELLED.nextAction);
            });
            Process process = currentProcess;
            if (terminateProcessForCancellation(process, Duration.ofSeconds(2))) {
                line("Active install command was asked to terminate.");
            }
        }

        /**
         * Records successful completion.
         *
         * @param outcome completion result.
         */
        void done(RuntimeSetupOutcome outcome) {
            line("\nSUCCESS\n" + outcome.title() + "\n" + outcome.body());
            Platform.runLater(() -> {
                elapsedTimeline.stop();
                refreshElapsed();
                phase.setText(outcome.title());
                detail.setText(outcome.heading());
                resultTitle.setText(outcome.title());
                resultBody.setText(outcome.heading() + "\n" + outcome.kind.nextAction);
                progressBar.setProgress(1.0);
                cancel.setDisable(true);
            });
        }

        /**
         * Records failed completion.
         *
         * @param failure classified failure.
         */
        void failed(RuntimeSetupFailure failure) {
            line("\nFAILED\n" + failure.title() + "\n" + failure.body());
            Platform.runLater(() -> {
                elapsedTimeline.stop();
                refreshElapsed();
                phase.setText(failure.title());
                detail.setText(failure.heading());
                resultTitle.setText(failure.title());
                resultBody.setText(failure.heading() + "\n" + failure.kind.nextAction);
                progressBar.setProgress(0.0);
                cancel.setDisable(true);
            });
        }

        /**
         * Returns elapsed seconds.
         *
         * @return elapsed seconds.
         */
        private long elapsedSeconds() {
            return Math.max(0L, (System.currentTimeMillis() - started) / 1000L);
        }

        private void refreshElapsed() {
            elapsed.setText("Elapsed " + elapsedSeconds() + "s");
        }
    }

    static VBox createInstallProgressRootForTesting() {
        Label phase = new Label("Preparing runtime setup");
        Label detail = new Label("ASTRA is preparing the managed Cellpose runtime workflow.");
        Label elapsed = new Label("Elapsed 0s");
        Label stepList = new Label("Steps: validate managed runtime -> repair if needed -> install pinned packages -> validate final runtime -> register with QuPath.");
        Label resultTitle = new Label("Runtime setup pending");
        Label resultBody = new Label("Validation, repair, installation, and registration messages will appear here.");
        ProgressBar progressBar = new ProgressBar();
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progressBar.setMinHeight(InstallerGeometry.PROGRESS_BAR_HEIGHT);
        progressBar.setPrefHeight(InstallerGeometry.PROGRESS_BAR_HEIGHT);
        Button cancel = new Button("Cancel");
        Button copyLog = new Button("Copy log");
        TextArea log = new TextArea("Runtime installer diagnostic log.\nWaiting for commands...");
        log.setEditable(false);
        log.setWrapText(false);
        TitledPane logPane = new TitledPane("Technical install log", log);
        return createInstallProgressRoot(phase, detail, elapsed, stepList, resultTitle, resultBody,
                progressBar, cancel, copyLog, logPane, log);
    }

    static double installerRootPaddingForTesting() {
        return InstallerGeometry.ROOT_PADDING;
    }

    static double installerHeaderRowGapForTesting() {
        return InstallerGeometry.HEADER_ROW_GAP;
    }

    static double installerRootContentGapForTesting() {
        return InstallerGeometry.ROOT_CONTENT_GAP;
    }

    static double installerWindowWidthForTesting() {
        return InstallerGeometry.WINDOW_WIDTH;
    }

    static double installerWindowHeightForTesting() {
        return InstallerGeometry.WINDOW_HEIGHT;
    }

    static double installerProgressBarHeightForTesting() {
        return InstallerGeometry.PROGRESS_BAR_HEIGHT;
    }

    private static VBox createInstallProgressRoot(Label phase,
                                                  Label detail,
                                                  Label elapsed,
                                                  Label stepList,
                                                  Label resultTitle,
                                                  Label resultBody,
                                                  ProgressBar progressBar,
                                                  Button cancel,
                                                  Button copyLog,
                                                  TitledPane logPane,
                                                  TextArea log) {
        phase.getStyleClass().add("astra-runtime-installer-phase");
        detail.getStyleClass().add("astra-runtime-installer-detail");
        detail.setWrapText(true);
        elapsed.getStyleClass().add("astra-runtime-installer-elapsed");
        stepList.getStyleClass().add("astra-runtime-installer-step-list");
        stepList.setWrapText(true);
        stepList.setPadding(new Insets(InstallerGeometry.STATUS_CARD_PADDING));
        stepList.setMaxWidth(Double.MAX_VALUE);
        resultTitle.getStyleClass().add("astra-runtime-installer-result-title");
        resultBody.getStyleClass().add("astra-runtime-installer-result-body");
        resultBody.setWrapText(true);
        progressBar.getStyleClass().add("astra-runtime-installer-progress");
        progressBar.setMaxWidth(Double.MAX_VALUE);
        cancel.getStyleClass().add("astra-button");
        cancel.getStyleClass().add("astra-button-secondary");
        cancel.getStyleClass().add("astra-runtime-installer-cancel");
        copyLog.getStyleClass().add("astra-button");
        copyLog.getStyleClass().add("astra-button-small");
        copyLog.getStyleClass().add("astra-runtime-installer-copy");
        log.getStyleClass().add("astra-runtime-installer-log");
        logPane.getStyleClass().add("astra-runtime-installer-log-pane");

        VBox titleBlock = new VBox(InstallerGeometry.STATUS_CARD_GAP, phase, detail);
        titleBlock.getStyleClass().add("astra-runtime-installer-title-block");
        HBox header = new HBox(InstallerGeometry.HEADER_ROW_GAP, titleBlock, elapsed);
        header.getStyleClass().add("astra-runtime-installer-header");
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleBlock, Priority.ALWAYS);

        VBox statusCard = new VBox(InstallerGeometry.STATUS_CARD_GAP, resultTitle, resultBody);
        statusCard.getStyleClass().add("astra-runtime-installer-status-card");
        statusCard.setPadding(new Insets(InstallerGeometry.STATUS_CARD_PADDING));

        HBox actions = new HBox(InstallerGeometry.HEADER_ROW_GAP, copyLog, cancel);
        actions.getStyleClass().add("astra-runtime-installer-actions");
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(InstallerGeometry.ROOT_CONTENT_GAP, header, stepList, progressBar, statusCard, logPane, actions);
        root.getStyleClass().add("astra-runtime-installer-root");
        root.setPadding(new Insets(InstallerGeometry.ROOT_PADDING));
        VBox.setVgrow(logPane, Priority.ALWAYS);
        return root;
    }

    private static void addAstraStylesheet(Scene scene) {
        var resource = PipelineLauncher.class.getResource("/qupath/ext/astra/astra-launcher.css");
        if (resource != null) {
            scene.getStylesheets().add(resource.toExternalForm());
        }
    }
}
