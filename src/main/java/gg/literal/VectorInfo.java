package gg.literal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Build metadata baked into the jar by Maven resource filtering. */
public final class VectorInfo {

    private static final Properties PROPS = load();

    private VectorInfo() {
    }

    public static String version() {
        return PROPS.getProperty("version", "dev");
    }

    /** UTC build timestamp, or "unknown" when running from an IDE without filtered resources. */
    public static String builtAt() {
        final String value = PROPS.getProperty("built", "unknown");
        return value.startsWith("${") ? "unknown" : value;
    }

    private static Properties load() {
        final Properties props = new Properties();
        try (InputStream in = VectorInfo.class.getResourceAsStream("/vector.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
            // fall back to defaults
        }
        final String version = props.getProperty("version", "");
        if (version.startsWith("${")) {
            props.remove("version");
        }
        return props;
    }
}
