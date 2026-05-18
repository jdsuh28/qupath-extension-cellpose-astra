package qupath.ext.astra;

import javafx.application.Platform;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import qupath.ext.biop.cellpose.CellposeSetup;
import qupath.fx.dialogs.Dialogs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
final class AstraRuntimeInstaller {

    static final String ASTRA_CELLPOSE_REPO = "https://github.com/jdsuh28/cellpose-astra.git";
    static final String DEFAULT_CELLPOSE_REF = "v4.0.8+astra.2";
    static final String ENVIRONMENT_NAME = "cellpose-astra";
    static final String PYTHON_VERSION = "3.10";

    private static final String RUNTIME_FOLDER_NAME = ENVIRONMENT_NAME;
    private static final String RELEASE_PROPERTIES_RESOURCE = "qupath/ext/astra/release/runtime.properties";
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(45);

    private AstraRuntimeInstaller() {
    }

    /**
     * Starts an asynchronous install-or-repair run from the QuPath menu.
     *
     * @param runtimePythonPath persistent ASTRA runtime Python preference.
     */
    static void installOrRepairAsync(StringProperty runtimePythonPath) {
        Objects.requireNonNull(runtimePythonPath, "runtimePythonPath");
        boolean proceed = Dialogs.showYesNoDialog(
                "ASTRA Runtime Setup",
                "ASTRA will create or repair a local Python runtime, install Cellpose-ASTRA, " +
                        "verify the installation, and register it with QuPath.\n\n" +
                        "This can take several minutes and requires internet access the first time.\n\n" +
                        "Continue?");
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
     * @param runtimePythonPath persistent ASTRA runtime Python preference.
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
            String conda = findCondaExecutable();
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
        try (var stream = AstraRuntimeInstaller.class.getClassLoader().getResourceAsStream(RELEASE_PROPERTIES_RESOURCE)) {
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
                List.of(py, "-c", "import cellpose, torch, numpy; print('ASTRA runtime import validation OK')"),
                List.of(py, "-m", "cellpose", "--version")
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
        throw new IOException("No conda-compatible executable was found. Install Miniforge/conda or set ASTRA_CONDA. " +
                "The venv installer is available only when ASTRA_RUNTIME_INSTALL_STRATEGY=venv is set explicitly.");
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
     * Verifies that the runtime imports Cellpose, torch, and segment-anything.
     *
     * @param python runtime Python executable.
     * @throws IOException if verification fails.
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
     * @param runtimePythonPath persistent ASTRA runtime Python preference.
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
            throw new IOException("ASTRA runtime setup was cancelled before starting command:\n" + String.join(" ", command));
        }
        appendLog(logFile, "\n$ " + String.join(" ", command) + System.lineSeparator());
        progressLine(progress, "$ " + String.join(" ", command));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        if (workingDirectory != null) {
            builder.directory(workingDirectory);
        }

        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> readOutput(process, output, progress, logFile), "ASTRA runtime installer output");
        reader.setDaemon(true);
        reader.start();

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Command timed out after " + timeout.toMinutes() + " minutes:\n" + String.join(" ", command));
        }
        reader.join(Duration.ofSeconds(2).toMillis());
        if (progress != null && progress.cancelRequested) {
            throw new IOException("ASTRA runtime setup was cancelled after command:\n" + String.join(" ", command));
        }
        return new CommandResult(process.exitValue(), output.toString());
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
        Platform.runLater(() -> Dialogs.showPlainMessage(title, message));
    }

    /**
     * Shows an error dialog on the JavaFX thread.
     *
     * @param title dialog title.
     * @param throwable failure to display.
     */
    private static void showError(String title, Throwable throwable) {
        Platform.runLater(() -> Dialogs.showErrorMessage(title, throwable));
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
     * Minimal live progress dialog for runtime installation.
     */
    private static final class InstallProgress {
        private final long started = System.currentTimeMillis();
        private final Stage stage;
        private final Label step;
        private final TextArea log;
        private volatile boolean cancelRequested;

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
                progress.cancelRequested = true;
                progress.line("Cancellation requested. The current package operation may finish before setup stops.");
                cancel.setDisable(true);
            });
            HBox top = new HBox(10, indicator, step, cancel);
            VBox root = new VBox(10, top, log);
            root.setPadding(new Insets(12));
            VBox.setVgrow(log, Priority.ALWAYS);
            stage.setTitle("ASTRA Runtime Setup");
            stage.setScene(new Scene(root, 780, 420));
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
}
