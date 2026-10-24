package vip.mystery0.spw.navidrome.net;

import java.util.*;

/** 有深度限制的 JSON 读取器，不执行任何服务端内容。 */
public final class Json {
    private final String text;
    private int pos;
    private Json(String text) { this.text = text; }
    public static Object parse(String text) {
        Json p = new Json(text);
        Object v = p.value(0); p.space();
        if (p.pos != text.length()) throw new IllegalArgumentException("Invalid JSON trailing data");
        return v;
    }
    private void space() { while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++; }
    private char take() { if (pos >= text.length()) throw new IllegalArgumentException("Truncated JSON"); return text.charAt(pos++); }
    private Object value(int depth) {
        if (depth > 64) throw new IllegalArgumentException("JSON nesting limit");
        space(); char c = take();
        if (c == '"') return string();
        if (c == '{') {
            Map<String,Object> m = new LinkedHashMap<>(); space();
            if (pos < text.length() && text.charAt(pos) == '}') { pos++; return m; }
            do {
                space(); if (take() != '"') throw new IllegalArgumentException("Invalid JSON key");
                String k = string(); space(); if (take() != ':') throw new IllegalArgumentException("Invalid JSON separator");
                m.put(k, value(depth+1)); space(); c = take();
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("Invalid JSON object");
            } while (true);
        }
        if (c == '[') {
            List<Object> list = new ArrayList<>(); space();
            if (pos < text.length() && text.charAt(pos) == ']') { pos++; return list; }
            do {
                list.add(value(depth+1)); space(); c = take();
                if (c == ']') return list;
                if (c != ',') throw new IllegalArgumentException("Invalid JSON array");
            } while (true);
        }
        int start = pos-1;
        while (pos < text.length() && ",]} \t\r\n".indexOf(text.charAt(pos)) < 0) pos++;
        String word = text.substring(start, pos);
        return switch(word) {
            case "true" -> true; case "false" -> false; case "null" -> null;
            default -> {
                if (!word.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw new IllegalArgumentException("Invalid JSON value");
                yield new java.math.BigDecimal(word);
            }
        };
    }
    private String string() {
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = take(); if (c == '"') return b.toString();
            if (c < 32) throw new IllegalArgumentException("Invalid JSON control character");
            if (c == '\\') {
                c = take();
                switch(c) {
                    case '"', '\\', '/' -> b.append(c);
                    case 'n' -> b.append('\n'); case 'r' -> b.append('\r'); case 't' -> b.append('\t');
                    case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                    case 'u' -> { if (pos+4 > text.length()) throw new IllegalArgumentException("Invalid JSON escape"); b.append((char)Integer.parseInt(text.substring(pos,pos+4),16)); pos+=4; }
                    default -> throw new IllegalArgumentException("Invalid JSON escape");
                }
            } else b.append(c);
        }
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Object v) {
        return v instanceof Map<?,?> ? (Map<String,Object>)v : Map.of();
    }
    public static String str(Map<String,Object> m, String key) { Object v = m.get(key); return v == null ? "" : v.toString(); }
    public static long number(Map<String,Object> m, String key) { Object v = m.get(key); return v instanceof Number n ? n.longValue() : 0; }
    public static List<Map<String,Object>> objects(Object v) {
        if (!(v instanceof List<?> a)) return List.of();
        return a.stream().map(Json::object).toList();
    }
}
