package com.hagent.common.util;

import com.hagent.common.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JWT 工具类 - 提供 Token 生成、解析与吊销支持
 * 基于 jjwt 0.12.x API，Token 中包含 jti 用于唯一标识与吊销
 *
 * @author H
 */
@Component
@RequiredArgsConstructor
public class JwtUtil {

    private final JwtProperties jwtProperties;

    private SecretKey signingKey;

    /**
     * 初始化签名密钥
     */
    @PostConstruct
    public void init() {
        this.signingKey = Keys.hmacShaKeyFor(jwtProperties.getSecretKey().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 JWT Token（含 jti 用于吊销）
     * @param userId 用户 ID
     * @param username 用户名
     * @param role 用户角色
     * @return JWT Token 字符串
     */
    public String generateToken(Long userId, String username, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("role", role);
        return Jwts.builder()
                .claims(claims)
                .subject(username)
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtProperties.getTtl()))
                .signWith(signingKey)
                .compact();
    }

    /**
     * 解析 JWT Token
     * @param token JWT Token 字符串
     * @return Claims 对象
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 从 Token 中获取用户 ID
     * jjwt 0.12.x 解析小整数为 Integer，需手动转换为 Long 避免类型异常
     * @param token JWT Token
     * @return 用户 ID
     */
    public Long getUserId(String token) {
        Object value = parseToken(token).get("userId");
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    /**
     * 从 Token 中获取用户名
     * @param token JWT Token
     * @return 用户名
     */
    public String getUsername(String token) {
        return parseToken(token).getSubject();
    }

    /**
     * 从 Token 中获取角色
     * @param token JWT Token
     * @return 角色
     */
    public String getRole(String token) {
        return parseToken(token).get("role", String.class);
    }

    /**
     * 从 Token 中获取 jti（JWT ID，用于吊销）
     * @param token JWT Token
     * @return jti 字符串
     */
    public String getJti(String token) {
        return parseToken(token).getId();
    }

    /**
     * 从 Token 中获取剩余有效期（毫秒）
     * @param token JWT Token
     * @return 剩余有效期毫秒数，已过期返回 0
     */
    public long getRemainingTtl(String token) {
        try {
            Date expiration = parseToken(token).getExpiration();
            long remaining = expiration.getTime() - System.currentTimeMillis();
            return Math.max(0, remaining);
        } catch (ExpiredJwtException e) {
            return 0;
        }
    }

    /**
     * 检查 Token 是否过期
     * @param token JWT Token
     * @return 过期返回 true，未过期返回 false
     */
    public boolean isTokenExpired(String token) {
        try {
            return parseToken(token).getExpiration().before(new Date());
        } catch (ExpiredJwtException e) {
            return true;
        } catch (Exception e) {
            // 解析失败视为无效 Token，不允许通过
            return true;
        }
    }
}
