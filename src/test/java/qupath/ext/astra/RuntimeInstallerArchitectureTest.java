package qupath.ext.astra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeInstallerArchitectureTest {
    @Test
    void appleSiliconCondaCreationTargetsArm64() {
        assertEquals(Map.of(
                        RuntimeInstaller.CONDA_OVERRIDE_OSX, RuntimeInstaller.MACOS_CONDA_SOLVER_VERSION,
                        "CONDA_SUBDIR", "osx-arm64"),
                RuntimeInstaller.condaCreateEnvironmentOverrides("Mac OS X", true));
        assertEquals(Map.of(
                        RuntimeInstaller.CONDA_OVERRIDE_OSX, RuntimeInstaller.MACOS_CONDA_SOLVER_VERSION),
                RuntimeInstaller.condaCreateEnvironmentOverrides("Mac OS X", false));
    }

    @Test
    void appleSiliconPrefersBundledMambaForCrossArchitectureSolve(@TempDir Path dir) throws Exception {
        Path conda = dir.resolve("conda");
        Path mamba = dir.resolve("mamba");
        Files.writeString(conda, "#!/bin/sh\necho conda 26\n");
        Files.writeString(mamba, "#!/bin/sh\necho mamba 2\n");
        conda.toFile().setExecutable(true);
        mamba.toFile().setExecutable(true);
        assertEquals(mamba.toString(), RuntimeInstaller.condaExecutableForRuntime(conda.toString(), true));
        assertEquals(conda.toString(), RuntimeInstaller.condaExecutableForRuntime(conda.toString(), false));
    }
}
