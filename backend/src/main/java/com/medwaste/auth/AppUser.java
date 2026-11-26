package com.medwaste.auth;

import java.util.Map;

/** 登录用户视图。 */
public record AppUser(
        long id,
        String username,
        String displayName,
        String role,
        Long orgId,
        String orgCode,
        String orgName,
        String orgType,
        String title
) {
    public static AppUser from(Map<String, Object> r) {
        return new AppUser(
                ((Number) r.get("id")).longValue(),
                (String) r.get("username"),
                (String) r.get("display_name"),
                (String) r.get("role"),
                r.get("org_id") == null ? null : ((Number) r.get("org_id")).longValue(),
                (String) r.get("org_code"),
                (String) r.get("org_name"),
                (String) r.get("org_type"),
                (String) r.get("title")
        );
    }

    public boolean is(String... roles) {
        for (String r : roles) if (r.equals(this.role)) return true;
        return false;
    }
}
