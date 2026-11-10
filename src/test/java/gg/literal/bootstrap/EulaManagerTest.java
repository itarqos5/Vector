package gg.literal.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EulaManagerTest {

    @TempDir
    Path dir;

    @Test
    void missingFileIsGeneratedAndStartupStops() throws Exception {
        final Path eula = dir.resolve("eula.txt");
        assertThrows(BootstrapException.class, () -> EulaManager.verifyOrCreate(eula, Map.of()));
        assertTrue(Files.readString(eula).contains("eula=false"));
    }

    @Test
    void acceptedFilePasses() throws Exception {
        final Path eula = dir.resolve("eula.txt");
        Files.writeString(eula, "# note\nEULA=TRUE\n");
        EulaManager.verifyOrCreate(eula, Map.of());
    }

    @Test
    void refusedFileStopsStartup() throws Exception {
        final Path eula = dir.resolve("eula.txt");
        Files.writeString(eula, "eula=false\n");
        assertThrows(BootstrapException.class, () -> EulaManager.verifyOrCreate(eula, Map.of()));
    }

    @Test
    void environmentVariableAcceptsAndRecordsTheEula() throws Exception {
        final Path eula = dir.resolve("eula.txt");
        EulaManager.verifyOrCreate(eula, Map.of("VECTOR_EULA", "true"));
        assertTrue(Files.readString(eula).contains("eula=true"));
        // a second start with the file in place no longer needs the variable
        EulaManager.verifyOrCreate(eula, Map.of());
    }

    @Test
    void otherEnvironmentValuesDoNotAccept() throws Exception {
        final Path eula = dir.resolve("eula.txt");
        assertThrows(BootstrapException.class, () -> EulaManager.verifyOrCreate(eula, Map.of("VECTOR_EULA", "yes")));
        assertEquals(false, Files.exists(eula) && Files.readString(eula, java.nio.charset.StandardCharsets.UTF_8).contains("eula=true"));
    }
}
