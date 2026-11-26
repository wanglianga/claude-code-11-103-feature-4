package com.medwaste.common;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** JSONB 读写共用的 ObjectMapper。 */
public final class Jsonx {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Jsonx() {}

    public static String write(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T read(String json, Class<T> type) {
        try {
            if (json == null || json.isBlank()) {
                if (type == java.util.List.class) return (T) java.util.List.of();
                return null;
            }
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 反序列化失败: " + json, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static java.util.List<Object> readList(String json) {
        java.util.List<Object> r = read(json, java.util.List.class);
        return r == null ? java.util.List.of() : r;
    }
}
