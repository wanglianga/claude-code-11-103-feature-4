package com.medwaste.auth;

import com.medwaste.common.ApiException;
import com.medwaste.common.Db;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {
    private final Db db;
    // demo 用内存会话：token -> username；reset 后用户名保持稳定
    private final Map<String, String> sessions = new ConcurrentHashMap<>();

    public AuthService(Db db) {
        this.db = db;
    }

    public record LoginResult(String token, AppUser user) {}

    public LoginResult login(String username, String password) {
        Map<String, Object> u = db.maybeOne("""
                SELECT u.*, o.code AS org_code, o.name AS org_name, o.org_type
                FROM app_users u LEFT JOIN orgs o ON o.id = u.org_id
                WHERE u.username = ?
                """, username).orElseThrow(() ->
                ApiException.unauthorized("用户名或密码错误"));
        if (!password.equals(u.get("password"))) {
            throw ApiException.unauthorized("用户名或密码错误");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        sessions.put(token, username);
        return new LoginResult(token, AppUser.from(u));
    }

    public AppUser resolveToken(String token) {
        if (token == null || token.isBlank()) return null;
        String username = null;
        if (token.startsWith("Bearer ")) token = token.substring(7);
        if (sessions.containsKey(token)) username = sessions.get(token);
        if (username == null) return null;
        return byUsername(username);
    }

    public AppUser byUsername(String username) {
        return db.maybeOne("""
                SELECT u.*, o.code AS org_code, o.name AS org_name, o.org_type
                FROM app_users u LEFT JOIN orgs o ON o.id = u.org_id
                WHERE u.username = ?
                """, username).map(AppUser::from).orElse(null);
    }

    public static void requireRole(AppUser user, String... roles) {
        if (user == null) throw ApiException.unauthorized("未登录");
        if (user.is("ADMIN")) return;
        for (String r : roles) if (r.equals(user.role())) return;
        throw ApiException.forbidden("ROLE_FORBIDDEN",
                "当前角色 " + user.role() + " 无权执行该操作，需要: " + String.join("/", roles));
    }
}
