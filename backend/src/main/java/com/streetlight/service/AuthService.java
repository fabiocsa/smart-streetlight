package com.streetlight.service;

import com.streetlight.common.BusinessException;
import com.streetlight.entity.User;
import com.streetlight.repository.UserRepository;
import com.streetlight.security.JwtUtil;
import com.streetlight.security.PasswordUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;

    private static final java.util.Set<String> VALID_ROLES = java.util.Set.of("admin", "manager", "municipal", "operator");

    /** 注册新用户 */
    @Transactional
    public Map<String, Object> register(String username, String password, String role) {
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException("用户名已存在");
        }
        if (role == null || role.isBlank()) {
            role = "municipal";
        }
        if (!VALID_ROLES.contains(role)) {
            throw new BusinessException("无效的角色: " + role);
        }
        User user = User.builder()
                .username(username)
                .passwordHash(PasswordUtil.hash(password))
                .role(role)
                .build();
        userRepository.save(user);
        log.info("新用户注册: username={}, role={}", username, role);

        String token = JwtUtil.generate(username, role);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("username", username);
        result.put("role", role);
        return result;
    }

    /** 登录 */
    public Map<String, Object> login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException("用户名或密码错误"));
        if (!PasswordUtil.verify(password, user.getPasswordHash())) {
            throw new BusinessException("用户名或密码错误");
        }
        log.info("用户登录: username={}, role={}", username, user.getRole());

        String token = JwtUtil.generate(username, user.getRole());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("username", username);
        result.put("role", user.getRole());
        return result;
    }
}
