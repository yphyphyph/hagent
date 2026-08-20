package com.hagent.ai.controller;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.hagent.ai.model.dto.AiToolRequest;
import com.hagent.ai.service.AiToolService;
import com.hagent.common.Result;
import com.hagent.common.annotation.RateLimit;
import com.hagent.common.context.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 智能工具控制器
 * 统一 SSE 入口：按 toolKey 装配提示词/工具，流式返回结果
 *
 * @author H
 */
@Slf4j
@Tag(name = "AI智能工具", description = "统一SSE入口，按toolKey装配提示词与工具并流式返回执行结果")
@RestController
@RequestMapping("/admin/ai/tools")
public class AiToolController {

    private final AiToolService aiToolService;
    private final ObjectMapper objectMapper;

    public AiToolController(AiToolService aiToolService, ObjectMapper objectMapper) {
        this.aiToolService = aiToolService;
        this.objectMapper = objectMapper;
    }

    /** SSE超时时间（毫秒）：5分钟 */
    private static final long SSE_TIMEOUT_MS = 300000L;

    private final ExecutorService executor = new ThreadPoolExecutor(
            4, 16, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100),
            new NamedThreadFactory("ai-tool-sse"),
            new ThreadPoolExecutor.CallerRunsPolicy());

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30L, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 查询可用智能工具及其入参定义
     */
    @GetMapping
    @Operation(summary = "查询可用智能工具", description = "返回当前可用的AI智能工具列表及其入参定义")
    @ApiResponse(responseCode = "200", description = "查询成功")
    public Result<List<Map<String, Object>>> listTools() {
        return Result.success(aiToolService.listTools());
    }

    /**
     * 执行智能工具（SSE流式）
     */
    @PostMapping(value = "/execute", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "执行智能工具（SSE流式）", description = "按toolKey装配提示词与工具，以SSE流式返回执行结果，[DONE]为结束标记")
    @ApiResponse(responseCode = "200", description = "SSE流建立成功")
    @RateLimit(window = 60, limit = 20, message = "AI请求过于频繁，请 60 秒后重试")
    public SseEmitter execute(@RequestBody AiToolRequest request) {
        Long userId = UserContext.getUserId();
        log.info("execute AI tool: userId={}, toolKey={}", userId, request.getToolKey());
        return doSse(userId, request);
    }

    private SseEmitter doSse(Long userId, AiToolRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        final Disposable[] holder = new Disposable[1];

        emitter.onTimeout(() -> {
            cancel(holder[0]);
            emitter.complete();
        });
        emitter.onError(t -> cancel(holder[0]));
        emitter.onCompletion(() -> cancel(holder[0]));

        executor.execute(() -> {
            try {
                Flux<String> flux = aiToolService.execute(userId, request);
                holder[0] = flux.subscribe(
                        chunk -> {
                            try {
                                emitter.send(SseEmitter.event().data(chunk));
                            } catch (IOException e) {
                                log.warn("SSE发送失败（客户端可能断连）: userId={}", userId);
                                cancel(holder[0]);
                                emitter.completeWithError(e);
                            }
                        },
                        error -> {
                            log.error("AI工具SSE流异常", error);
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
                                emitter.completeWithError(e);
                            }
                        }
                );
            } catch (Exception e) {
                log.error("AI工具执行异常", e);
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

    private void cancel(Disposable subscription) {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }

    private String buildErrorJson(String message) {
        try {
            return "{\"type\":\"error\",\"message\":" + objectMapper.writeValueAsString(message) + "}";
        } catch (JacksonException e) {
            return "{\"type\":\"error\",\"message\":\"AI服务异常\"}";
        }
    }

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
