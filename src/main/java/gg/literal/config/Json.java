package gg.literal.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer with no external dependencies.
 *
 * <p>Parsed values map to: {@link Map} (insertion-ordered), {@link List}, {@link String},
 * {@link Long} (integral numbers), {@link Double} (other numbers), {@link Boolean} and {@code null}.
 */
public final class Json {

    private final String src;
    private int pos;

    private Json(final String src) {
        this.src = src;
    }

    /** Parses a complete JSON document. Throws {@link IllegalArgumentException} on malformed input. */
    public static Object parse(final String text) {
        final Json parser = new Json(text);
        parser.skipWhitespace();
        final Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.pos != parser.src.length()) {
            throw parser.error("Unexpected trailing content");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(final String text) {
        final Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Expected a JSON object at the top level");
        }
        return (Map<String, Object>) value;
    }

    // ------------------------------------------------------------------ reader

    private Object readValue() {
        if (pos >= src.length()) {
            throw error("Unexpected end of input");
        }
        final char c = src.charAt(pos);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expectLiteral("true");
                return Boolean.TRUE;
            case 'f':
                expectLiteral("false");
                return Boolean.FALSE;
            case 'n':
                expectLiteral("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return readNumber();
                }
                throw error("Unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> readObject() {
        final Map<String, Object> map = new LinkedHashMap<>();
        pos++; // {
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("Expected a string key");
            }
            final String key = readString();
            skipWhitespace();
            if (peek() != ':') {
                throw error("Expected ':' after key \"" + key + "\"");
            }
            pos++;
            skipWhitespace();
            map.put(key, readValue());
            skipWhitespace();
            final char next = peek();
            pos++;
            if (next == '}') {
                return map;
            }
            if (next != ',') {
                pos--;
                throw error("Expected ',' or '}'");
            }
        }
    }

    private List<Object> readArray() {
        final List<Object> list = new ArrayList<>();
        pos++; // [
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(readValue());
            skipWhitespace();
            final char next = peek();
            pos++;
            if (next == ']') {
                return list;
            }
            if (next != ',') {
                pos--;
                throw error("Expected ',' or ']'");
            }
        }
    }

    private String readString() {
        pos++; // opening quote
        final StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw error("Unterminated string");
            }
            final char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                throw error("Unterminated escape sequence");
            }
            final char esc = src.charAt(pos++);
            switch (esc) {
                case '"', '\\', '/' -> sb.append(esc);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > src.length()) {
                        throw error("Truncated \\u escape");
                    }
                    try {
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("Invalid \\u escape");
                    }
                    pos += 4;
                }
                default -> throw error("Invalid escape '\\" + esc + "'");
            }
        }
    }

    private Object readNumber() {
        final int start = pos;
        boolean integral = true;
        if (peek() == '-') {
            pos++;
        }
        while (pos < src.length()) {
            final char c = src.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                integral = false;
                pos++;
            } else {
                break;
            }
        }
        final String token = src.substring(start, pos);
        try {
            if (integral) {
                return Long.parseLong(token);
            }
            return Double.parseDouble(token);
        } catch (NumberFormatException ex) {
            pos = start;
            throw error("Invalid number '" + token + "'");
        }
    }

    private void expectLiteral(final String literal) {
        if (!src.startsWith(literal, pos)) {
            throw error("Expected '" + literal + "'");
        }
        pos += literal.length();
    }

    private char peek() {
        if (pos >= src.length()) {
            throw error("Unexpected end of input");
        }
        return src.charAt(pos);
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private IllegalArgumentException error(final String message) {
        int line = 1;
        int col = 1;
        for (int i = 0; i < Math.min(pos, src.length()); i++) {
            if (src.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new IllegalArgumentException(message + " (line " + line + ", column " + col + ")");
    }

    // ------------------------------------------------------------------ writer

    /** Serialises a value tree produced by {@link #parse(String)} (or built from the same types). */
    public static String write(final Object value) {
        final StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(final StringBuilder sb, final Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean || value instanceof Long || value instanceof Integer) {
            sb.append(value);
        } else if (value instanceof Number n) {
            final double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                write(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            sb.append('[');
            boolean first = true;
            for (final Object element : iterable) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, element);
            }
            sb.append(']');
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    /** Appends {@code s} as a quoted, escaped JSON string. */
    public static void writeString(final StringBuilder sb, final String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    public static String quote(final String s) {
        final StringBuilder sb = new StringBuilder(s.length() + 2);
        writeString(sb, s);
        return sb.toString();
    }
}
