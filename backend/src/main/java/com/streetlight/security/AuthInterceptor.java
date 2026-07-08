package com.streetlight.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

/**
 * 认证拦截器：验证 JWT Token，解析用户角色并注入 request attribute。
 *
 * 无需鉴权的路径：
 *   POST /api/auth/login
 *   POST /api/auth/register
 *   GET  /ws/monitor (WebSocket 握手)
 *   OPTIONS (预检请求)
 */
@Slf4j
public class AuthInterceptor implements HandlerInterceptor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        // 放行 OPTIONS 预检
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI();

        // 放行登录/注册
        if (path.startsWith("/api/auth/")) {
            return true;
        }

        // 放行 WebSocket
        if (path.startsWith("/ws/")) {
            return true;
        }

        // 从 Authorization Header 获取 Token
        String authHeader = request.getHeader("Authorization");
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        }

        if (token == null || token.isEmpty()) {
            sendError(response, 401, "未登录，请先登录");
            return false;
        }

        // 验证 Token
        JwtUtil.TokenPayload payload = JwtUtil.verify(token);
        if (payload == null || payload.isExpired()) {
            sendError(response, 401, "登录已过期，请重新登录");
            return false;
        }

        // 注入用户信息到 request attribute
        request.setAttribute("username", payload.username);
        request.setAttribute("role", payload.role);

        // ----- 角色权限校验 -----
        String role = payload.role;

        // 告警管理 & 告警规则 — 仅 admin
        if (path.startsWith("/api/alarms") || path.startsWith("/api/alarm-rules")) {
            if (!"admin".equals(role)) {
                sendError(response, 403, "权限不足，仅管理员可访问告警模块");
                return false;
            }
        }

        // 传感器写操作 & 设备写操作 — 仅 admin
        // (GET /sensors 允许 municipal 查看)
        if (path.matches(".*/devices/[^/]+/sensors") && !"GET".equalsIgnoreCase(request.getMethod())) {
            if (!"admin".equals(role)) {
                sendError(response, 403, "权限不足，仅管理员可管理传感器");
                return false;
            }
        }

        // 设备增删改 — 仅 admin
        if (path.matches(".*/devices/?$") || path.matches(".*/devices/\\d+$")) {
            if (!"GET".equalsIgnoreCase(request.getMethod()) && !"admin".equals(role)) {
                sendError(response, 403, "权限不足，仅管理员可管理设备");
                return false;
            }
        }

        // 设备控制 (POST /devices/{id}/control) — municipal 和 admin 均可
        // 默认放行

        return true;
    }

    private void sendError(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = Map.of("code", code, "message", message);
        response.getWriter().write(MAPPER.writeValueAsString(body));
    }
}
