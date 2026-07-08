package com.streetlight.controller;

import com.streetlight.common.Result;
import com.streetlight.security.JwtUtil;
import com.streetlight.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 注册 */
    @PostMapping("/register")
    public Result<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String role = body.getOrDefault("role", "municipal");
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return Result.error("用户名和密码不能为空");
        }
        return Result.success(authService.register(username.trim(), password, role));
    }

    /** 登录 */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return Result.error("用户名和密码不能为空");
        }
        return Result.success(authService.login(username.trim(), password));
    }

    /** 获取当前登录用户信息（由拦截器注入 request attribute） */
    @GetMapping("/me")
    public Result<Map<String, Object>> me(@RequestAttribute(value = "username", required = false) String username,
                                          @RequestAttribute(value = "role", required = false) String role) {
        if (username == null) {
            return Result.error(401, "未登录");
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("username", username);
        info.put("role", role);
        return Result.success(info);
    }
}
