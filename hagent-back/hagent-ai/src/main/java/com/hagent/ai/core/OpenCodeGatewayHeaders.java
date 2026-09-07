package com.hagent.ai.core;

import java.net.http.HttpRequest;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * opencode.ai Go 网关会话头注入工具（集中注入点，所有网关对话模型调用统一走此方法）
 *
 * <p>背景：opencode.ai Go 网关自 2026-09-07 13:23 起强制校验会话头，缺失时返回
 * 400 "Request is missing x-opencode-session and cannot be routed efficiently"
 * （网关文档要求见 https://opencode.ai/docs/go/#where-can-i-use-it：
 * "stable session ID for each conversation"）。
 *
 * <p>机制结论（javap 反编译 spring-ai-openai 2.0.1 确认）：
 * Spring AI 2.0.1 已移除 OpenAiApi.Builder（底层重写为官方 com.openai SDK），
 * 头注入唯一入口是 {@link OpenAiChatOptions#getCustomHeaders()}（Options 级）：
 * OpenAiChatModel.createRequest 在构建请求时读取该 Map 并应用到 SDK 参数构建器的
 * 请求头上；且 buildRequestPrompt 不会将运行时 Options 与模型默认 Options 合并
 * （Prompt 携带运行时 Options 时默认 Options 整体被忽略），因此每个作为运行时
 * Options 传入网关的 OpenAiChatOptions 都必须各自注入头；裸 Prompt 调用（无
 * 运行时 Options）走模型默认 Options，由 AiModelProvider 构建处注入实例级头兜底。
 *
 * @author H
 */
public final class OpenCodeGatewayHeaders {

    /** 网关会话头名（网关强制校验，缺失返回 400） */
    public static final String HEADER_SESSION = "x-opencode-session";

    /** 自定义 User-Agent 头名（网关按 UA 识别流量来源） */
    public static final String HEADER_USER_AGENT = "User-Agent";

    /** 会话级会话ID前缀（每会话稳定，满足网关 "stable session ID for each conversation" 要求） */
    private static final String SESSION_CONV_PREFIX = "hagent-conv-";

    /** 实例级会话ID前缀（访客/无会话上下文兜底） */
    private static final String SESSION_INSTANCE_PREFIX = "hagent-instance-";

    /** User-Agent 值 */
    private static final String USER_AGENT_VALUE = "HAgent/1.0 (Agentic-RAG)";

    /** 实例级会话ID：类加载时生成一次，进程内所有无会话请求共用，保证实例内稳定 */
    private static final String INSTANCE_SESSION_ID = SESSION_INSTANCE_PREFIX + UUID.randomUUID();

    private OpenCodeGatewayHeaders() {
    }

    /**
     * 构建网关要求的请求头（会话头 + User-Agent）
     *
     * @param conversationId 会话ID；为空时回退实例级 UUID（访客/无会话上下文）
     * @return 不可变请求头 Map
     * @author H
     */
    public static Map<String, String> buildHeaders(Long conversationId) {
        String sessionId = conversationId != null
                ? SESSION_CONV_PREFIX + conversationId
                : INSTANCE_SESSION_ID;
        return Map.of(HEADER_SESSION, sessionId, HEADER_USER_AGENT, USER_AGENT_VALUE);
    }

    /**
     * 将网关请求头注入 OpenAiChatOptions 构建器（链式，供构建处统一调用）
     *
     * @param builder        Options 构建器
     * @param conversationId 会话ID；为空时回退实例级 UUID
     * @return 注入后的构建器
     * @author H
     */
    public static OpenAiChatOptions.Builder applyTo(OpenAiChatOptions.Builder builder, Long conversationId) {
        return builder.customHeaders(buildHeaders(conversationId));
    }

    /**
     * 将网关请求头注入 JDK HttpClient 请求构建器（自建 HTTP 调网关的场景，如视觉 API）
     *
     * @param builder        HttpRequest 构建器
     * @param conversationId 会话ID；为空时回退实例级 UUID
     * @return 注入后的构建器
     * @author H
     */
    public static HttpRequest.Builder applyTo(HttpRequest.Builder builder, Long conversationId) {
        Map<String, String> headers = buildHeaders(conversationId);
        return builder.header(HEADER_SESSION, headers.get(HEADER_SESSION))
                .header(HEADER_USER_AGENT, headers.get(HEADER_USER_AGENT));
    }
}
