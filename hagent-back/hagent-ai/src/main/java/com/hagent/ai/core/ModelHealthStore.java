package com.hagent.ai.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型健康存储 — 熔断隔离
 * 连续失败 3 次则 OPEN 60s，超时后进入 HALF_OPEN 放行一次探测，成功则关闭、失败则重新 OPEN
 *
 * @author H
 */
@Slf4j
@Component
public class ModelHealthStore {

    /** 连续失败阈值 */
    private static final int FAILURE_THRESHOLD = 3;

    /** 熔断时长（毫秒） */
    private static final long OPEN_DURATION_MS = 60_000L;

    /** 健康表：modelName -> Health */
    private final ConcurrentHashMap<String, Health> store = new ConcurrentHashMap<>();

    /**
     * 健康状态枚举
     *
     * @author H
     */
    public enum State {
        /** 关闭（可用） */
        CLOSED,
        /** 开启（熔断） */
        OPEN,
        /** 半开（探测期，仅放行一次） */
        HALF_OPEN
    }

    /**
     * 单模型健康记录
     *
     * @author H
     */
    public static class Health {

        /** 连续失败次数 */
        volatile int consecutiveFailures;

        /** 熔断截止时间戳（毫秒） */
        volatile long openUntilMs;

        /** 当前状态 */
        volatile State state = State.CLOSED;

        @Override
        public String toString() {
            return "Health{failures=" + consecutiveFailures + ", state=" + state + ", openUntilMs=" + openUntilMs + "}";
        }
    }

    /**
     * 判断模型是否可用
     * <p>
     * OPEN 且未超时返回 false；OPEN 且已超时则转为 HALF_OPEN 放行一次
     * </p>
     *
     * @param modelName 模型名称
     * @return 是否可用
     * @author H
     */
    public boolean isAvailable(String modelName) {
        if (modelName == null) {
            return true;
        }
        Health health = store.get(modelName);
        if (health == null) {
            return true;
        }
        synchronized (health) {
            if (health.state == State.OPEN) {
                long now = System.currentTimeMillis();
                if (now < health.openUntilMs) {
                    return false;
                }
                // 超时：转半开，放行一次探测
                health.state = State.HALF_OPEN;
                log.info("model health HALF_OPEN probe model={}", modelName);
                return true;
            }
            return true;
        }
    }

    /**
     * 记录成功 — 清零并关闭熔断
     *
     * @param modelName 模型名称
     * @author H
     */
    public void recordSuccess(String modelName) {
        if (modelName == null) {
            return;
        }
        Health health = store.get(modelName);
        if (health == null) {
            return;
        }
        synchronized (health) {
            health.consecutiveFailures = 0;
            health.openUntilMs = 0L;
            if (health.state != State.CLOSED) {
                log.info("model health CLOSED recover model={}", modelName);
            }
            health.state = State.CLOSED;
        }
    }

    /**
     * 记录失败 — 连续 3 次则 OPEN 60s
     *
     * @param modelName 模型名称
     * @author H
     */
    public void recordFailure(String modelName) {
        if (modelName == null) {
            return;
        }
        Health health = store.computeIfAbsent(modelName, k -> new Health());
        synchronized (health) {
            // 半开期失败直接重新熔断
            if (health.state == State.HALF_OPEN) {
                health.consecutiveFailures = FAILURE_THRESHOLD;
                health.state = State.OPEN;
                health.openUntilMs = System.currentTimeMillis() + OPEN_DURATION_MS;
                log.warn("model health OPEN half-open probe failed model={} openUntilMs={}", modelName, health.openUntilMs);
                return;
            }
            health.consecutiveFailures++;
            if (health.consecutiveFailures >= FAILURE_THRESHOLD) {
                health.state = State.OPEN;
                health.openUntilMs = System.currentTimeMillis() + OPEN_DURATION_MS;
                log.warn("model health OPEN model={} failures={} openUntilMs={}", modelName, health.consecutiveFailures, health.openUntilMs);
            }
        }
    }

    /**
     * 仅供测试/观测：获取健康快照
     *
     * @param modelName 模型名称
     * @return 健康对象或 null
     * @author H
     */
    public Health getHealth(String modelName) {
        return store.get(modelName);
    }
}
