package qupath.ext.astra;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import javafx.application.Platform;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
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
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/**
 * Installs and registers the ASTRA Python runtime used by Cellpose-SAM.
 *
 * <p>The installer is intentionally ASTRA-specific and leaves upstream BIOP
 * setup untouched. It creates a deterministic user-local conda-prefix
 * environment, installs the ASTRA Cellpose fork, validates imports/startup, and
 * then writes the extension preference consumed by {@link CellposeSetup}.</p>
 *
 * <p>Conda/miniforge is the default path. The venv installer is available only
 * when explicitly requested with {@code ASTRA_RUNTIME_INSTALL_STRATEGY=venv};
 * it is not used silently when conda is missing.</p>
 */
final class RuntimeInstaller {

    static final String ASTRA_CELLPOSE_REPO = "https://github.com/jdsuh28/cellpose-astra.git";
    static final String DEFAULT_CELLPOSE_REF = "v4.1.1+astra.1";
    static final String ENVIRONMENT_NAME = "cellpose-astra";
    static final String PYTHON_VERSION = "3.10";

    private static final String RUNTIME_FOLDER_NAME = ENVIRONMENT_NAME;
    private static final String RELEASE_PROPERTIES_RESOURCE = "qupath/ext/astra/release/runtime.properties";
    private static final String RELEASE_MANIFEST_RESOURCE = "astra/rulebook/manifests/release.json";
    private static final String MINIFORGE_FOLDER_NAME = "miniforge";
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(45);
    private static final Duration BOOTSTRAP_TIMEOUT = Duration.ofMinutes(20);
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {}.getType();

    private static final class InstallerGeometry {
        private static final double ROOT_PADDING =
                LauncherGeometryTokens.INTRA_PANEL_MARGIN;
        private static final double HEADER_ROW_GAP =
                LauncherGeometryTokens.LAYOUT_UNIT * 5.0 / 12.0;
        private static final double ROOT_CONTENT_GAP =
                HEADER_ROW_GAP;
        private static final double WINDOW_WIDTH =
                LauncherGeometryTokens.LAYOUT_UNIT * 65.0 / 2.0;
        private static final double WINDOW_HEIGHT =
                LauncherGeometryTokens.LAYOUT_UNIT * 35.0 / 2.0;

        private InstallerGeometry() {
        }
    }

