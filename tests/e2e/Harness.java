import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** End-to-end test: fake Minecraft backends + scripted client against a real Vector process. */
/*
 * Usage: java -Dsun.net.http.allowRestrictedHeaders=true tests/e2e/Harness.java <workDir> <vector.jar> [librariesDir]
 * Starts two fake Minecraft backends and a real Vector process, then drives it over the wire.
 * If librariesDir is omitted Vector downloads Netty itself on first start.
 */
public class Harness {

    static final int VECTOR = 26000, A = 26001, B = 26002, METRICS = 26100, DASH = 26200;
    static Path run;
    static Process vector;
    static PrintWriter vin;
    static final List<String> log = new CopyOnWriteArrayList<>();
    static int passed, failed;

    // ------------------------------------------------------------ fake backend
    static class Fake {
        final String name; final int port; final int online;
        ServerSocket server; volatile boolean up;
        final AtomicInteger logins = new AtomicInteger();
        Fake(String n, int p, int o) { name = n; port = p; online = o; }
        void start() throws Exception {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", port));
            up = true;
            Thread t = new Thread(() -> {
                while (up) {
                    try { Socket s = server.accept(); Thread.startVirtualThread(() -> handle(s)); }
                    catch (IOException e) { return; }
                }
            });
            t.setDaemon(true); t.start();
        }
        void stop() throws Exception { up = false; server.close(); }
        void handle(Socket s) {
            try (s) {
                s.setSoTimeout(5000);
                DataInputStream in = new DataInputStream(s.getInputStream());
                OutputStream out = s.getOutputStream();
                readPacket(in); // handshake
                byte[] first = readPacket(in);
                int id = first[0];
                if (id == 0x00 && first.length == 1) { // status request
                    String json = "{\"version\":{\"name\":\"Fake " + name + "\",\"protocol\":767},\"players\":{\"max\":10,\"online\":" + online
                        + "},\"description\":{\"text\":\"MOTD " + name + "\"}}";
                    out.write(strPacket(0x00, json));
                    out.flush();
                    byte[] ping = readPacket(in);
                    out.write(pack(ping));
                    out.flush();
                } else { // login start -> reply with a Login Disconnect naming this backend
                    logins.incrementAndGet();
                    out.write(strPacket(0x00, "{\"text\":\"backend:" + name + "\"}"));
                    out.flush();
                    Thread.sleep(150);
                }
            } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------ wire helpers
    static void writeVarInt(ByteArrayOutputStream o, int v) { while ((v & ~0x7F) != 0) { o.write((v & 0x7F) | 0x80); v >>>= 7; } o.write(v); }
    static int readVarInt(DataInputStream in) throws IOException {
        int r = 0, shift = 0;
        while (true) { int b = in.readUnsignedByte(); r |= (b & 0x7F) << shift; if ((b & 0x80) == 0) return r; shift += 7; if (shift > 35) throw new IOException("varint"); }
    }
    static byte[] readPacket(DataInputStream in) throws IOException {
        int len = readVarInt(in); byte[] b = new byte[len]; in.readFully(b); return b;
    }
    static byte[] pack(byte[] body) { ByteArrayOutputStream o = new ByteArrayOutputStream(); writeVarInt(o, body.length); o.writeBytes(body); return o.toByteArray(); }
    static byte[] strPacket(int id, String s) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); writeVarInt(b, id);
        byte[] sb = s.getBytes(StandardCharsets.UTF_8); writeVarInt(b, sb.length); b.writeBytes(sb); return pack(b.toByteArray());
    }
    static byte[] handshake(int state) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); writeVarInt(b, 0); writeVarInt(b, 767);
        byte[] h = "localhost".getBytes(); writeVarInt(b, h.length); b.writeBytes(h);
        b.write(VECTOR >> 8); b.write(VECTOR & 0xFF); writeVarInt(b, state); return pack(b.toByteArray());
    }
    static byte[] loginStart(String name) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); writeVarInt(b, 0);
        byte[] n = name.getBytes(); writeVarInt(b, n.length); b.writeBytes(n); b.writeBytes(new byte[16]); return pack(b.toByteArray());
    }

    /** Logs in and returns the Login Disconnect text, or "CLOSED" if the connection died without data. */
    static String login(String name) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", VECTOR), 3000);
            s.setSoTimeout(4000);
            OutputStream out = s.getOutputStream();
            out.write(handshake(2)); out.write(loginStart(name)); out.flush();
            DataInputStream in = new DataInputStream(s.getInputStream());
            byte[] p = readPacket(in);
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(p));
            readVarInt(d); int l = readVarInt(d); byte[] t = new byte[l]; d.readFully(t);
            return new String(t, StandardCharsets.UTF_8);
        } catch (Exception e) { return "CLOSED"; }
    }

    /** Status ping through Vector; returns the JSON or "CLOSED"; sets pongOk[0]. */
    static String status(boolean[] pongOk) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", VECTOR), 3000);
            s.setSoTimeout(4000);
            OutputStream out = s.getOutputStream();
            out.write(handshake(1)); out.write(new byte[]{1, 0}); out.flush();
            DataInputStream in = new DataInputStream(s.getInputStream());
            byte[] p = readPacket(in);
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(p));
            readVarInt(d); int l = readVarInt(d); byte[] t = new byte[l]; d.readFully(t);
            ByteArrayOutputStream ping = new ByteArrayOutputStream(); writeVarInt(ping, 1); ping.writeBytes(new byte[]{0,0,0,0,0,0,0x12,0x34});
            out.write(pack(ping.toByteArray())); out.flush();
            byte[] pong = readPacket(in);
            if (pongOk != null) pongOk[0] = pong.length == 9 && pong[0] == 1 && pong[8] == 0x34;
            return new String(t, StandardCharsets.UTF_8);
        } catch (Exception e) { return "CLOSED"; }
    }

    // ------------------------------------------------------------ vector process
    static String config(String backendsJson, String extra) {
        return "{\n \"bind-host\":\"127.0.0.1\",\"bind-port\":" + VECTOR + ",\n \"boss-threads\":1,\"worker-threads\":2,\n"
            + " \"backends\":" + backendsJson + ",\n \"health-check-interval-ms\":500,\"health-check-timeout-ms\":400,\"health-check-failures\":2,\n"
            + " \"status-cache-ttl-ms\":400,\"offline-motd\":\"OFFLINE-MOTD\",\n \"metrics-enabled\":true,\"metrics-port\":" + METRICS + ",\n"
            + " \"dashboard-port\":" + DASH + ",\"dashboard-password\":\"s3cret-pass\",\n"
            + " \"max-connections-per-ip\":0,\"max-connection-rate-per-ip\":0,\n" + extra + "\n \"proxy-protocol\":false\n}";
    }
    static void writeConfig(String c) throws IOException { Files.writeString(run.resolve("config.json"), c); }

    static void startVector() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("java", "--enable-native-access=ALL-UNNAMED", "-jar", "vector.jar", "--attached-console");
        pb.directory(run.toFile()); pb.redirectErrorStream(true);
        vector = pb.start();
        vin = new PrintWriter(new OutputStreamWriter(vector.getOutputStream()), true);
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(vector.getInputStream()))) {
                String line; while ((line = r.readLine()) != null) { log.add(line); }
            } catch (IOException ignored) { }
        });
        t.setDaemon(true); t.start();
        waitLog("Vector online", 20000);
    }
    static void cmd(String c) { vin.println(c); }
    static boolean waitLog(String needle, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { for (String l : log) if (l.contains(needle)) return true; Thread.sleep(50); }
        return false;
    }
    static boolean logHas(String needle) { for (String l : log) if (l.contains(needle)) return true; return false; }
    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  PASS  " + name); } else { failed++; System.out.println("  FAIL  " + name + "  -> " + detail); }
    }
    static String http(String path) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + METRICS + path).openConnection();
            c.setConnectTimeout(2000); c.setReadTimeout(2000);
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = is == null ? "" : new String(is.readAllBytes());
            return code + "\n" + body;
        } catch (Exception e) { return "ERR " + e; }
    }

    static String[] dash(String method, String path, String body, String... headers) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + DASH + path).openConnection();
            c.setRequestMethod(method); c.setConnectTimeout(2000); c.setReadTimeout(3000);
            for (int i = 0; i + 1 < headers.length; i += 2) c.setRequestProperty(headers[i], headers[i + 1]);
            if (body != null) { c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json"); c.getOutputStream().write(body.getBytes()); }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String resp = is == null ? "" : new String(is.readAllBytes());
            String cookie = c.getHeaderField("Set-Cookie");
            return new String[]{String.valueOf(code), resp, cookie == null ? "" : cookie, c.getHeaderField("Content-Security-Policy") == null ? "" : "csp"};
        } catch (Exception e) { return new String[]{"ERR", String.valueOf(e), "", ""}; }
    }
    static String json(String backendA, String backendB) {
        return "[{\"name\":\"a\",\"host\":\"127.0.0.1\",\"port\":" + A + backendA + "},{\"name\":\"b\",\"host\":\"127.0.0.1\",\"port\":" + B + backendB + "}]";
    }

    public static void main(String[] args) throws Exception {
        run = Path.of(args[0]);
        Files.createDirectories(run);
        Files.copy(Path.of(args[1]), run.resolve("vector.jar"), StandardCopyOption.REPLACE_EXISTING);
        Path libs = run.resolve("libraries");
        if (args.length > 2 && Files.notExists(libs)) {
            Files.createDirectories(libs);
            try (var s = Files.list(Path.of(args[2]))) { for (Path p : (Iterable<Path>) s::iterator) Files.copy(p, libs.resolve(p.getFileName())); }
        }
        Files.writeString(run.resolve("eula.txt"), "eula=true\n");
        Files.deleteIfExists(run.resolve("ban-list.txt")); Files.deleteIfExists(run.resolve("whitelist.txt"));

        Fake fa = new Fake("a", A, 2), fb = new Fake("b", B, 3);
        fa.start(); fb.start();
        writeConfig(config(json("", ""), ""));

        try {
            startVector();
            Thread.sleep(800);

            System.out.println("== round-robin");
            Map<String, Integer> dist = new TreeMap<>();
            for (int i = 0; i < 6; i++) dist.merge(login("Player" + i), 1, Integer::sum);
            check("6 logins split 3/3 across a and b", dist.getOrDefault("{\"text\":\"backend:a\"}", 0) == 3 && dist.getOrDefault("{\"text\":\"backend:b\"}", 0) == 3, dist.toString());

            System.out.println("== status cache + player summing");
            boolean[] pong = {false};
            String st = status(pong);
            check("status served with summed players 5/20", st.contains("\"online\":5") && st.contains("\"max\":20"), st);
            check("ping/pong echoed locally", pong[0], "no pong");
            int before = fa.logins.get() + fb.logins.get();
            for (int i = 0; i < 20; i++) status(null);
            check("status pings never count as logins on backends", fa.logins.get() + fb.logins.get() == before, "logins changed");

            System.out.println("== metrics endpoint");
            String m = http("/metrics");
            check("/metrics 200 with backend gauges", m.startsWith("200") && m.contains("vector_backend_up{backend=\"a\"} 1") && m.contains("vector_status_responses_total{source=\"cache\"}"), m.substring(0, Math.min(200, m.length())));
            check("/health 200", http("/health").startsWith("200"), http("/health"));
            check("/stats JSON lists backends", http("/stats").contains("\"backends\":["), http("/stats"));

            System.out.println("== failover when backend a dies");
            fa.stop();
            Thread.sleep(2200);
            check("health checker logged a DOWN", logHas("is DOWN"), "no DOWN log");
            dist.clear();
            for (int i = 0; i < 4; i++) dist.merge(login("P" + i), 1, Integer::sum);
            check("all logins land on b", dist.getOrDefault("{\"text\":\"backend:b\"}", 0) == 4, dist.toString());
            Thread.sleep(600);
            st = status(null);
            check("status now reports only b (3/10)", st.contains("\"online\":3") && st.contains("\"max\":10"), st);

            System.out.println("== offline MOTD when everything is down");
            fb.stop();
            Thread.sleep(2200);
            st = status(pong);
            check("status shows offline MOTD", st.contains("OFFLINE-MOTD") && st.contains("\"online\":0"), st);
            check("offline ping still pongs", pong[0], "no pong");
            String l = login("Nobody");
            check("login gets a readable disconnect", l.contains("OFFLINE-MOTD"), l);
            check("/health is 503", http("/health").startsWith("503"), http("/health"));

            System.out.println("== recovery");
            fa.start(); fb.start();
            Thread.sleep(1800);
            check("UP again logged", logHas("is UP again"), "no UP log");
            check("login works again", login("Back").contains("backend:"), "login failed");

            System.out.println("== per-IP limits via reload");
            writeConfig(config(json("", ""), "\"max-connections-per-ip\":3,"));
            cmd("reload"); Thread.sleep(600);
            check("reload logged", logHas("Configuration reloaded"), "no reload log");
            List<Socket> held = new ArrayList<>();
            for (int i = 0; i < 5; i++) { Socket s = new Socket(); s.connect(new InetSocketAddress("127.0.0.1", VECTOR)); held.add(s); }
            Thread.sleep(500);
            int dead = 0;
            for (Socket s : held) { try { s.setSoTimeout(300); if (s.getInputStream().read() == -1) dead++; } catch (SocketTimeoutException e) { } catch (IOException e) { dead++; } }
            check("connections beyond 3 are dropped (2 of 5)", dead == 2, "dead=" + dead);
            check("limit logged", logHas("Connection limit"), "no limit log");
            for (Socket s : held) s.close();
            Thread.sleep(300);
            writeConfig(config(json("", ""), "\"max-connection-rate-per-ip\":4,\"connection-rate-window-ms\":3000,"));
            cmd("reload"); Thread.sleep(500);
            int closed = 0;
            for (int i = 0; i < 7; i++) if (status(null).equals("CLOSED")) closed++;
            check("rate limit refuses connections after 4/window", closed >= 3, "closed=" + closed);
            Thread.sleep(3300);

            System.out.println("== weighted balancing via reload");
            writeConfig(config(json(",\"weight\":3", ",\"weight\":1"), "\"balance-mode\":\"weighted\","));
            cmd("reload"); Thread.sleep(600);
            dist.clear();
            for (int i = 0; i < 8; i++) dist.merge(login("W" + i), 1, Integer::sum);
            check("weights 3:1 -> 6/2", dist.getOrDefault("{\"text\":\"backend:a\"}", 0) == 6 && dist.getOrDefault("{\"text\":\"backend:b\"}", 0) == 2, dist.toString());

            System.out.println("== ip-hash is sticky");
            writeConfig(config(json("", ""), "\"balance-mode\":\"ip-hash\","));
            cmd("reload"); Thread.sleep(600);
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 5; i++) seen.add(login("H" + i));
            check("same IP always same backend", seen.size() == 1, seen.toString());

            System.out.println("== bad reload keeps old config");
            Files.writeString(run.resolve("config.json"), "{ not json");
            cmd("reload"); Thread.sleep(500);
            check("bad config rejected", logHas("Reload failed"), "no failure log");
            check("still serving", login("Still").contains("backend:"), "login failed");

            System.out.println("== whitelist");
            writeConfig(config(json("", ""), "\"whitelist-enabled\":true,\"whitelist-message\":\"NOT-ALLOWED\","));
            cmd("reload"); Thread.sleep(500);
            check("non-whitelisted name refused with message", login("Bob").contains("NOT-ALLOWED"), login("Bob"));
            cmd("whitelist add Bob"); Thread.sleep(300);
            check("whitelisted name allowed (case-insensitive)", login("bob").contains("backend:") || login("Bob").contains("backend:"), login("Bob"));
            check("status pings unaffected by whitelist", status(null).contains("MOTD"), status(null));
            cmd("whitelist add 127.0.0.0/8"); Thread.sleep(300);
            check("CIDR whitelist admits any local name", login("Zed").contains("backend:"), login("Zed"));
            check("whitelist.txt persisted", Files.readString(run.resolve("whitelist.txt")).contains("127.0.0.0/8"), "file");
            writeConfig(config(json("", ""), ""));
            cmd("reload"); Thread.sleep(400);

            System.out.println("== timed + CIDR bans");
            cmd("ban 127.0.0.1 2s testing reason"); Thread.sleep(400);
            check("banned IP gets closed", status(null).equals("CLOSED"), "not closed");
            check("ban log has reason", logHas("testing reason"), "no reason");
            check("ban-list.txt persisted", Files.readString(run.resolve("ban-list.txt")).contains("127.0.0.1|"), "file");
            Thread.sleep(2200);
            check("timed ban expires", !status(null).equals("CLOSED"), "still closed");
            cmd("ban 127.0.0.0/8 5m"); Thread.sleep(300);
            check("CIDR ban covers 127.0.0.1", status(null).equals("CLOSED"), "not closed");
            cmd("unban 127.0.0.0/8"); Thread.sleep(300);
            check("unban CIDR restores access", !status(null).equals("CLOSED"), "still closed");
            cmd("ban not-an-ip"); Thread.sleep(300);
            check("invalid ban target rejected", logHas("Not a valid IP address or CIDR"), "no warning");


            System.out.println("== dashboard");
            String[] page = dash("GET", "/", null);
            check("index.html served without auth with CSP", page[0].equals("200") && page[1].contains("Vector dashboard") && page[3].equals("csp"), page[0] + page[3]);
            check("app.js and style.css served", dash("GET", "/app.js", null)[0].equals("200") && dash("GET", "/style.css", null)[0].equals("200"), "assets");
            check("session endpoint says auth required", dash("GET", "/api/session", null)[1].contains("\"authRequired\":true") && dash("GET", "/api/session", null)[1].contains("\"authenticated\":false"), dash("GET", "/api/session", null)[1]);
            check("state needs sign-in (401)", dash("GET", "/api/state", null)[0].equals("401"), dash("GET", "/api/state", null)[0]);
            String csrf = "X-Requested-With", cv = "vector-dashboard";
            check("login without CSRF header refused (403)", dash("POST", "/api/login", "{\"password\":\"s3cret-pass\"}")[0].equals("403"), "csrf");
            check("login with foreign Origin refused (403)", dash("POST", "/api/login", "{\"password\":\"s3cret-pass\"}", csrf, cv, "Origin", "http://evil.example")[0].equals("403"), "origin");
            check("wrong password refused (401)", dash("POST", "/api/login", "{\"password\":\"nope\"}", csrf, cv)[0].equals("401"), "wrong");
            String[] li = dash("POST", "/api/login", "{\"password\":\"s3cret-pass\"}", csrf, cv, "Origin", "http://127.0.0.1:" + DASH);
            check("login succeeds and sets HttpOnly SameSite cookie", li[0].equals("200") && li[2].contains("HttpOnly") && li[2].contains("SameSite=Strict"), li[0] + li[2]);
            String cookie = li[2].split(";")[0];
            Thread.sleep(2500);
            String[] st2 = dash("GET", "/api/state?history=0&log=0", null, "Cookie", cookie);
            check("state returns backends, history and logs", st2[0].equals("200") && st2[1].contains("\"backends\":[{") && st2[1].contains("\"history\":[{") && st2[1].contains("\"logs\":[{"), st2[0] + st2[1].substring(0, Math.min(160, st2[1].length())));
            check("state carries the activity feed", st2[1].contains("\"activity\":[") && st2[1].contains("\"activitySeq\":"), "activity");
            check("state lists connections list and totals", st2[1].contains("\"connectionsTotal\"") && st2[1].contains("\"whitelist\":{"), "fields");
            check("Bearer password works for scripts", dash("GET", "/api/state", null, "Authorization", "Bearer s3cret-pass")[0].equals("200"), "bearer");
            check("config view masks secrets", dash("GET", "/api/config", null, "Cookie", cookie)[1].contains("\"dashboard-password\":\"(set)\"") && !dash("GET", "/api/config", null, "Cookie", cookie)[1].contains("s3cret-pass"), "config");
            check("action without CSRF header refused", dash("POST", "/api/action", "{\"action\":\"reload\"}", "Cookie", cookie)[0].equals("403"), "csrf action");
            String[] ban = dash("POST", "/api/action", "{\"action\":\"ban\",\"target\":\"203.0.113.0/24\",\"duration\":\"1h\",\"reason\":\"from dashboard\"}", "Cookie", cookie, csrf, cv);
            check("ban action works", ban[0].equals("200") && ban[1].contains("\"ok\":true"), ban[0] + ban[1]);
            check("ban visible in state", dash("GET", "/api/state", null, "Cookie", cookie)[1].contains("203.0.113.0/24"), "state");
            check("bad duration rejected", dash("POST", "/api/action", "{\"action\":\"ban\",\"target\":\"1.2.3.4\",\"duration\":\"soon\"}", "Cookie", cookie, csrf, cv)[0].equals("400"), "dur");
            check("invalid ban target rejected", dash("POST", "/api/action", "{\"action\":\"ban\",\"target\":\"not-an-ip\"}", "Cookie", cookie, csrf, cv)[0].equals("400"), "target");
            check("unban action works", dash("POST", "/api/action", "{\"action\":\"unban\",\"target\":\"203.0.113.0/24\"}", "Cookie", cookie, csrf, cv)[0].equals("200"), "unban");
            check("whitelist add/remove via dashboard", dash("POST", "/api/action", "{\"action\":\"whitelist-add\",\"entry\":\"DashUser\"}", "Cookie", cookie, csrf, cv)[0].equals("200")
                && dash("POST", "/api/action", "{\"action\":\"whitelist-remove\",\"entry\":\"DashUser\"}", "Cookie", cookie, csrf, cv)[0].equals("200"), "wl");
            check("reload action works", dash("POST", "/api/action", "{\"action\":\"reload\"}", "Cookie", cookie, csrf, cv)[1].contains("reloaded"), "reload");
            check("unknown action rejected", dash("POST", "/api/action", "{\"action\":\"rm-rf\"}", "Cookie", cookie, csrf, cv)[0].equals("400"), "unknown");
            check("dashboard actions are logged", logHas("[DASHBOARD]"), "no log");
            String act = "/api/action";
            check("dashboard can drain a backend", dash("POST", act, "{\"action\":\"drain\",\"backend\":\"a\"}", "Cookie", cookie, csrf, cv)[0].equals("200")
                && dash("GET", "/api/state", null, "Cookie", cookie)[1].contains("\"draining\":true"), "drain");
            check("dashboard can undrain it", dash("POST", act, "{\"action\":\"undrain\",\"backend\":\"a\"}", "Cookie", cookie, csrf, cv)[0].equals("200"), "undrain");
            check("drain of an unknown backend is rejected", dash("POST", act, "{\"action\":\"drain\",\"backend\":\"zzz\"}", "Cookie", cookie, csrf, cv)[0].equals("400"), "drain zzz");
            check("evacuate validates its backends", dash("POST", act, "{\"action\":\"evacuate\",\"from\":\"a\",\"to\":\"a\"}", "Cookie", cookie, csrf, cv)[0].equals("400"), "evac same");
            check("kickall by backend works", dash("POST", act, "{\"action\":\"kickall\",\"backend\":\"b\"}", "Cookie", cookie, csrf, cv)[0].equals("200"), "kickall");
            check("a safe setting can be changed (maintenance on)", dash("POST", act, "{\"action\":\"setting\",\"key\":\"maintenance-enabled\",\"value\":true}", "Cookie", cookie, csrf, cv)[0].equals("200")
                && status(null).contains("Under maintenance"), "setting on");
            check("setting is persisted to config.json", Files.readString(run.resolve("config.json")).contains("\"maintenance-enabled\": true"), "persist");
            dash("POST", act, "{\"action\":\"setting\",\"key\":\"maintenance-enabled\",\"value\":false}", "Cookie", cookie, csrf, cv);
            check("maintenance is off again", !status(null).contains("Under maintenance"), "setting off");
            check("wrong value type is rejected", dash("POST", act, "{\"action\":\"setting\",\"key\":\"maintenance-enabled\",\"value\":\"yes\"}", "Cookie", cookie, csrf, cv)[0].equals("400"), "type");
            check("secrets cannot be edited remotely", dash("POST", act, "{\"action\":\"setting\",\"key\":\"dashboard-password\",\"value\":\"x\"}", "Cookie", cookie, csrf, cv)[0].equals("400"), "secret");
            check("config view lists the editable keys", dash("GET", "/api/config", null, "Cookie", cookie)[1].contains("\"editable\":{"), "editable");
            check("logout invalidates session", dash("POST", "/api/logout", "{}", "Cookie", cookie, csrf, cv)[0].equals("200") && dash("GET", "/api/state", null, "Cookie", cookie)[0].equals("401"), "logout");
            int last = 0; for (int i = 0; i < 6; i++) last = Integer.parseInt(dash("POST", "/api/login", "{\"password\":\"bad" + i + "\"}", csrf, cv)[0]);
            check("repeated wrong passwords lock out (429)", last == 429, "last=" + last);
            check("locked out even with the right password", dash("POST", "/api/login", "{\"password\":\"s3cret-pass\"}", csrf, cv)[0].equals("429"), "lock");

            System.out.println("== maintenance mode");
            cmd("whitelist remove 127.0.0.0/8"); cmd("whitelist remove Bob"); Thread.sleep(300);
            cmd("maintenance on"); Thread.sleep(600);
            check("maintenance written to config.json", Files.readString(run.resolve("config.json")).contains("\"maintenance-enabled\": true"), "file");
            check("login refused with the maintenance message", login("Visitor").contains("under maintenance"), login("Visitor"));
            check("status shows the maintenance MOTD", status(null).contains("Under maintenance"), status(null));
            cmd("whitelist add Visitor"); Thread.sleep(300);
            check("whitelisted staff can still join", login("Visitor").contains("backend:"), login("Visitor"));
            cmd("whitelist remove Visitor"); cmd("maintenance off"); Thread.sleep(600);
            check("logins work again after maintenance", login("Visitor").contains("backend:"), login("Visitor"));
            check("blocked{maintenance} metric counted", http("/metrics").contains("vector_blocked_total{reason=\"maintenance\"}") && !http("/metrics").contains("vector_blocked_total{reason=\"maintenance\"} 0"), "metric");

            System.out.println("== drain");
            writeConfig(config(json("", ""), "")); cmd("reload"); Thread.sleep(500);
            cmd("drain a"); Thread.sleep(300);
            dist.clear();
            for (int i = 0; i < 4; i++) dist.merge(login("D" + i), 1, Integer::sum);
            check("draining backend gets no new players", dist.getOrDefault("{\"text\":\"backend:b\"}", 0) == 4, dist.toString());
            cmd("undrain a"); Thread.sleep(300);
            dist.clear();
            for (int i = 0; i < 4; i++) dist.merge(login("U" + i), 1, Integer::sum);
            check("undrained backend takes players again", dist.getOrDefault("{\"text\":\"backend:a\"}", 0) >= 1, dist.toString());

            System.out.println("== host routing");
            writeConfig(config(json("", ""), "\"routes\":[{\"host\":\"localhost\",\"backends\":[\"b\"]}],"));
            cmd("reload"); Thread.sleep(600);
            dist.clear();
            for (int i = 0; i < 5; i++) dist.merge(login("R" + i), 1, Integer::sum);
            check("route pins localhost to backend b", dist.getOrDefault("{\"text\":\"backend:b\"}", 0) == 5, dist.toString());
            writeConfig(config(json("", ""), ""));
            cmd("reload"); Thread.sleep(400);
            System.out.println("== console commands smoke");
            cmd("backends"); cmd("stats"); cmd("list"); cmd("banlist"); cmd("whitelist list"); Thread.sleep(500);
            check("backends/stats print", logHas("Backends (2") && logHas("Status pings: cache="), "missing output");
        } finally {
            System.out.println("\nPassed " + passed + ", failed " + failed);
            Files.writeString(run.resolve("harness-vector.log"), String.join("\n", log));
            if (vector != null) vector.destroyForcibly();
            try { fa.stop(); } catch (Exception ignored) { }
            try { fb.stop(); } catch (Exception ignored) { }
            System.exit(failed == 0 ? 0 : 1);
        }
    }
}
