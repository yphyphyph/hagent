package com.hagent.common.service.impl;

import com.hagent.common.service.TokenBlacklistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的 Token 黑名单服务实现
 * <p>
 * 使用 Redis 存储被吊销的 jti，利用 Redis 原生 TTL 自动过期，天然支持多实例部署。
 * 仅当容器中存在 {@link StringRedisTemplate} 时生效，并作为 {@link Primary} 覆盖内存实现；
 * 若 Redis 不可用则由 {@code InMemoryTokenBlacklistService} 兜底。
 *
 * @author H
 */
@Slf4j
@Primary
@Service
@RequiredArgsConstructor
@ConditionalOnBean(StringRedisTemplate.class)
public class RedisTokenBlacklistService implements TokenBlacklistService {

    /** Redis Key 前缀 */
    private static final String KEY_PREFIX = "hui:jwt:blacklist:";

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void blacklist(String jti, long ttlMillis) {
        if (jti == null || jti.isBlank() || ttlMillis <= 0) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", ttlMillis, TimeUnit.MILLISECONDS);
            log.debug("Token 已加入 Redis 黑名单: jti={}, ttl={}ms", jti, ttlMillis);
        } catch (Exception e) {
            log.error("写入 Redis 黑名单失败: jti={}", jti, e);
        }
    }

    @Override
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(KEY_PREFIX + jti));
        } catch (Exception e) {
            // Redis 异常时不阻断请求（安全兜底：宁可放行也不因缓存故障导致全站不可用）
            log.error("查询 Redis 黑名单失败: jti={}", jti, e);
            return false;
        }
    }
}
