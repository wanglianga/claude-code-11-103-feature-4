package com.medwaste.web;

import com.medwaste.auth.AppUser;
import com.medwaste.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;

/** Controller 取当前登录用户的小工具。 */
public final class CurrentUsers {
    private CurrentUsers() {}

    public static AppUser get(HttpServletRequest req) {
        Object u = req.getAttribute("currentUser");
        if (u instanceof AppUser user) return user;
        throw ApiException.unauthorized("未登录");
    }
}
