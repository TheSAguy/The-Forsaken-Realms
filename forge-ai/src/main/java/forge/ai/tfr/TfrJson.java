package forge.ai.tfr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TFR round 1018: a small JSON reader for {@code res/ai/tfr-rules.json}. forge-ai has no JSON library, and the rules
 * file must be readable on desktop and Android alike without a new dependency. Objects become {@link LinkedHashMap},
 * arrays {@link ArrayList}, numbers {@link Double}, plus String, Boolean and null.
 */
final class TfrJson {
    private final String s;
    private int i;

    private TfrJson(final String text) {
        s = text;
    }

    static Object parse(final String text) {
        final TfrJson p = new TfrJson(text);
        final Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw p.error("text after the end of the document");
        }
        return v;
    }

    private Object value() {
        ws();
        if (i >= s.length()) {
            throw error("unexpected end");
        }
        final char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': return literal("true", Boolean.TRUE);
            case 'f': return literal("false", Boolean.FALSE);
            case 'n': return literal("null", null);
            default: return number();
        }
    }

    private Map<String, Object> object() {
        final Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            final String k = string();
            ws();
            expect(':');
            m.put(k, value());
            ws();
            if (peek() == ',') {
                i++;
                continue;
            }
            expect('}');
            return m;
        }
    }

    private List<Object> array() {
        final List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') {
            i++;
            return l;
        }
        while (true) {
            l.add(value());
            ws();
            if (peek() == ',') {
                i++;
                continue;
            }
            expect(']');
            return l;
        }
    }

    private String string() {
        expect('"');
        final StringBuilder b = new StringBuilder();
        while (i < s.length()) {
            final char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            final char e = s.charAt(i++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default: b.append(e);
            }
        }
        throw error("unterminated string");
    }

    private Object number() {
        final int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        if (start == i) {
            throw error("unexpected character '" + s.charAt(i) + "'");
        }
        return Double.valueOf(s.substring(start, i));
    }

    private Object literal(final String word, final Object v) {
        if (!s.startsWith(word, i)) {
            throw error("expected " + word);
        }
        i += word.length();
        return v;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private void expect(final char c) {
        if (peek() != c) {
            throw error("expected '" + c + "'");
        }
        i++;
    }

    private IllegalArgumentException error(final String what) {
        int line = 1;
        for (int k = 0; k < Math.min(i, s.length()); k++) {
            if (s.charAt(k) == '\n') {
                line++;
            }
        }
        return new IllegalArgumentException(what + " (line " + line + ")");
    }
}
