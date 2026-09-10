package forge.adventure.agent;

import com.badlogic.gdx.math.Vector2;

import java.util.Map;

/**
 * Round 161: a deliberately tiny JSON writer for the agent bridge. libGDX's Json can serialise maps
 * too, but it decorates unknown value types with class names and quotes numbers under some output
 * types; the bridge wants plain, predictable JSON that curl and Python read without surprises.
 * Handles Map, Iterable, arrays, String, Number, Boolean, Vector2 and null; anything else is
 * written as its toString().
 */
final class Jsons {
    private Jsons() {}

    static String write(Object value) {
        StringBuilder sb = new StringBuilder(4096);
        write(sb, value);
        return sb.toString();
    }

    static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            string(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v) ? "true" : "false");
        } else if (v instanceof Float || v instanceof Double) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d))
                sb.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15)
                sb.append((long) d);
            else
                sb.append(Math.round(d * 100.0) / 100.0);
        } else if (v instanceof Number) {
            sb.append(v);
        } else if (v instanceof Vector2) {
            Vector2 p = (Vector2) v;
            sb.append('[').append(Math.round(p.x)).append(',').append(Math.round(p.y)).append(']');
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                string(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Iterable<?>) v) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else if (v.getClass().isArray()) {
            sb.append('[');
            int n = java.lang.reflect.Array.getLength(v);
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(',');
                write(sb, java.lang.reflect.Array.get(v, i));
            }
            sb.append(']');
        } else {
            string(sb, v.toString());
        }
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    /** Strip the markup the game's labels carry ("[%88]", "[GREEN]", "{CAROUSEL}") for the agent's eyes. */
    static String plain(String s) {
        if (s == null) return "";
        return s.replaceAll("\\[[^\\]]*\\]", "").replaceAll("\\{[^}]*\\}", "").replace("\n", " ").trim();
    }
}
