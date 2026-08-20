package com.hagent.ai.controller;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.hagent.ai.model.dto.ChatRequest;
import com.hagent.ai.service.ChatService;
import com.hagent.common.annotation.RateLimit;
import com.hagent.common.context.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI聊天控制器
 * 提供管理端与门户端的SSE流式聊天接口
 *
 * @author H
 */
@Slf4j
@Tag(name = "AI聊天", description = "提供管理端与门户端的AI流式聊天（SSE）接口")
@RestController
@RequestMapping("/admin/ai")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    /** SSE超时时间（毫秒）：5分钟，覆盖两阶段（同步工具调用+流式输出） */
    private static final long SSE_TIMEOUT_MS = 300000L;

    /** 线程池核心线程数 */
    private static final int CORE_POOL_SIZE = 8;
    /** 线程池最大线程数 */
    private static final int MAX_POOL_SIZE = 32;
    /** 线程池队列容量 */
    private static final int QUEUE_CAPACITY = 200;
    /** 线程空闲存活时间（秒） */
    private static final long KEEP_ALIVE_SECONDS = 60L;
    /** 优雅关闭等待时间（秒） */
    private static final long SHUTDOWN_AWAIT_SECONDS = 30L;

    /**
     * SSE处理线程池
     * 使用命名线程工厂便于问题排查；CallerRunsPolicy拒绝策略：队列满时由调用线程执行，避免任务丢失
     */
    private final ExecutorService executor = new ThreadPoolExecutor(
            CORE_POOL_SIZE, MAX_POOL_SIZE, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            new NamedThreadFactory("ai-sse"),
            new ThreadPoolExecutor.CallerRunsPolicy());

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 管理端AI聊天（SSE流式）
     * 使用SseEmitter订阅Flux，逐chunk推送到前端
     *
     * @param request       聊天请求
     * @param authorization 认证头
     * @return SseEmitter流式发射器
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "管理端AI聊天（SSE流式）", description = "管理端AI对话，使用SseEmitter订阅Flux逐chunk推送到前端，[DONE]为流结束标记")
    @ApiResponse(responseCode = "200", description = "SSE流建立成功")
    @RateLimit(window = 60, limit = 20, message = "AI请求过于频繁，请 60 秒后重试")
    public SseEmitter adminChat(@RequestBody @Valid ChatRequest request,
                                @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = resolveUserId();
        log.info("adminChat: userId={}, message={}", userId, request.getMessage());
        return doSseChat(userId, request, true);
    }

    /**
     * 门户端AI聊天（SSE流式）
     *
     * @param request       聊天请求
     * @param authorization 认证头
     * @return SseEmitter流式发射器
     */
    @PostMapping(value = "/portal/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "门户端AI聊天（SSE流式）", description = "门户端AI对话，使用SseEmitter订阅Flux逐chunk推送到前端，[DONE]为流结束标记")
    @ApiResponse(responseCode = "200", description = "SSE流建立成功")
    @RateLimit(window = 60, limit = 20, message = "AI请求过于频繁，请 60 秒后重试")
    public SseEmitter portalChat(@RequestBody @Valid ChatRequest request,
                                 @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = resolveUserId();
        return doSseChat(userId, request, false);
    }

    /**
     * 统一SSE流式处理
     * 异步执行：订阅Flux → 逐chunk通过SseEmitter推送
     * 所有业务数据（token/元数据/错误）均为结构化JSON字符串，[DONE]为流结束标记
     *
     * @param userId   用户ID
     * @param request  聊天请求
     * @param isAdmin  是否管理端
     * @return SseEmitter流式发射器
     */
    private SseEmitter doSseChat(Long userId, ChatRequest request, boolean isAdmin) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        // 用于保存Flux订阅句柄，以便在emitter超时/出错/完成时取消订阅，释放资源
        final Disposable[] subscriptionHolder = new Disposable[1];

        // 注册生命周期回调：客户端断连或超时后取消Flux订阅，避免AI继续生成浪费token
        emitter.onTimeout(() -> {
            log.warn("SSE超时，取消订阅: userId={}", userId);
            cancelSubscription(subscriptionHolder[0]);
            emitter.complete();
        });
        emitter.onError(throwable -> {
            log.warn("SSE异常，取消订阅: userId={}", userId);
            cancelSubscription(subscriptionHolder[0]);
        });
        emitter.onCompletion(() -> cancelSubscription(subscriptionHolder[0]));

        executor.execute(() -> {
            try {
                Flux<String> flux = isAdmin
                        ? chatService.chatAdmin(userId, request)
                        : chatService.chatPortal(userId, request);

                subscriptionHolder[0] = flux.subscribe(
                        chunk -> {
                            try {
                                emitter.send(SseEmitter.event().data(chunk));
                            } catch (IOException e) {
                                // 客户端断连：取消订阅，终止后续AI生成，避免日志爆炸和token浪费
                                log.warn("SSE发送chunk失败（客户端可能已断连），取消订阅: userId={}", userId);
                                cancelSubscription(subscriptionHolder[0]);
                                emitter.completeWithError(e);
                            }
                        },
                        error -> {
                            log.error("SSE流异常", error);
                            try {
                                emitter.send(SseEmitter.event().data(buildErrorJson("AI服务暂时不可用，请稍后重试")));
                                emitter.send(SseEmitter.event().data("[DONE]"));
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        () -> {
                            try {
                                emitter.send(SseEmitter.event().data("[DONE]"));
                                emitter.complete();
                            } catch (IOException e) {
                                log.error("SSE发送完成事件失败", e);
                                emitter.completeWithError(e);
                            }
                        }
                );
            } catch (Exception e) {
                log.error("AI对话异常", e);
                try {
                    emitter.send(SseEmitter.event().data(buildErrorJson("AI服务暂时不可用，请稍后重试")));
                    emitter.send(SseEmitter.event().data("[DONE]"));
                    emitter.complete();
                } catch (IOException ex) {
                    emitter.completeWithError(ex);
                }
            }
        });

        return emitter;
    }

    /**
     * 安全取消Flux订阅
     *
     * @param subscription 订阅句柄
     */
    private void cancelSubscription(Disposable subscription) {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }

    /**
     * 构建错误事件JSON
     * 统一结构化格式：{"type":"error","message":"<错误信息>"}（脱敏，不暴露内部细节）
     *
     * @param message 错误消息文本
     * @return 结构化JSON字符串
     */
    private String buildErrorJson(String message) {
        try {
            return "{\"type\":\"error\",\"message\":" + objectMapper.writeValueAsString(message) + "}";
        } catch (JacksonException e) {
            log.warn("JSON编码错误消息失败，降级为固定提示", e);
            return "{\"type\":\"error\",\"message\":\"AI服务异常\"}";
        }
    }

    /**
     * 解析当前登录用户ID
     * 直接复用拦截器已设置的UserContext，避免重复解析token（DRY）
     *
     * @return 用户ID，不存在则返回null
     */
    private Long resolveUserId() {
        return UserContext.getUserId();
    }

    /**
     * 命名线程工厂，为线程池线程生成可识别的名称
     */
    private static class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);
        private final String namePrefix;

        NamedThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, namePrefix + "-" + counter.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        }
    }
}
