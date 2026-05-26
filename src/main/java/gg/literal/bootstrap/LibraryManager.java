package gg.literal.bootstrap;

import gg.literal.config.VectorConfig;
import gg.literal.log.TerminalLogger;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class LibraryManager {

    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/io/netty/";
    private static final List<String> REQUIRED_ARTIFACTS = List.of(
        "netty-common",
        "netty-buffer",
        "netty-transport",
        "netty-codec",
        "netty-handler"
    );

    private final Path librariesDir;
    private final String nettyVersion;

    public LibraryManager(final Path librariesDir, final String nettyVersion) {
        this.librariesDir = librariesDir;
        this.nettyVersion = nettyVersion;
    }

    public void ensureRequiredNettyLibraries() throws BootstrapException {
        try {
            Files.createDirectories(librariesDir);
        } catch (IOException ex) {
            throw new BootstrapException("Unable to create libraries directory", ex);
        }

        final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        for (String artifact : REQUIRED_ARTIFACTS) {
            final String fileName = artifact + "-" + nettyVersion + ".jar";
            final Path targetFile = librariesDir.resolve(fileName);
            if (Files.exists(targetFile)) {
                continue;
            }
            downloadJar(client, artifact, fileName, targetFile);
        }

        TerminalLogger.info("Library validation complete: Netty components are present in libraries/");
    }

    public ClassLoader createRuntimeClassLoader(final Class<?> anchorClass) throws BootstrapException {
        final List<URL> urls = new ArrayList<>();

        try {
            final URI selfJarUri = anchorClass.getProtectionDomain().getCodeSource().getLocation().toURI();
            urls.add(selfJarUri.toURL());
        } catch (Exception ex) {
            throw new BootstrapException("Unable to resolve launcher JAR location", ex);
        }

        for (String artifact : REQUIRED_ARTIFACTS) {
            final String fileName = artifact + "-" + nettyVersion + ".jar";
            final Path file = librariesDir.resolve(fileName);
            if (!Files.exists(file)) {
                throw new BootstrapException("Missing required library: " + file);
            }
            try {
                urls.add(file.toUri().toURL());
            } catch (Exception ex) {
                throw new BootstrapException("Invalid library path: " + file, ex);
            }
        }

        return new ChildFirstClassLoader(
            urls.toArray(URL[]::new),
            ClassLoader.getSystemClassLoader(),
            List.of("gg.literal.runtime.", "io.netty.")
        );
    }

    public void invokeRuntime(final String runtimeEntrypoint, final ClassLoader classLoader, final VectorConfig config)
        throws BootstrapException {
        try {
            final Class<?> runtime = Class.forName(runtimeEntrypoint, true, classLoader);
            final Method bootMethod = runtime.getMethod("boot", VectorConfig.class);
            bootMethod.invoke(null, config);
        } catch (Exception ex) {
            throw new BootstrapException("Failed to invoke Vector runtime", ex);
        }
    }

    private void downloadJar(
        final HttpClient client,
        final String artifact,
        final String fileName,
        final Path targetFile
    ) throws BootstrapException {
        final String url = MAVEN_CENTRAL + artifact + "/" + nettyVersion + "/" + fileName;
        final Path tempFile = targetFile.resolveSibling(fileName + ".part");

        TerminalLogger.info("Downloading missing dependency: " + artifact + " " + nettyVersion);

        final HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();

        final HttpResponse<Path> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofFile(tempFile));
        } catch (Exception ex) {
            throw new BootstrapException("Failed to download " + artifact + " from Maven Central", ex);
        }

        if (response.statusCode() != 200) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
            }
            throw new BootstrapException("Maven Central returned status " + response.statusCode() + " for " + url);
        }

        try {
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            throw new BootstrapException("Failed to finalize library file " + targetFile, ex);
        }
    }
}
