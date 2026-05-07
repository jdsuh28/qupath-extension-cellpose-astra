package qupath.ext.astra;

import javafx.application.Platform;
import javafx.beans.property.StringProperty;
import qupath.ext.biop.cellpose.CellposeSetup;
import qupath.fx.dialogs.Dialogs;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * setup untouched. It creates a deterministic user-local virtual environment,
 * installs the ASTRA Cellpose fork, verifies that the runtime can import the
 * required packages, and then writes the extension preference consumed by
 * {@link CellposeSetup}.</p>
 *
 * <p>Python installation itself is out of scope. If no system Python can create
 * a virtual environment, the installer fails with a clear message rather than
 * silently falling back to a partial runtime.</p>
 */
final class AstraRuntimeInstaller {

    static final String ASTRA_CELLPOSE_REPO = "https://github.com/jdsuh28/cellpose-astra.git";
    static final String DEFAULT_CELLPOSE_REF = "v4.0.8+astra.2";

    private static final String RUNTIME_FOLDER_NAME = "cellpose-runtime";
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

        Dialogs.showInfoNotification("ASTRA Runtime Setup", "Runtime setup started. QuPath may remain busy while Python packages install.");
        Thread worker = new Thread(() -> installOrRepair(runtimePythonPath), "ASTRA runtime installer");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Performs the runtime installation and preference update.
     *
     * @param runtimePythonPath persistent ASTRA runtime Python preference.
     */
    private static void installOrRepair(StringProperty runtimePythonPath) {
        try {
            File runtimeDirectory = runtimeDirectory();
            File python = runtimePythonExecutable(runtimeDirectory);

            if (!isValidRuntime(python)) {
                Files.createDirectories(runtimeDirectory.toPath());
                List<String> seedPython = findSeedPython();
                runCommand(seedPythonWithArgs(seedPython, "-m", "venv", runtimeDirectory.getAbsolutePath()), null);
                runCommand(List.of(python.getAbsolutePath(), "-m", "pip", "install", "--upgrade", "pip", "setuptools", "wheel"), null);
                runCommand(List.of(python.getAbsolutePath(), "-m", "pip", "install", "--upgrade", cellposePackageSpec()), null);
            }

            verifyRuntime(python);
            applyRuntimePath(runtimePythonPath, python);
            showInfo("ASTRA Runtime Setup", "ASTRA runtime is ready:\n" + python.getAbsolutePath());
        } catch (Throwable t) {
            showError("ASTRA Runtime Setup", t);
        }
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
                CommandResult result = runCommand(seedPythonWithArgs(candidate, "-c", "import venv"), null, Duration.ofSeconds(20));
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
            verifyRuntime(python);
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
    private static void verifyRuntime(File python) throws IOException, InterruptedException {
        runCommand(List.of(
                python.getAbsolutePath(),
                "-c",
                "import cellpose, torch, segment_anything; print('ASTRA runtime OK')"
        ), null, Duration.ofMinutes(2));
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
        CommandResult result = runCommand(command, workingDirectory, COMMAND_TIMEOUT);
        if (result.exitCode() != 0) {
            throw new IOException("Command failed with exit code " + result.exitCode() + ":\n" +
                    String.join(" ", command) + "\n\n" + result.output());
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
    private static CommandResult runCommand(List<String> command, File workingDirectory, Duration timeout) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        if (workingDirectory != null) {
            builder.directory(workingDirectory);
        }

        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> readOutput(process, output), "ASTRA runtime installer output");
        reader.setDaemon(true);
        reader.start();

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Command timed out after " + timeout.toMinutes() + " minutes:\n" + String.join(" ", command));
        }
        reader.join(Duration.ofSeconds(2).toMillis());
        return new CommandResult(process.exitValue(), output.toString());
    }

    /**
     * Reads process output into a bounded diagnostic buffer.
     *
     * @param process running process.
     * @param output destination buffer.
     */
    private static void readOutput(Process process, StringBuilder output) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < 20_000) {
                    output.append(line).append(System.lineSeparator());
                }
            }
        } catch (IOException ignored) {
            // Command output is diagnostic only.
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
}
