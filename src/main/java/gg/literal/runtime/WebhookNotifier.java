package gg.literal.runtime;

import gg.literal.config.Json;
import gg.literal.config.VectorConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Posts selected activity events to a webhook (Discord and Slack incoming webhooks both accept the JSON body
 * Vector sends). Delivery is asynchronous and rate limited so an incident cannot flood the channel or stall the proxy.
 */
public final class WebhookNotifier {

    /** Event names accepted in {@code webhook-events}. */
    public static final Set<String> KNOWN_EVENTS =
        Set.of("join", "leave", "refused", "backend-up", "backend-down", "auto-ban", "admin");

    private static final int MAX_PER_WINDOW = 5;
    private static final long WINDOW_MS = 10_000L;

    private final ConfigHolder config;
    private final HttpClient client;
    private final ArrayDeque<Long> recentSends = new ArrayDeque<>();
    private final ThrottledLog failureLog = new ThrottledLog(60_000);

    public WebhookNotifier(final ConfigHolder config) {
        this(config, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    WebhookNotifier(final ConfigHolder config, final HttpClient client) {
        this.config = config;
        this.client = client;
    }

    /** Subscribes to the global activity feed. */
    public void start() {
        ActivityLog.INSTANCE.addListener(this::onEvent);
    }

    void onEvent(final ActivityLog.Event event) {
        final VectorConfig.Policy policy = config.get().policy();
        if (policy.webhookUrl().isEmpty() || !shouldSend(event, policy.webhookEvents()) || !allowNow(System.currentTimeMillis())) {
            return;
        }
        final HttpRequest request = HttpRequest.newBuilder(URI.create(policy.webhookUrl()))
            .timeout(Duration.ofSeconds(8))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload(describe(event)), StandardCharsets.UTF_8))
            .build();
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
            if (error != null) {
                failureLog.warn("Webhook delivery failed: " + error.getMessage());
            } else if (response.statusCode() >= 300) {
                failureLog.warn("Webhook returned HTTP " + response.statusCode());
            }
        });
    }

    /** {@code BACKEND_DOWN} -> {@code backend-down}. */
    static String eventName(final ActivityLog.Event event) {
        return event.type().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    static boolean shouldSend(final ActivityLog.Event event, final List<String> enabled) {
        return enabled.contains(eventName(event));
    }

    static String describe(final ActivityLog.Event event) {
        final String subject = event.subject();
        final String detail = event.detail();
        return "[Vector] " + switch (event.type()) {
            case "BACKEND_DOWN" -> "Backend " + subject + (detail.isEmpty() ? "" : " (" + detail + ")") + " is DOWN";
            case "BACKEND_UP" -> "Backend " + subject + (detail.isEmpty() ? "" : " (" + detail + ")") + " is UP again";
            case "AUTO_BAN" -> subject + " was banned automatically: " + detail;
            case "JOIN" -> subject + " joined (" + detail + ")";
            case "LEAVE" -> subject + " left (" + detail + ")";
            case "REFUSED" -> subject + " was refused: " + detail;
            case "ADMIN" -> subject + ": " + detail;
            default -> event.type() + " " + subject + (detail.isEmpty() ? "" : " " + detail);
        };
    }

    /** Body understood by both Discord ("content") and Slack ("text") incoming webhooks. */
    static String payload(final String text) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", text);
        body.put("text", text);
        return Json.write(body);
    }

    /** At most {@value #MAX_PER_WINDOW} deliveries per {@value #WINDOW_MS} ms; the rest are dropped. */
    synchronized boolean allowNow(final long now) {
        while (!recentSends.isEmpty() && now - recentSends.peekFirst() >= WINDOW_MS) {
            recentSends.removeFirst();
        }
        if (recentSends.size() >= MAX_PER_WINDOW) {
            failureLog.warn("Webhook rate limit reached; dropping events");
            return false;
        }
        recentSends.addLast(now);
        return true;
    }
}
