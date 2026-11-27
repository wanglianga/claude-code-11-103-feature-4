package com.medwaste.common;

import java.util.LinkedHashMap;
import java.util.Map;

/** 业务规则异常：携带稳定错误码，前端与验收脚本据此判定。 */
public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final Map<String, Object> extra = new LinkedHashMap<>();

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(404, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(403, code, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(401, "UNAUTHORIZED", message);
    }

    public ApiException with(String key, Object value) {
        this.extra.put(key, value);
        return this;
    }

    public int getStatus() { return status; }
    public String getCode() { return code; }
    public Map<String, Object> getExtra() { return extra; }
}
