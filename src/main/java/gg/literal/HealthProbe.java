package gg.literal;

import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Minimal liveness probe for container health checks: {@code java -cp vector.jar gg.literal.HealthProbe 3000}
 * exits 0 if the dashboard answers on that port and 1 otherwise.
 */
public final class HealthProbe {

    private HealthProbe() {
    }

    public static void main(final String[] args) {
        final int port = args.length > 0 ? Integer.parseInt(args[0]) : 3000;
        try {
            final HttpURLConnection connection =
                (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/api/session").toURL().openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            System.exit(connection.getResponseCode() == 200 ? 0 : 1);
        } catch (Exception ex) {
            System.exit(1);
        }
    }
}
