import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Mock-data demo: starts 3 fake backends (one deliberately down) and Vector, then generates traffic so the
 * dashboard has something to show.
 * Usage: java tests/e2e/Demo.java <workDir> <vector.jar> <librariesDir|-> <seconds>
 * Dashboard: http://localhost:3000
 */
public class Demo {
    public static void main(String[] args) throws Exception {
        Path run = Path.of(args[0]);
        Files.createDirectories(run);
        Files.copy(Path.of(args[1]), run.resolve("vector.jar"), StandardCopyOption.REPLACE_EXISTING);
        if (!args[2].equals("-") && Files.notExists(run.resolve("libraries"))) {
            Files.createDirectories(run.resolve("libraries"));
            try (var s = Files.list(Path.of(args[2]))) { for (Path p : (Iterable<Path>) s::iterator) Files.copy(p, run.resolve("libraries").resolve(p.getFileName())); }
        }
        Files.writeString(run.resolve("eula.txt"), "eula=true\n");
        Files.deleteIfExists(run.resolve("config.json"));
        Files.deleteIfExists(run.resolve("ban-list.txt"));
        Files.writeString(run.resolve("config.json"), """
            {"bind-host":"127.0.0.1","bind-port":26300,"boss-threads":1,"worker-threads":2,
             "backends":[{"name":"lobby-1","host":"127.0.0.1","port":26301,"weight":2},
                         {"name":"lobby-2","host":"127.0.0.1","port":26302,"weight":1},
                         {"name":"survival","host":"127.0.0.1","port":26303,"weight":1}],
             "balance-mode":"least-connections","health-check-interval-ms":1000,"max-connections-per-ip":0,"max-connection-rate-per-ip":0}
            """);
        for (int port : new int[]{26301, 26302}) startBackend(port);
        // 26303 intentionally down so the map shows a failed backend

        ProcessBuilder pb = new ProcessBuilder("java", "--enable-native-access=ALL-UNNAMED", "-jar", "vector.jar", "--attached-console");
        pb.directory(run.toFile()); pb.redirectErrorStream(true);
        pb.redirectOutput(run.resolve("demo-vector.log").toFile());
        Process vector = pb.start();
        Thread.sleep(4000);

        List<Socket> held = new ArrayList<>();
        String[] names = {"Alex", "Steve", "Herobrine", "Notch", "Dream", "TommyInnit", "Technoblade", "Ranboo"};
        for (int i = 0; i < names.length; i++) {
            Socket s = new Socket("127.0.0.1", 26300);
            s.getOutputStream().write(handshake(2)); s.getOutputStream().write(loginStart(names[i])); s.getOutputStream().flush();
            held.add(s);
            Thread.sleep(100);
        }
        long end = System.currentTimeMillis() + Long.parseLong(args[3]) * 1000;
        Random rnd = new Random(1);
        while (System.currentTimeMillis() < end) {
            for (int i = 0; i < 4; i++) {
                try (Socket s = new Socket("127.0.0.1", 26300)) {
                    s.setSoTimeout(1000);
                    s.getOutputStream().write(handshake(1)); s.getOutputStream().write(new byte[]{1, 0}); s.getOutputStream().flush();
                    s.getInputStream().read();
                } catch (IOException ignored) { }
            }
            for (Socket s : held) { try { s.getOutputStream().write(new byte[200 + rnd.nextInt(800)]); s.getOutputStream().flush(); } catch (IOException ignored) { } }
            Thread.sleep(500);
        }
        vector.destroyForcibly();
        System.exit(0);
    }

    static void startBackend(int port) throws Exception {
        ServerSocket ss = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (true) {
                try { Socket s = ss.accept(); Thread.startVirtualThread(() -> serve(s, port)); } catch (IOException e) { return; }
            }
        });
        t.setDaemon(false); t.start();
    }

    static void serve(Socket s, int port) {
        try (s) {
            s.setSoTimeout(600000);
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            readPacket(in);
            byte[] first = readPacket(in);
            if (first.length == 1) {
                String json = "{\"version\":{\"name\":\"Paper 1.21\",\"protocol\":767},\"players\":{\"max\":100,\"online\":" + (port % 7 + 3) + "},\"description\":{\"text\":\"Demo\"}}";
                out.write(str(0, json)); out.flush(); readPacket(in);
            } else {
                while (in.read() != -1) { /* swallow, keep session open */ }
            }
        } catch (Exception ignored) { }
    }

    static void vi(ByteArrayOutputStream o, int v) { while ((v & ~0x7F) != 0) { o.write((v & 0x7F) | 0x80); v >>>= 7; } o.write(v); }
    static int rv(DataInputStream in) throws IOException { int r = 0, sh = 0; while (true) { int b = in.readUnsignedByte(); r |= (b & 0x7F) << sh; if ((b & 0x80) == 0) return r; sh += 7; } }
    static byte[] readPacket(DataInputStream in) throws IOException { int l = rv(in); byte[] b = new byte[l]; in.readFully(b); return b; }
    static byte[] pack(byte[] b) { ByteArrayOutputStream o = new ByteArrayOutputStream(); vi(o, b.length); o.writeBytes(b); return o.toByteArray(); }
    static byte[] str(int id, String s) { ByteArrayOutputStream b = new ByteArrayOutputStream(); vi(b, id); byte[] sb = s.getBytes(); vi(b, sb.length); b.writeBytes(sb); return pack(b.toByteArray()); }
    static byte[] handshake(int state) { ByteArrayOutputStream b = new ByteArrayOutputStream(); vi(b, 0); vi(b, 767); byte[] h = "localhost".getBytes(); vi(b, h.length); b.writeBytes(h); b.write(26300 >> 8); b.write(26300 & 0xFF); vi(b, state); return pack(b.toByteArray()); }
    static byte[] loginStart(String n) { ByteArrayOutputStream b = new ByteArrayOutputStream(); vi(b, 0); byte[] x = n.getBytes(); vi(b, x.length); b.writeBytes(x); b.writeBytes(new byte[16]); return pack(b.toByteArray()); }
}
