package gg.literal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VectorLauncherTest {

    @TempDir
    Path dir;

    @Test
    void validConfigExitsWithZero() throws Exception {
        final Path file = dir.resolve("config.json");
        Files.writeString(file, "{\"backends\": [{\"name\": \"a\", \"host\": \"h\", \"port\": 1}]}");
        assertEquals(0, VectorLauncher.checkConfig(file));
    }

    @Test
    void invalidConfigExitsWithOne() throws Exception {
        final Path file = dir.resolve("config.json");
        Files.writeString(file, "{\"bind-port\": 0}");
        assertEquals(1, VectorLauncher.checkConfig(file));
    }

    @Test
    void missingConfigExitsWithOne() {
        assertEquals(1, VectorLauncher.checkConfig(dir.resolve("nope.json")));
    }
}
