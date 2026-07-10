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

        // 辅助方法：是否为管理员或操作员
        boolean isAdmin = "admin".equals(role);
        boolean isOperator = "operator".equals(role);

        // --- 告警规则 — 仅 admin ---
        if (path.startsWith("/api/alarm-rules")) {
            if (!isAdmin) {
                sendError(response, 403, "权限不足，仅管理员可管理告警规则");
                return false;
            }
        }

        // --- 告警管理 ---
        if (path.startsWith("/api/alarms")) {
            // 查看告警列表 & 待处理数量 → admin + operator（市政人员不可见）
            if ("GET".equalsIgnoreCase(request.getMethod())) {
                if (!isAdmin && !isOperator) {
                    sendError(response, 403, "权限不足，仅管理员和操作员可查看告警");
                    return false;
                }
            } else if (path.matches(".*/alarms/\\d+/resolvedBy$") && "PUT".equalsIgnoreCase(request.getMethod())) {
                // 修改处理人 → admin + operator
                if (!isAdmin && !isOperator) {
                    sendError(response, 403, "权限不足，仅管理员和操作员可修改处理人");
                    return false;
                }
            } else {
                // 告警处理（resolve / batch-resolve）→ 仅 admin
                if (!isAdmin) {
                    sendError(response, 403, "权限不足，仅管理员可处理告警");
                    return false;
                }
            }
        }

        // --- 传感器绑定/解绑 ---
        if (path.matches(".*/devices/[^/]+/bind-sensor")) {
            if (!isAdmin) {
                sendError(response, 403, "权限不足，仅管理员可绑定传感器");
                return false;
            }
        }
        if (path.matches(".*/devices/[^/]+/unbind-sensor/[^/]+")) {
            if (!isAdmin && !isOperator) {
                sendError(response, 403, "权限不足，仅管理员和操作员可解绑传感器");
                return false;
            }
        }

        // 传感器列表写操作 — 仅 admin（GET /sensors 允许 municipal 查看）
        if (path.matches(".*/devices/[^/]+/sensors") && !"GET".equalsIgnoreCase(request.getMethod())) {
            if (!isAdmin) {
                sendError(response, 403, "权限不足，仅管理员可管理传感器");
                return false;
            }
        }

        // 设备增删改 — admin 可全部操作，operator 可新增/删除设备
        if (path.matches(".*/devices/?$")) {
            if (!"GET".equalsIgnoreCase(request.getMethod())) {
                if ("POST".equalsIgnoreCase(request.getMethod())) {
                    // 添加设备 → admin + operator
                    if (!isAdmin && !isOperator) {
                        sendError(response, 403, "权限不足，仅管理员和操作员可添加设备");
                        return false;
                    }
                } else {
                    // PUT/DELETE 设备列表 → 仅 admin
                    if (!isAdmin) {
                        sendError(response, 403, "权限不足，仅管理员可修改设备");
                        return false;
                    }
                }
            }
        }
        if (path.matches(".*/devices/\\d+$")) {
            if (!"GET".equalsIgnoreCase(request.getMethod())) {
                // PUT（修改设备信息）→ 仅 admin
                if ("PUT".equalsIgnoreCase(request.getMethod())) {
                    if (!isAdmin) {
                        sendError(response, 403, "权限不足，仅管理员可修改设备");
                        return false;
                    }
                }
                // DELETE（删除设备）→ admin + operator
                if ("DELETE".equalsIgnoreCase(request.getMethod())) {
                    if (!isAdmin && !isOperator) {
                        sendError(response, 403, "权限不足，仅管理员和操作员可删除设备");
                        return false;
                    }
                }
            }
        }

        // 设备控制 (POST /devices/{id}/control) — municipal、operator、admin 均可
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
