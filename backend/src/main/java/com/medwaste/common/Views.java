package com.medwaste.common;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 把 JdbcTemplate 行映射为驼峰键 Map，并自动解析 jsonb(PGobject)。 */
public final class Views {
    private Views() {}

    public static Map<String, Object> of(Map<String, Object> row) {
        if (row == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            out.put(camel(e.getKey()), normalize(e.getValue()));
        }
        return out;
    }

    public static List<Map<String, Object>> list(List<Map<String, Object>> rows) {
        List<Map<String, Object>> r = new ArrayList<>();
        for (Map<String, Object> row : rows) r.add(of(row));
        return r;
    }

    public static String camel(String snake) {
        if (snake == null) return null;
        snake = snake.toLowerCase();
        if (!snake.contains("_")) return snake;
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') { upper = true; continue; }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return sb.toString();
    }

    private static Object normalize(Object v) {
        if (v == null) return null;
        if ("org.postgresql.util.PGobject".equals(v.getClass().getName())) {
            try {
                Method m = v.getClass().getMethod("getValue");
                String s = (String) m.invoke(v);
                if (s == null) return null;
                return Jsonx.MAPPER.readValue(s, Object.class);
            } catch (Exception e) {
                return v.toString();
            }
        }
        return v;
    }
}
