package com.enterprise.iam.kernel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minimal, dependency-free JSON reader/writer used where the Core needs deterministic output or must stay
 * framework-independent: audit canonicalization (sorted keys), outbox payloads, and the Vault HTTP client.
 * Supports objects ({@link Map}), arrays ({@link List}), strings, numbers ({@link BigDecimal} on read),
 * booleans and null. Not a general-purpose replacement for Jackson in the web layer.
 */
public final class Json {

    private static final int MAX_DEPTH = 64;

    private Json() {
    }

    // ---------------------------------------------------------------- writing

    /** Serializes with object keys in natural (sorted) order — the canonical form used for hashing. */
    public static String writeCanonical(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, true, 0);
        return sb.toString();
    }

    /** Serializes preserving map iteration order. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, false, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, boolean sorted, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("JSON nesting too deep");
        }
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof Boolean b) {
            sb.append(b);
        } else if (v instanceof Number n) {
            if (n instanceof Double d && (d.isNaN() || d.isInfinite()) || n instanceof Float f && (f.isNaN() || f.isInfinite())) {
                throw new IllegalArgumentException("Non-finite number");
            }
            sb.append(n instanceof BigDecimal bd ? bd.toPlainString() : n.toString());
        } else if (v instanceof Map<?, ?> m) {
            Map<?, ?> map = sorted ? new TreeMap<>(stringKeys(m)) : m;
            sb.append('{');
            Iterator<? extends Map.Entry<?, ?>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue(), sorted, depth + 1);
                if (it.hasNext()) {
                    sb.append(',');
                }
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> list) {
            sb.append('[');
            Iterator<?> it = list.iterator();
            while (it.hasNext()) {
                write(sb, it.next(), sorted, depth + 1);
                if (it.hasNext()) {
                    sb.append(',');
                }
            }
            sb.append(']');
        } else if (v instanceof Secret) {
            throw new IllegalArgumentException("Secret values must never be serialized");
        } else {
            quote(sb, v.toString());
        }
    }

    private static Map<String, Object> stringKeys(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, val) -> out.put(String.valueOf(k), val));
        return out;
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------- reading

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value(0);
        p.skipWs();
        if (p.pos != text.length()) {
            throw p.error("Unexpected trailing content");
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("JSON value is not an object");
        }
        return (Map<String, Object>) v;
    }

    /** Navigates nested objects: {@code path(obj, "auth", "client_token")}; returns null if absent. */
    @SuppressWarnings("unchecked")
    public static Object path(Map<String, Object> root, String... keys) {
        Object cur = root;
        for (String k : keys) {
            if (!(cur instanceof Map)) {
                return null;
            }
            cur = ((Map<String, Object>) cur).get(k);
        }
        return cur;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException(msg + " at position " + pos);
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("JSON nesting too deep");
            }
            if (pos >= s.length()) {
                throw error("Unexpected end");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Object literal(String word, Object v) {
            if (!s.startsWith(word, pos)) {
                throw error("Invalid literal");
            }
            pos += word.length();
            return v;
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            skipWs();
            if (peek('}')) {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (!peek('"')) {
                    throw error("Expected string key");
                }
                String k = string();
                skipWs();
                expect(':');
                skipWs();
                m.put(k, value(depth + 1));
                skipWs();
                if (peek(',')) {
                    pos++;
                } else if (peek('}')) {
                    pos++;
                    return m;
                } else {
                    throw error("Expected , or }");
                }
            }
        }

        List<Object> array(int depth) {
            List<Object> l = new ArrayList<>();
            pos++;
            skipWs();
            if (peek(']')) {
                pos++;
                return l;
            }
            while (true) {
                skipWs();
                l.add(value(depth + 1));
                skipWs();
                if (peek(',')) {
                    pos++;
                } else if (peek(']')) {
                    pos++;
                    return l;
                } else {
                    throw error("Expected , or ]");
                }
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw error("Unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw error("Bad escape");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw error("Bad unicode escape");
                            }
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw error("Bad escape");
                    }
                } else if (c < 0x20) {
                    throw error("Control character in string");
                } else {
                    sb.append(c);
                }
            }
        }

        BigDecimal number() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            if (start == pos) {
                throw error("Unexpected character");
            }
            try {
                return new BigDecimal(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw error("Invalid number");
            }
        }

        boolean peek(char c) {
            return pos < s.length() && s.charAt(pos) == c;
        }

        void expect(char c) {
            if (!peek(c)) {
                throw error("Expected '" + c + "'");
            }
            pos++;
        }
    }
}
