package com.hagent.common.service.impl;

import com.hagent.common.service.TokenBlacklistService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 Token 黑名单服务实现
 * 使用 ConcurrentHashMap 存储被吊销的 jti 及其过期时间戳
 * 适用单实例部署场景；多实例部署建议替换为 Redis 实现
 *
 * @author H
 */
@Slf4j
@Service
public class InMemoryTokenBlacklistService implements TokenBlacklistService {

    /** 黑名单存储：jti -> 过期时间戳（毫秒） */
    private final Map<String, Long> blacklist = new ConcurrentHashMap<>();

    /** 最大容量，防止内存溢出 */
    private static final int MAX_SIZE = 10000;

    @Override
    public void blacklist(String jti, long ttlMillis) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        if (ttlMillis <= 0) {
            return;
        }
        // 容量保护：超过上限时先清理过期条目
        if (blacklist.size() >= MAX_SIZE) {
            cleanupExpired();
        }
        blacklist.put(jti, System.currentTimeMillis() + ttlMillis);
        log.debug("Token 已加入黑名单: jti={}, ttl={}ms", jti, ttlMillis);
    }

    @Override
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        Long expireAt = blacklist.get(jti);
        if (expireAt == null) {
            return false;
        }
        // 已过期则移除并返回 false
        if (expireAt < System.currentTimeMillis()) {
            blacklist.remove(jti);
            return false;
        }
        return true;
    }

    /**
     * 定时清理过期条目（每 5 分钟执行一次）
     */
    @Scheduled(fixedDelay = 300_000)
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        int before = blacklist.size();
        Iterator<Map.Entry<String, Long>> it = blacklist.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < now) {
                it.remove();
            }
        }
        int removed = before - blacklist.size();
        if (removed > 0) {
            log.debug("Token 黑名单清理: 移除 {} 条过期记录，剩余 {}", removed, blacklist.size());
        }
    }
}
