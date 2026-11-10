package gg.literal.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void parsesScalars() {
        assertEquals(42L, Json.parse("42"));
        assertEquals(-7L, Json.parse("-7"));
        assertEquals(1.5, Json.parse("1.5"));
        assertEquals(Boolean.TRUE, Json.parse("true"));
        assertEquals(Boolean.FALSE, Json.parse("false"));
        assertNull(Json.parse("null"));
        assertEquals("hi", Json.parse("\"hi\""));
    }

    @Test
    void parsesNestedStructures() {
        final Map<String, Object> root = Json.parseObject(
            "{\"a\":[1,2,{\"b\":true}],\"c\":{\"d\":\"e\"}}");
        final List<?> a = assertInstanceOf(List.class, root.get("a"));
        assertEquals(3, a.size());
        assertEquals(Boolean.TRUE, ((Map<?, ?>) a.get(2)).get("b"));
        assertEquals("e", ((Map<?, ?>) root.get("c")).get("d"));
    }

    @Test
    void keepsKeyOrder() {
        final Map<String, Object> root = Json.parseObject("{\"z\":1,\"a\":2,\"m\":3}");
        assertEquals(List.of("z", "a", "m"), List.copyOf(root.keySet()));
    }

    @Test
    void decodesEscapes() {
        // JSON text: "a\"b\\c\ndé"
        final String json = "\"a\\\"b\\\\c\\nd" + "\\" + "u00e9\"";
        assertEquals("a\"b\\c\ndé", Json.parse(json));
    }

    @Test
    void roundTripsThroughWriter() {
        final String source = "{\"name\":\"x\\ty\",\"n\":[1,2.5,null,true],\"o\":{}}";
        assertEquals(source, Json.write(Json.parse(source)));
    }

    @Test
    void writerEscapesControlCharacters() {
        assertEquals("\"a" + "\\" + "u0001b\"", Json.write("a\u0001b"));
    }

    @Test
    void errorsReportLineAndColumn() {
        final IllegalArgumentException ex =
            assertThrows(IllegalArgumentException.class, () -> Json.parse("{\n  \"a\": }"));
        assertTrue(ex.getMessage().contains("line 2"), ex.getMessage());
    }

    @Test
    void rejectsTrailingContentAndNonObjects() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{} x"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("[1]"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("\"unterminated"));
    }
}
