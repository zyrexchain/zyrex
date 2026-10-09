package org.zyrexchain.desktop;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small JSON codec that preserves all protocol integers exactly. */
public final class Json {
    private Json() { }

    public static Object parse(String text) {
        if (text == null || text.length() > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("JSON document is missing or too large");
        }
        Parser parser = new Parser(text);
        Object result = parser.value(0);
        parser.space();
        if (parser.position != text.length()) {
            throw new IllegalArgumentException("Unexpected data after JSON document");
        }
        return result;
    }

    public static String stringify(Object value) {
        StringBuilder result = new StringBuilder();
        append(result, value, 0);
        return result.toString();
    }

    public static Map<String, Object> object(Object... entries) {
        if ((entries.length & 1) != 0) {
            throw new IllegalArgumentException("JSON object requires key/value pairs");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            if (!(entries[i] instanceof String) || result.containsKey(entries[i])) {
                throw new IllegalArgumentException("JSON object requires unique string keys");
            }
            result.put((String) entries[i], entries[i + 1]);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("Expected a JSON array");
        }
        return (List<Object>) value;
    }

    public static long asLong(Object value) {
        if (value instanceof BigInteger) {
            return ((BigInteger) value).longValueExact();
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).longValueExact();
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof String && ((String) value).matches("-?[0-9]{1,20}")) {
            return Long.parseLong((String) value);
        }
        throw new IllegalArgumentException("Expected an exact integer");
    }

    private static void append(StringBuilder out, Object value, int depth) {
        if (depth > 64) {
            throw new IllegalArgumentException("JSON nesting is too deep");
        }
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            string(out, (String) value);
        } else if (value instanceof Boolean || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            out.append(value);
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> item : ((Map<?, ?>) value).entrySet()) {
                if (!(item.getKey() instanceof String)) {
                    throw new IllegalArgumentException("JSON object keys must be strings");
                }
                if (!first) {
                    out.append(',');
                }
                first = false;
                string(out, (String) item.getKey());
                out.append(':');
                append(out, item.getValue(), depth + 1);
            }
            out.append('}');
        } else if (value instanceof Iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                append(out, item, depth + 1);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value type");
        }
    }

    private static void string(StringBuilder out, String text) {
        validateSurrogates(text);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 32) {
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    private static void validateSurrogates(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    throw new IllegalArgumentException("Invalid Unicode surrogate in JSON string");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("Invalid Unicode surrogate in JSON string");
            }
        }
    }

    private static final class Parser {
        private final String text;
        private int position;

        private Parser(String text) { this.text = text; }

        private void space() {
            while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) {
                position++;
            }
        }

        private Object value(int depth) {
            if (depth > 64) {
                throw invalid();
            }
            space();
            if (position == text.length()) {
                throw invalid();
            }
            char c = text.charAt(position);
            if (c == '"') {
                return string();
            }
            if (c == '{') {
                position++;
                Map<String, Object> result = new LinkedHashMap<>();
                if (take('}')) {
                    return result;
                }
                do {
                    space();
                    String key = string();
                    if (result.containsKey(key) || !take(':')) {
                        throw invalid();
                    }
                    result.put(key, value(depth + 1));
                } while (take(','));
                if (!take('}')) {
                    throw invalid();
                }
                return result;
            }
            if (c == '[') {
                position++;
                List<Object> result = new ArrayList<>();
                if (take(']')) {
                    return result;
                }
                do {
                    result.add(value(depth + 1));
                } while (take(','));
                if (!take(']')) {
                    throw invalid();
                }
                return result;
            }
            if (text.startsWith("true", position)) {
                position += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", position)) {
                position += 5;
                return Boolean.FALSE;
            }
            if (text.startsWith("null", position)) {
                position += 4;
                return null;
            }
            return number();
        }

        private boolean take(char c) {
            space();
            if (position < text.length() && text.charAt(position) == c) {
                position++;
                return true;
            }
            return false;
        }

        private String string() {
            if (position >= text.length() || text.charAt(position++) != '"') {
                throw invalid();
            }
            StringBuilder result = new StringBuilder();
            while (position < text.length()) {
                char c = text.charAt(position++);
                if (c == '"') {
                    String decoded = result.toString();
                    validateSurrogates(decoded);
                    return decoded;
                }
                if (c < 32) {
                    throw invalid();
                }
                if (c != '\\') {
                    result.append(c);
                    continue;
                }
                if (position == text.length()) {
                    throw invalid();
                }
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case '"': case '\\': case '/': result.append(escaped); break;
                    case 'b': result.append('\b'); break;
                    case 'f': result.append('\f'); break;
                    case 'n': result.append('\n'); break;
                    case 'r': result.append('\r'); break;
                    case 't': result.append('\t'); break;
                    case 'u':
                        if (position + 4 > text.length()) {
                            throw invalid();
                        }
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            int digit = Character.digit(text.charAt(position++), 16);
                            if (digit < 0) {
                                throw invalid();
                            }
                            code = (code << 4) | digit;
                        }
                        result.append((char) code);
                        break;
                    default: throw invalid();
                }
            }
            throw invalid();
        }

        private Object number() {
            int start = position;
            if (position < text.length() && text.charAt(position) == '-') {
                position++;
            }
            if (position == text.length() || !digit(text.charAt(position))) {
                throw invalid();
            }
            if (text.charAt(position++) != '0') {
                while (position < text.length() && digit(text.charAt(position))) {
                    position++;
                }
            }
            boolean decimal = false;
            if (position < text.length() && text.charAt(position) == '.') {
                decimal = true;
                position++;
                digits();
            }
            if (position < text.length() && "eE".indexOf(text.charAt(position)) >= 0) {
                decimal = true;
                position++;
                if (position < text.length() && "+-".indexOf(text.charAt(position)) >= 0) {
                    position++;
                }
                digits();
            }
            if (position - start > 256) {
                throw invalid();
            }
            String token = text.substring(start, position);
            try {
                return decimal ? new BigDecimal(token) : new BigInteger(token);
            } catch (NumberFormatException e) {
                throw invalid();
            }
        }

        private void digits() {
            int start = position;
            while (position < text.length() && digit(text.charAt(position))) {
                position++;
            }
            if (position == start) {
                throw invalid();
            }
        }

        private boolean digit(char c) { return c >= '0' && c <= '9'; }

        private IllegalArgumentException invalid() {
            return new IllegalArgumentException("Invalid JSON document");
        }
    }
}
