package gg.literal.detect;

import gg.literal.log.TerminalLogger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class BackendDetector {

    private BackendDetector() {
    }

    public static BackendType detect(final String host, final int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            socket.setSoTimeout(3000);

            final OutputStream out = socket.getOutputStream();
            final InputStream in = socket.getInputStream();

            // Handshake packet
            final ByteArrayOutputStream handshakePayload = new ByteArrayOutputStream();
            writeVarInt(handshakePayload, 0x00);   // packet ID
            writeVarInt(handshakePayload, 767);    // protocol version (1.21.4)
            writeString(handshakePayload, host);
            handshakePayload.write((port >> 8) & 0xFF);
            handshakePayload.write(port & 0xFF);
            writeVarInt(handshakePayload, 1);      // next state = STATUS

            final byte[] payload = handshakePayload.toByteArray();
            final ByteArrayOutputStream handshakePacket = new ByteArrayOutputStream();
            writeVarInt(handshakePacket, payload.length);
            handshakePacket.write(payload);
            out.write(handshakePacket.toByteArray());

            // Status request: length=1, packet ID=0x00
            out.write(new byte[]{1, 0});
            out.flush();

            // Read status response
            readVarInt(in); // total packet length (discard)
            final int packetId = readVarInt(in);
            if (packetId != 0x00) {
                return BackendType.UNKNOWN;
            }

            final int jsonLen = readVarInt(in);
            if (jsonLen <= 0 || jsonLen > 131072) {
                return BackendType.UNKNOWN;
            }

            final String json = new String(in.readNBytes(jsonLen), StandardCharsets.UTF_8);
            final BackendType type = classify(json);
            final String versionName = extractVersionName(json);

            TerminalLogger.info("Backend probe -> " + type.name()
                + (versionName != null ? " [" + versionName + "]" : "")
                + " at " + host + ":" + port);

            return type;
        } catch (Exception ex) {
            TerminalLogger.warn("Backend probe failed (" + host + ":" + port + "): " + ex.getMessage());
            return BackendType.UNKNOWN;
        }
    }

    private static BackendType classify(final String json) {
        final String lower = json.toLowerCase();
        if (lower.contains("velocity")) {
            return BackendType.VELOCITY;
        }
        if (lower.contains("bungeecord") || lower.contains("waterfall") || lower.contains("flamecord")) {
            return BackendType.BUNGEECORD;
        }
        if (lower.contains("paper") || lower.contains("spigot") || lower.contains("craftbukkit") || lower.contains("purpur")) {
            return BackendType.PAPER;
        }
        return BackendType.UNKNOWN;
    }

    private static String extractVersionName(final String json) {
        final int nameIdx = json.indexOf("\"name\"");
        if (nameIdx < 0) {
            return null;
        }
        final int colonIdx = json.indexOf(":", nameIdx);
        if (colonIdx < 0) {
            return null;
        }
        final int startQuote = json.indexOf("\"", colonIdx);
        if (startQuote < 0) {
            return null;
        }
        final int endQuote = json.indexOf("\"", startQuote + 1);
        if (endQuote < 0) {
            return null;
        }
        return json.substring(startQuote + 1, endQuote);
    }

    private static void writeVarInt(final OutputStream out, int value) throws IOException {
        while ((value & -128) != 0) {
            out.write(value & 127 | 128);
            value >>>= 7;
        }
        out.write(value);
    }

    private static void writeString(final OutputStream out, final String s) throws IOException {
        final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static int readVarInt(final InputStream in) throws IOException {
        int numRead = 0;
        int result = 0;
        int read;
        do {
            read = in.read();
            if (read == -1) {
                throw new IOException("Stream ended during VarInt read");
            }
            result |= (read & 0x7F) << (7 * numRead);
            if (++numRead > 5) {
                throw new IOException("VarInt too large");
            }
        } while ((read & 0x80) != 0);
        return result;
    }
}
