package com.hagent.ai.core;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * LLM 首包探测 — 流式首包超时控制
 * 订阅后 2s 内未收到首个 ChatResponse 则判定超时并切备
 *
 * @author H
 */
@Component
public class LlmFirstPacketProbe {

    /** 默认首包超时（毫秒） */
    private static final long DEFAULT_TIMEOUT_MS = 2000L;

    /**
     * 包裹流以实现首包超时
     * 使用 Reactor 的 timeout 操作符，超时即转为 Flux.error(TimeoutException)
     *
     * @param flux      原始流
     * @param timeoutMs 超时阈值，传 0 或负数则取 2000ms
     * @return 带首包超时保护的流
     * @author H
     */
    public Flux<ChatResponse> probeWithTimeout(Flux<ChatResponse> flux, long timeoutMs) {
        long effectiveMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        return flux.timeout(Duration.ofMillis(effectiveMs))
                .onErrorMap(e -> {
                    // Reactor 超时异常为 java.util.concurrent.TimeoutException
                    if (e instanceof TimeoutException) {
                        return new TimeoutException("first packet timeout");
                    }
                    // 有些版本包装为内部 TimeoutException，检查 cause
                    if (e.getCause() instanceof TimeoutException) {
                        return new TimeoutException("first packet timeout");
                    }
                    // 对 Reactor 的超时错误（消息包含 timeout）做兼容兜底
                    String msg = e.getMessage();
                    if (msg != null && msg.toLowerCase().contains("timeout")) {
                        return new TimeoutException("first packet timeout");
                    }
                    return e;
                });
    }
}
