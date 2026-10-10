package com.syh.luna.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器（零依赖，Android 与桌面通用）。
 * 支持 object / array / string(含 unicode 转义) / number / true / false / null。
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) { this.s = s; this.i = 0; }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        return v;
    }

    /** 对齐 python json.loads：整串（允许尾部空白）必须正好是一个 JSON 值，否则返回 null */
    public static Object parseStrict(String text) {
        if (text == null) return null;
        try {
            Json p = new Json(text);
            p.ws();
            Object v = p.value();
            p.ws();
            if (p.i != text.length()) return null;
            return v;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    /** 解析一个 JSON 值的结果：值 + 消耗掉的字符数 */
    public static final class Prefix {
        public final Object value;
        public final int consumed;

        Prefix(Object v, int c) { this.value = v; this.consumed = c; }
    }

    /**
     * 从 start 位置起解析**一个** JSON 值，返回它和消耗长度。
     *
     * 与 parseStrict 的关键差别：**不要求解析到串尾**。
     * 宿主的 sqlite WAL、keva 的 .sgv 都是「二进制头尾 + 中间夹 JSON」的容器，
     * 用整串语义必然整体解析失败、什么都抽不到；于是这些文件里最新鲜的数据
     * （比如 cache_load_records 的 md5→vid 映射）就全丢了。
     */
    public static Prefix parsePrefix(String text, int start) {
        if (text == null || start < 0 || start >= text.length()) return null;
        char c = text.charAt(start);
        if (c != '{' && c != '[') return null;
        try {
            Json p = new Json(text);
            p.i = start;
            Object v = p.value();
            if (v == null) return null;
            int n = p.i - start;
            if (n <= 0) return null;
            return new Prefix(v, n);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static Map<String, Object> parseStrictObject(String text) {
        Object v = parseStrict(text);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static String str(Object o) { return o == null ? null : String.valueOf(o); }

    public static String str(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break;
        }
    }

    private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }

    private Object value() {
        char c = peek();
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

    private Object literal(String lit, Object v) {
        if (s.startsWith(lit, i)) { i += lit.length(); return v; }
        throw new IllegalStateException("bad literal at " + i);
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        i++; // {
        ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            String k = string();
            ws();
            if (peek() != ':') throw new IllegalStateException("expect ':' at " + i);
            i++;
            ws();
            m.put(k, value());
            ws();
            char c = peek();
            if (c == ',') { i++; continue; }
            if (c == '}') { i++; return m; }
            throw new IllegalStateException("bad object at " + i);
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<Object>();
        i++; // [
        ws();
        if (peek() == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = peek();
            if (c == ',') { i++; continue; }
            if (c == ']') { i++; return l; }
            throw new IllegalStateException("bad array at " + i);
        }
    }

    private String string() {
        if (peek() != '"') throw new IllegalStateException("expect '\"' at " + i);
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: sb.append(e);
            }
        }
        throw new IllegalStateException("unterminated string");
    }

    private Object number() {
        int st = i;
        while (i < s.length()) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') i++;
            else break;
        }
        String t = s.substring(st, i);
        if (t.isEmpty()) throw new IllegalStateException("bad number at " + st);
        if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
            try { return Long.valueOf(Long.parseLong(t)); } catch (NumberFormatException ignore) { }
        }
        return Double.valueOf(Double.parseDouble(t));
    }

    // ------------------------------------------------------------------ 写
    public static String escape(String v) {
        if (v == null) return "null";
        StringBuilder sb = new StringBuilder(v.length() + 16);
        sb.append('"');
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
