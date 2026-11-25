package com.fire.fieldgov.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Map;

/**
 * 简单会话认证：POST /api/auth/login 成功后返回 token（即 username 的签名串，演示用）。
 * 生产环境应替换为 OAuth2/JWT；本系统的治理逻辑在服务端访问策略中强制，不依赖前端隐藏。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final JdbcTemplate jdbc;
    private final ObjectMapper om = new ObjectMapper();

    public AuthInterceptor(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        String path = req.getRequestURI();
        if (path.equals("/api/auth/login") || path.startsWith("/api/health")) {
            return true;
        }
        String token = req.getHeader("X-Auth-Token");
        if (token == null || token.isBlank()) {
            return deny(resp, 401, "缺少 X-Auth-Token，请先登录");
        }
        // token 格式：username:hmac（演示 HMAC 用固定盐，账号密码仍在库中校验）
        String username = token.contains(":") ? token.substring(0, token.indexOf(':')) : token;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT username, display_name, role, home_district, clearance, allowed_purposes FROM app_user WHERE username=?",
                username);
        if (rows.isEmpty()) {
            return deny(resp, 401, "无效令牌");
        }
        Map<String, Object> r = rows.get(0);
        List<String> purposes = jdbc.queryForObject(
                "SELECT allowed_purposes FROM app_user WHERE username=?",
                (rs, n) -> com.fire.fieldgov.db.DbJson.toStringList(rs.getArray("allowed_purposes")),
                username);
        UserContext user = new UserContext(
                (String) r.get("username"),
                (String) r.get("display_name"),
                (String) r.get("role"),
                (String) r.get("home_district"),
                (String) r.get("clearance"),
                purposes == null ? List.of() : purposes);
        CurrentUser.set(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp, Object handler, Exception ex) {
        CurrentUser.clear();
    }

    private boolean deny(HttpServletResponse resp, int status, String msg) throws Exception {
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(om.writeValueAsString(Map.of("error", msg)));
        return false;
    }
}
