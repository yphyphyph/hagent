package com.hagent.ai.service;

import com.hagent.ai.model.dto.ChatRequest;
import reactor.core.publisher.Flux;

/**
 * 聊天服务接口
 *
 * @author H
 */
public interface ChatService {

    /**
     * 管理端聊天（完整工具支持）
     *
     * @param userId  用户ID
     * @param request 聊天请求
     * @return SSE流式响应
     */
    Flux<String> chatAdmin(Long userId, ChatRequest request);

    /**
     * 门户端聊天（有限工具支持）
     *
     * @param userId  用户ID
     * @param request 聊天请求
     * @return SSE流式响应
     */
    Flux<String> chatPortal(Long userId, ChatRequest request);
}
