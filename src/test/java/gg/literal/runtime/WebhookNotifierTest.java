package gg.literal.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import gg.literal.config.Json;
import gg.literal.config.VectorConfig;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebhookNotifierTest {

    @TempDir
    Path dir;

    private static ActivityLog.Event event(final String type, final String subject, final String detail) {
        return new ActivityLog.Event(1, 0, type, subject, detail);
    }

    @Test
    void eventNamesMatchTheConfigVocabulary() {
        assertEquals("backend-down", WebhookNotifier.eventName(event("BACKEND_DOWN", "a", "")));
        assertEquals("auto-ban", WebhookNotifier.eventName(event("AUTO_BAN", "1.2.3.4", "flood")));
        for (final String known : WebhookNotifier.KNOWN_EVENTS) {
            assertEquals(known, WebhookNotifier.eventName(event(known.toUpperCase().replace('-', '_'), "", "")));
        }
        assertTrue(WebhookNotifier.KNOWN_EVENTS.contains("backend-up"));
    }

    @Test
    void onlyEnabledEventsAreSent() {
        final List<String> enabled = List.of("backend-down");
        assertTrue(WebhookNotifier.shouldSend(event("BACKEND_DOWN", "a", ""), enabled));
        assertFalse(WebhookNotifier.shouldSend(event("JOIN", "Alex", "1.2.3.4"), enabled));
    }

    @Test
    void messagesAreReadable() {
        assertEquals("[Vector] Backend lobby (10.0.0.1:25577) is DOWN",
            WebhookNotifier.describe(event("BACKEND_DOWN", "lobby", "10.0.0.1:25577")));
        assertEquals("[Vector] Backend lobby (10.0.0.1:25577) is UP again",
            WebhookNotifier.describe(event("BACKEND_UP", "lobby", "10.0.0.1:25577")));
        assertEquals("[Vector] 203.0.113.9 was banned automatically: packet flood",
            WebhookNotifier.describe(event("AUTO_BAN", "203.0.113.9", "packet flood")));
    }

    @Test
    void payloadWorksForDiscordAndSlack() {
        final Map<String, Object> body = Json.parseObject(WebhookNotifier.payload("hi \"there\""));
        assertEquals("hi \"there\"", body.get("content"));
        assertEquals("hi \"there\"", body.get("text"));
    }

    @Test
    void rateLimitDropsEventsBeyondFivePerWindow() {
        final WebhookNotifier notifier = new WebhookNotifier(null, null);
        for (int i = 0; i < 5; i++) {
            assertTrue(notifier.allowNow(1_000 + i));
        }
        assertFalse(notifier.allowNow(1_010));
        assertTrue(notifier.allowNow(11_100), "the window has slid past the earlier sends");
    }

    @Test
    void postsJsonToTheConfiguredUrl() throws Exception {
        final CountDownLatch received = new CountDownLatch(1);
        final AtomicReference<String> body = new AtomicReference<>();
        final AtomicReference<String> contentType = new AtomicReference<>();
        final HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/hook", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            received.countDown();
        });
        server.start();
        try {
            final VectorConfig d = VectorConfig.defaults();
            final VectorConfig.Policy p = d.policy();
            final String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            final VectorConfig.Policy policy = new VectorConfig.Policy(p.logChat(), p.idleTimeoutSeconds(),
                p.handshakeTimeoutMs(), p.maxConnections(), p.maintenance(), p.maintenanceMessage(), p.maintenanceMotd(),
                p.fullMessage(), p.minProtocol(), p.maxProtocol(), p.protocolMessage(), p.allowedHosts(),
                p.autoBanViolations(), p.autoBanWindowSeconds(), p.autoBanMinutes(), p.logToFile(), p.logRetentionDays(),
                url, List.of("backend-down"));
            final VectorConfig cfg = new VectorConfig(d.bindHost(), d.bindPort(), d.bossThreads(), d.workerThreads(),
                d.maxBytesPerSecond(), d.maxPacketsPerSecond(), d.connectTimeoutMs(), d.proxyProtocol(), d.backends(),
                d.limits(), d.status(), d.access(), d.metrics(), d.dashboard(), policy);

            final WebhookNotifier notifier = new WebhookNotifier(new ConfigHolder(dir.resolve("c.json"), cfg));
            notifier.onEvent(event("JOIN", "Alex", "1.2.3.4")); // filtered out
            notifier.onEvent(event("BACKEND_DOWN", "lobby", "10.0.0.1:25577"));

            assertTrue(received.await(5, TimeUnit.SECONDS), "webhook was not called");
            assertEquals("application/json", contentType.get());
            assertEquals("[Vector] Backend lobby (10.0.0.1:25577) is DOWN", Json.parseObject(body.get()).get("content"));
        } finally {
            server.stop(0);
        }
    }
}
