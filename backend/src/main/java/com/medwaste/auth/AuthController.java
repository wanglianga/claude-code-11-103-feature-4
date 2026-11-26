package com.medwaste.auth;

import com.medwaste.common.Db;
import com.medwaste.web.CurrentUsers;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final Db db;

    public AuthController(AuthService auth, Db db) {
        this.auth = auth;
        this.db = db;
    }

    public record LoginReq(String username, String password) {}

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginReq req) {
        AuthService.LoginResult r = auth.login(req.username().trim(), req.password());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("token", r.token());
        body.put("user", r.user());
        return body;
    }

    @GetMapping("/me")
    public AppUser me(HttpServletRequest req) {
        return CurrentUsers.get(req);
    }

    /** 测试账号列表（虚构演示环境，直接展示账号密码）。 */
    @GetMapping("/demo-accounts")
    public List<Map<String, Object>> accounts() {
        return db.list("""
                SELECT u.username, u.password, u.display_name AS "displayName", u.role,
                       o.name AS "orgName", u.title
                FROM app_users u LEFT JOIN orgs o ON o.id = u.org_id
                ORDER BY u.id
                """);
    }
}
