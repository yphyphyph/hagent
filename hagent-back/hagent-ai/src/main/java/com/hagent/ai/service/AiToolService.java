package com.hagent.ai.service;

import com.hagent.ai.model.dto.AiToolRequest;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * AI 智能工具服务接口
 * 统一装配提示词 + 工具，按 toolKey 提供 SSE 流式能力
 *
 * @author H
 */
public interface AiToolService {

    /**
     * 列出可用的智能工具及其入参定义（供前端动态渲染表单）
     *
     * @return 工具描述列表
     */
    List<Map<String, Object>> listTools();

    /**
     * 执行指定工具，返回结构化 JSON 的流式响应
     *
     * @param userId  当前用户ID（用于归属/审计）
     * @param request 工具执行请求
     * @return SSE 流式响应（每个元素为结构化 JSON 字符串）
     */
    Flux<String> execute(Long userId, AiToolRequest request);
}
