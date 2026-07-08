package com.streetlight.security;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 轻量 JWT 工具：HMAC-SHA256 签名，无外部依赖。
 * Token 格式: base64(header).base64(payload).base64(signature)
 */
@Slf4j
public class JwtUtil {

    // 生产环境应从配置文件读取
    private static final String SECRET = "smart-streetlight-secret-key-2026";
    private static final long EXPIRATION_MS = 24 * 60 * 60 * 1000; // 24 小时

    /** 生成 Token: { "username": "...", "role": "...", "exp": ... } */
    public static String generate(String username, String role) {
        long exp = System.currentTimeMillis() + EXPIRATION_MS;
        String payloadJson = String.format(
            "{\"username\":\"%s\",\"role\":\"%s\",\"exp\":%d}", username, role, exp
        );
        String headerB64 = base64Encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payloadB64 = base64Encode(payloadJson);
        String toSign = headerB64 + "." + payloadB64;
        String sigB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmacSha256(toSign, SECRET));
        return headerB64 + "." + payloadB64 + "." + sigB64;
    }

    /** 验证并解析 Token，返回 Payload 对象；验证失败返回 null */
    public static TokenPayload verify(String token) {
        if (token == null || token.isEmpty()) return null;
        String[] parts = token.split("\\.");
        if (parts.length != 3) return null;

        String toSign = parts[0] + "." + parts[1];
        String expectedSig = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmacSha256(toSign, SECRET));
        if (!expectedSig.equals(parts[2])) {
            log.warn("JWT 签名验证失败");
            return null;
        }

        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        return TokenPayload.fromJson(payloadJson);
    }

    // ---- 内部工具 ----

    private static String base64Encode(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("HMAC 签名失败", e);
        }
    }

    /** Token 解析结果 */
    public static class TokenPayload {
        public String username;
        public String role;
        public long exp;

        public boolean isExpired() {
            return System.currentTimeMillis() > exp;
        }

        static TokenPayload fromJson(String json) {
            TokenPayload p = new TokenPayload();
            p.username = extractString(json, "username");
            p.role = extractString(json, "role");
            p.exp = extractLong(json, "exp");
            return p;
        }

        private static String extractString(String json, String key) {
            String k = "\"" + key + "\":\"";
            int s = json.indexOf(k);
            if (s < 0) return "";
            s += k.length();
            int e = json.indexOf("\"", s);
            return json.substring(s, e);
        }

        private static long extractLong(String json, String key) {
            String k = "\"" + key + "\":";
            int s = json.indexOf(k);
            if (s < 0) return 0;
            s += k.length();
            int e = json.indexOf(",", s);
            if (e < 0) e = json.indexOf("}", s);
            return Long.parseLong(json.substring(s, e).trim());
        }
    }
}
