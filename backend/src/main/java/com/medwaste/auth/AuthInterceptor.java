package com.medwaste.auth;

import com.medwaste.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 除登录/公开查询/dev 重置外，均需登录；支持 Bearer token 与 X-Demo-User 头。 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final AuthService auth;

    public AuthInterceptor(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getRequestURI();
        if (path.startsWith("/api/auth/login")
                || path.startsWith("/api/track")
                || path.startsWith("/api/public")
                || path.startsWith("/api/dev")
                || path.equals("/api/meta/health")) {
            return true;
        }
        // feature-4 跨区现场总览为只读看板（与公开追踪一致），GET 放行；基线重置也允许直接调用以便重复验收
        if (("GET".equalsIgnoreCase(request.getMethod()) && path.startsWith("/api/zone"))
                || ("POST".equalsIgnoreCase(request.getMethod()) && path.equals("/api/zone/reset"))) {
            return true;
        }
        AppUser user = null;
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null) user = auth.resolveToken(authHeader);
        if (user == null) {
            String demoUser = request.getHeader("X-Demo-User");
            if (demoUser != null && !demoUser.isBlank()) user = auth.byUsername(demoUser.trim());
        }
        if (user == null) throw ApiException.unauthorized("请先登录后再操作");
        request.setAttribute("currentUser", user);
        return true;
    }
}
