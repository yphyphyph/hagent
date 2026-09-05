package com.hagent.ai.service;

import com.hagent.ai.model.dto.ChatRequest;
import com.hagent.ai.model.vo.ContextUsageSnapshot;
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

    /**
     * 现算历史打开首值（上下文用量的唯一真源，只读不落库）
     * 与聊天 meta 首值共用同一估算核心，不允许两套算法分叉
     *
     * @param conversationId 会话ID
     * @return 上下文用量快照
     */
    ContextUsageSnapshot estimateInitial(Long conversationId);
}