    private RuntimeInstaller() {
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

            if (!isValidRuntime(python)) {
                Files.createDirectories(runtimeDirectory.getParentFile().toPath());
                installRuntime(runtimeDirectory, python, progress, logFile);
            }

            verifyRuntime(python, progress, logFile);
            applyRuntimePath(runtimePythonPath, python);
            progress.done("ASTRA runtime is ready:\n" + python.getAbsolutePath() + "\n\nInstall log:\n" + logFile.getAbsolutePath());
            showInfo("ASTRA Runtime Setup", "ASTRA runtime is ready:\n" + python.getAbsolutePath());
        } catch (Throwable t) {
            progress.failed(t, logFile);
            showError("ASTRA Runtime Setup", t);
        }
    }

    /**
     * Installs the ASTRA runtime by the explicitly selected strategy.
     *
     * @param runtimeDirectory deterministic runtime directory.
     * @param python runtime Python executable.
     * @param progress progress UI/log sink.
     * @param logFile persistent install log.
     * @throws IOException if installation fails.
     * @throws InterruptedException if command execution is interrupted.
     */
    private static void installRuntime(File runtimeDirectory, File python, InstallProgress progress, File logFile) throws IOException, InterruptedException {
        String strategy = System.getenv("ASTRA_RUNTIME_INSTALL_STRATEGY");
        if (strategy != null && strategy.trim().equalsIgnoreCase("venv")) {
            progress.step("Creating explicit advanced venv runtime", runtimeDirectory.getAbsolutePath());
            List<String> seedPython = findSeedPython();
            runCommand(seedPythonWithArgs(seedPython, "-m", "venv", runtimeDirectory.getAbsolutePath()), null, progress, logFile);
        } else {
            String conda = findOrBootstrapCondaExecutable(progress, logFile);
            progress.step("Creating conda runtime", String.join(" ", condaCreateCommand(conda, runtimeDirectory)));
            runCommand(condaCreateCommand(conda, runtimeDirectory), null, progress, logFile);
        }
        progress.step("Upgrading Python packaging tools", python.getAbsolutePath());
        runCommand(List.of(python.getAbsolutePath(), "-m", "pip", "install", "--upgrade", "pip", "setuptools", "wheel"), null, progress, logFile);
        progress.step("Installing Cellpose-ASTRA", cellposePackageSpec());
        runCommand(List.of(python.getAbsolutePath(), "-m", "pip", "install", "--upgrade", cellposePackageSpec()), null, progress, logFile);
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
        try (var stream = RuntimeInstaller.class.getClassLoader().getResourceAsStream(RELEASE_PROPERTIES_RESOURCE)) {
            if (stream == null) {
                return DEFAULT_CELLPOSE_REF;
            }
            java.util.Properties properties = new java.util.Properties();
            properties.load(stream);
            String ref = properties.getProperty("cellpose_astra_ref", "").trim();
            return ref.isEmpty() ? DEFAULT_CELLPOSE_REF : ref;
        } catch (IOException ignored) {
            return DEFAULT_CELLPOSE_REF;
        }
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
        return List.of(condaExecutable, "create", "-y", "-p", runtimeDirectory.getAbsolutePath(), "python=" + PYTHON_VERSION);
    }

    /**
     * Builds validation commands that must pass before the runtime is accepted.
     *
     * @param python runtime Python executable.
     * @return validation command list.
     */
    static List<List<String>> validationCommands(File python) {
        String py = python.getAbsolutePath();
        return List.of(
                List.of(py, "--version"),
                List.of(py, "-c", "import numpy; print('numpy', numpy.__version__)"),
                List.of(py, "-c", "import torch; print('torch', torch.__version__)"),
                List.of(py, "-c", "import cellpose; from cellpose.version import version_str; assert 'astra' in version_str.lower(), version_str; print('cellpose', version_str)"),
                List.of(py, "-c", "import cellpose, cellpose.astra, torch, numpy; print('ASTRA runtime import validation OK')"),
                List.of(py, "-m", "cellpose.astra", "--version")
        );
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
                "The venv installer is available only when ASTRA_RUNTIME_INSTALL_STRATEGY=venv is set explicitly.");
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
     * Finds a system Python capable of creating the ASTRA virtual environment.
     *
     * @return command prefix for the selected Python interpreter.
     * @throws IOException if no usable Python command is found.
     * @throws InterruptedException if command probing is interrupted.
     */
    static List<String> findSeedPython() throws IOException, InterruptedException {
        List<List<String>> candidates = new ArrayList<>();
        String override = System.getenv("ASTRA_PYTHON_BOOTSTRAP");
        if (override != null && !override.isBlank()) {
            candidates.add(List.of(override.trim()));
        }
        if (isWindows()) {
            candidates.add(List.of("py", "-3"));
        }
        candidates.add(List.of("python3"));
        candidates.add(List.of("python"));

        for (List<String> candidate : candidates) {
            try {
                CommandResult result = runCommand(seedPythonWithArgs(candidate, "-c", "import venv"), null, Duration.ofSeconds(20), null, null);
                if (result.exitCode() == 0) {
                    return candidate;
                }
            } catch (IOException ignored) {
                // Try the next Python candidate.
            }
        }

        throw new IOException("No Python executable with the standard venv module was found. " +
                "Install Python 3.10 or newer, or set ASTRA_PYTHON_BOOTSTRAP to a Python executable before retrying.");
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
            runCommand(command, null, Duration.ofMinutes(2), progress, logFile);
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
     * Appends arguments to a Python command prefix.
     *
     * @param seedPython command prefix.
     * @param args command arguments.
     * @return full command list.
     */
    private static List<String> seedPythonWithArgs(List<String> seedPython, String... args) {
        List<String> command = new ArrayList<>(seedPython);
        command.addAll(List.of(args));
        return command;
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
        CommandResult result = runCommand(command, workingDirectory, COMMAND_TIMEOUT, null, null);
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
        CommandResult result = runCommand(command, workingDirectory, COMMAND_TIMEOUT, progress, logFile);
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
        if (progress != null && progress.cancelRequested) {
            throw new CancellationException("ASTRA runtime installation cancelled by user before starting command:\n" + String.join(" ", command));
        }
        appendLog(logFile, "\n$ " + String.join(" ", command) + System.lineSeparator());
        progressLine(progress, "$ " + String.join(" ", command));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
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
     * Shows an informational dialog on the JavaFX thread.
     *
     * @param title dialog title.
     * @param message dialog message.
     */
    private static void showInfo(String title, String message) {
        Platform.runLater(() -> PipelineLauncher.showAstraMessage(
                null,
                title,
                "ASTRA runtime is ready.",
                message));
    }

    /**
     * Shows an error dialog on the JavaFX thread.
     *
     * @param title dialog title.
     * @param throwable failure to display.
     */
    private static void showError(String title, Throwable throwable) {
        Platform.runLater(() -> PipelineLauncher.showAstraErrorMessage(
                null,
                title,
                throwable == null ? "Unknown runtime installer failure." : throwable.toString()));
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
     * Minimal live progress dialog for runtime installation.
     */
    private static final class InstallProgress {
        private final long started = System.currentTimeMillis();
        private final Stage stage;
        private final Label step;
        private final TextArea log;
        private volatile boolean cancelRequested;
        private volatile Process currentProcess;

        /**
         * Creates a progress sink.
         *
         * @param stage dialog stage.
         * @param step current-step label.
         * @param log scrolling log view.
         */
        private InstallProgress(Stage stage, Label step, TextArea log) {
            this.stage = stage;
            this.step = step;
            this.log = log;
        }

        /**
         * Shows the progress dialog.
         *
         * @return progress sink.
         */
        static InstallProgress show() {
            Stage stage = new Stage();
            Label step = new Label("Starting ASTRA runtime setup...");
            TextArea log = new TextArea();
            log.setEditable(false);
            log.setWrapText(false);
            ProgressIndicator indicator = new ProgressIndicator();
            Button cancel = new Button("Cancel");
            InstallProgress progress = new InstallProgress(stage, step, log);
            cancel.setOnAction(event -> {
                progress.requestCancel();
                cancel.setDisable(true);
            });
            VBox root = createInstallProgressRoot(indicator, step, cancel, log);
            stage.setTitle("ASTRA Runtime Setup");
            Scene scene = new Scene(root, InstallerGeometry.WINDOW_WIDTH, InstallerGeometry.WINDOW_HEIGHT);
            addAstraStylesheet(scene);
            stage.setScene(scene);
            stage.show();
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
            Platform.runLater(() -> step.setText(label + " (" + elapsedSeconds() + "s)"));
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
            Process process = currentProcess;
            if (terminateProcessForCancellation(process, Duration.ofSeconds(2))) {
                line("Active install command was asked to terminate.");
            }
        }

        /**
         * Records successful completion.
         *
         * @param message completion message.
         */
        void done(String message) {
            line("\nSUCCESS\n" + message);
            Platform.runLater(() -> step.setText("Runtime setup complete"));
        }

        /**
         * Records failed completion.
         *
         * @param throwable failure.
         * @param logFile persistent log file.
         */
        void failed(Throwable throwable, File logFile) {
            line("\nFAILED\n" + throwable.getMessage() + "\nInstall log: " + logFile.getAbsolutePath());
            Platform.runLater(() -> step.setText("Runtime setup failed"));
        }

        /**
         * Returns elapsed seconds.
         *
         * @return elapsed seconds.
         */
        private long elapsedSeconds() {
            return Math.max(0L, (System.currentTimeMillis() - started) / 1000L);
        }
    }

    static VBox createInstallProgressRootForTesting() {
        ProgressIndicator indicator = new ProgressIndicator();
        Label step = new Label("Starting ASTRA runtime setup...");
        Button cancel = new Button("Cancel");
        TextArea log = new TextArea("Runtime installer diagnostic log.\nWaiting for commands...");
        log.setEditable(false);
        log.setWrapText(false);
        return createInstallProgressRoot(indicator, step, cancel, log);
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

    private static VBox createInstallProgressRoot(ProgressIndicator indicator,
                                                  Label step,
                                                  Button cancel,
                                                  TextArea log) {
        indicator.getStyleClass().add("astra-runtime-installer-indicator");
        step.getStyleClass().add("astra-runtime-installer-step");
        cancel.getStyleClass().add("astra-button");
        cancel.getStyleClass().add("astra-button-secondary");
        cancel.getStyleClass().add("astra-runtime-installer-cancel");
        log.getStyleClass().add("astra-runtime-installer-log");
        HBox top = new HBox(InstallerGeometry.HEADER_ROW_GAP, indicator, step, cancel);
        top.getStyleClass().add("astra-runtime-installer-header");
        VBox root = new VBox(InstallerGeometry.ROOT_CONTENT_GAP, top, log);
        root.getStyleClass().add("astra-runtime-installer-root");
        root.setPadding(new Insets(InstallerGeometry.ROOT_PADDING));
        VBox.setVgrow(log, Priority.ALWAYS);
        return root;
    }

    private static void addAstraStylesheet(Scene scene) {
        var resource = PipelineLauncher.class.getResource("/qupath/ext/astra/astra-launcher.css");
        if (resource != null) {
            scene.getStylesheets().add(resource.toExternalForm());
        }
    }
}
