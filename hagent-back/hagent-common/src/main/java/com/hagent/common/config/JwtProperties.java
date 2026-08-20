package com.hagent.common.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置属性 - 从 application.yml 中 hui.jwt.* 读取
 *
 * @author H
 */
@Data
@Slf4j
@Component
@ConfigurationProperties(prefix = "hui.jwt")
public class JwtProperties {

    /** JWT 密钥（生产环境必须通过环境变量 JWT_SECRET_KEY 注入，至少 32 字节） */
    private String secretKey;

    /** JWT 有效期（毫秒），默认 2 小时 */
    private long ttl = 7200000;

    /** Token 名称 */
    private String tokenName = "Authorization";

    /**
     * 启动时校验密钥安全性
     */
    @PostConstruct
    public void validate() {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("JWT 密钥未配置，请通过环境变量 JWT_SECRET_KEY 注入（至少 32 字节随机字符串）");
        }
        if (secretKey.length() < 32) {
            throw new IllegalStateException("JWT 密钥长度不足 32 字节，存在安全风险，请使用更长的随机字符串");
        }
        if (secretKey.contains("change-in-production") || secretKey.contains("hagent-jwt-secret-key-2026")) {
            log.warn("警告：当前使用的是开发默认 JWT 密钥，生产环境必须通过环境变量 JWT_SECRET_KEY 注入安全密钥！");
        }
    }
}
