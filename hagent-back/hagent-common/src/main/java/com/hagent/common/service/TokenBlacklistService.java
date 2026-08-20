package com.hagent.common.service;

/**
 * Token 黑名单服务接口 - 用于 JWT Token 吊销
 *
 * @author H
 */
public interface TokenBlacklistService {

    /**
     * 将 Token 的 jti 加入黑名单
     * @param jti JWT ID
     * @param ttlMillis 剩余有效期（毫秒），过期后自动清除
     */
    void blacklist(String jti, long ttlMillis);

    /**
     * 检查 jti 是否在黑名单中
     * @param jti JWT ID
     * @return 在黑名单中返回 true
     */
    boolean isBlacklisted(String jti);
}
