package com.medwaste.common;

import java.util.UUID;

public final class Codes {
    private Codes() {}

    /** 可读单号：前缀-8位十六进制。 */
    public static String next(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }
}
