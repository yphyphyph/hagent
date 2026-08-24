package com.hagent.ai.service.impl;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.hagent.ai.core.AiModelProvider;
import com.hagent.ai.core.UserContextHolder;
import com.hagent.ai.mapper.ChatMessageMapper;
import com.hagent.ai.model.dto.ChatRequest;
import com.hagent.ai.model.entity.ChatConversation;
import com.hagent.ai.model.entity.ChatMessage;
import com.hagent.ai.model.enums.MessageRoleEnum;
import com.hagent.ai.model.enums.MessageStatusEnum;
import com.hagent.ai.model.vo.ConversationVO;
import com.hagent.ai.service.ChatService;
import com.hagent.ai.service.ConversationService;
import com.hagent.ai.tool.KnowledgeTool;
import com.hagent.ai.tool.TextToSqlTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天服务实现
 * 实现Agent模式：工具 + 多轮对话 + 真正流式输出
 * <p>
 * 两阶段策略：
 * 1. 工具调用阶段：同步执行 chatModel.call()，处理工具循环
 * 2. 流式输出阶段：使用 chatModel.stream() 真正逐token流式输出最终文本
 *
 * @author H
 */
@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    private final AiModelProvider aiModelProvider;
    private final ConversationService conversationService;
    private final ChatMessageMapper chatMessageMapper;
    /** JSON序列化工具，用于SSE数据结构化编码 */
    private final ObjectMapper objectMapper;

    /** 管理员工具回调（完整访问权限） */
    private final ToolCallback[] adminToolCallbacks;

    /** 门户工具回调（受限访问权限） */
    private final ToolCallback[] portalToolCallbacks;

    /** 工具调用最大迭代次数 */
    private static final int MAX_TOOL_ITERATIONS = 5;

    /** 历史消息最大保留条数（滑动窗口，防止上下文超限） */
    private static final int MAX_HISTORY_MESSAGES = 20;

    /** 工具结果最大字符数（超出截断，防止撑爆上下文） */
    private static final int MAX_TOOL_RESULT_LENGTH = 4000;

    /** 标题最大字符数 */
    private static final int TITLE_MAX_LENGTH = 20;

    /** 新会话默认标题 */
    private static final String DEFAULT_TITLE = "新会话";

    /** 消息类型文本 */
    private static final String MESSAGE_TYPE_TEXT = "TEXT";

        private static final String ADMIN_SYSTEM_PROMPT = """
            你是 HAgent 智能助手，可以使用以下工具：
            - searchKnowledge: 搜索知识库文档（RAG 检索）
            - queryBusinessData: 通过自然语言查询业务数据（SQL 查询）
            - analyze_image: 使用多模态视觉模型识别图片并回答问题
            请遵循以下原则：
            1. 先理解用户想要达成什么目标
            2. 使用合适的工具来收集信息或准备操作
            3. 清晰简洁地向用户展示结果
            4. 当用户询问数据分析、统计、趋势等问题时，使用 queryBusinessData 工具
            5. 当用户提供图片或要求识别图片内容时，使用 analyze_image 工具
            """;

        private static final String PORTAL_SYSTEM_PROMPT = """
            你是 HAgent 智能助手，一个 Java 全栈与 AI 应用开发者（H）的作品集网站助手。
            你可以使用知识库工具（searchKnowledge）检索网站知识库文档来回答问题。
            请遵循以下原则：
            1. 保持友好、简洁、专业
            2. 当问题涉及知识库内容时，先调用 searchKnowledge 工具检索再回答
            3. 回答中文，以清晰易读的格式展示结果
            4. 不确定的内容不要编造，明确说明
            """;

    public ChatServiceImpl(AiModelProvider aiModelProvider,
                           ConversationService conversationService,
                           ChatMessageMapper chatMessageMapper,
                           @Nullable org.springframework.ai.chat.client.ChatClient chatClient,
                           ObjectMapper objectMapper,
                           @Lazy KnowledgeTool knowledgeTool,
                           @Lazy TextToSqlTool textToSqlTool) {
        this.aiModelProvider = aiModelProvider;
        this.conversationService = conversationService;
        this.chatMessageMapper = chatMessageMapper;
        this.objectMapper = objectMapper;
        // 管理员工具：知识库 + Text to SQL
        this.adminToolCallbacks = ToolCallbacks.from(knowledgeTool, textToSqlTool);
        // 门户工具：知识库检索
        this.portalToolCallbacks = ToolCallbacks.from(knowledgeTool);
    }

    @Override
    public Flux<String> chatAdmin(Long userId, ChatRequest request) {
        return doChat(userId, request, adminToolCallbacks, ADMIN_SYSTEM_PROMPT);
    }

    @Override
    public Flux<String> chatPortal(Long userId, ChatRequest request) {
        return doChat(userId, request, portalToolCallbacks, PORTAL_SYSTEM_PROMPT);
    }

    /**
     * 核心聊天逻辑 — 两阶段策略
     * <p>
     * 阶段1：同步执行工具循环（chatModel.call）
     * 阶段2：真正流式输出最终文本（chatModel.stream，不带工具回调，避免重复调用）
     *
     * @param userId              用户ID
     * @param request             聊天请求
     * @param toolCallbacks       可用工具回调
     * @param defaultSystemPrompt 默认系统提示词
     * @return SSE流式响应
     */
    private Flux<String> doChat(Long userId, ChatRequest request,
                                ToolCallback[] toolCallbacks, String defaultSystemPrompt) {
        // 1. 获取或创建会话（访客匿名模式：userId为空时不落库，使用内存临时会话）
        Long conversationId = request.getConversationId();
        ChatConversation conversation;
        if (userId == null) {
            // 访客模式：忽略传入conversationId，消息仅保留在前端内存，不创建会话
            conversation = null;
            conversationId = null;
        } else if (conversationId == null) {
            ConversationVO conv = conversationService.createConversation(userId, request.getScene());
            conversationId = conv.getId();
            conversation = conversationService.getById(conversationId);
        } else {
            conversation = conversationService.getById(conversationId);
            if (conversation == null) {
                return Flux.just(wrapError("会话不存在"));
            }
            // 校验会话归属权，防止越权访问他人会话
            if (!userId.equals(conversation.getUserId())) {
                log.warn("越权访问会话: userId={}, conversationId={}, ownerId={}",
                        userId, conversationId, conversation.getUserId());
                return Flux.just(wrapError("会话不存在"));
            }
        }
        if (conversation == null && userId != null) {
            return Flux.just(wrapError("会话创建失败"));
        }

        final Long finalConversationId = conversationId;
        log.info("聊天请求: userId={}, conversationId={}, message={}",
                userId, finalConversationId, request.getMessage());

        // 2. 保存用户消息（访客模式跳过持久化）
        if (userId != null) {
            saveMessage(finalConversationId, MessageRoleEnum.USER.getCode(), request.getMessage(), null);
        }

        // 3. 首条消息自动生成标题（访客模式无会话，跳过）
        if (userId != null && DEFAULT_TITLE.equals(conversation.getTitle())) {
            String newTitle = request.getMessage().length() > TITLE_MAX_LENGTH
                    ? request.getMessage().substring(0, TITLE_MAX_LENGTH) + "..."
                    : request.getMessage();
            conversationService.updateTitle(finalConversationId, newTitle);
        }

        // 4. 设置工具执行用户上下文（ThreadLocal，供@Tool访问）
        UserContextHolder.setUserId(userId);
        UserContextHolder.setConversationId(finalConversationId);

        try {
            // 5. 构建系统提示词（访客模式无会话，直接使用默认提示词）
            String systemPrompt = defaultSystemPrompt;
            if (conversation != null && conversation.getSystemPrompt() != null && !conversation.getSystemPrompt().isBlank()) {
                systemPrompt += "\n\n附加上下文: " + conversation.getSystemPrompt();
            }

            // 6. 构建消息历史（滑动窗口，保留最近N条，防止上下文超限；访客模式无历史）
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(systemPrompt));

            List<ChatMessage> history = finalConversationId == null
                    ? new ArrayList<>()
                    : chatMessageMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
                            .eq(ChatMessage::getConversationId, finalConversationId)
                            .orderByDesc(ChatMessage::getCreatedAt)
                            .last("LIMIT " + MAX_HISTORY_MESSAGES));
            // 反转为时间正序
            java.util.Collections.reverse(history);

            for (ChatMessage msg : history) {
                MessageRoleEnum roleEnum = MessageRoleEnum.fromCode(msg.getRole());
                if (roleEnum == null) {
                    continue;
                }
                switch (roleEnum) {
                    case USER -> messages.add(new UserMessage(msg.getContent()));
                    case ASSISTANT -> messages.add(new AssistantMessage(msg.getContent()));
                    case SYSTEM -> messages.add(new SystemMessage(msg.getContent()));
                    case TOOL -> {
                        // 历史工具结果消息：重建为ToolResponseMessage，保证上下文完整
                        if (msg.getToolName() != null && msg.getContent() != null) {
                            messages.add(ToolResponseMessage.builder()
                                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                                            msg.getToolName(), msg.getToolName(), msg.getContent())))
                                    .build());
                        }
                    }
                }
            }

            // 7. 构建带工具回调的Options（仅用于阶段1工具调用）
            ChatModel chatModel = aiModelProvider.getChatModel();
            // 安全获取 OpenAiChatOptions，避免 ChatModel 实现变更时的 ClassCastException
            if (!(chatModel instanceof OpenAiChatModel openAiChatModel)) {
                log.error("当前 ChatModel 非 OpenAiChatModel 实现: {}",
                        chatModel != null ? chatModel.getClass().getName() : "null");
                return Flux.just(wrapError("AI服务配置异常，请稍后重试"));
            }
            OpenAiChatOptions baseOptions = openAiChatModel.getOptions();
            OpenAiChatOptions toolOptions = OpenAiChatOptions.builder()
                    .apiKey(baseOptions.getApiKey())
                    .baseUrl(baseOptions.getBaseUrl())
                    .model(baseOptions.getModel())
                    .toolCallbacks(toolCallbacks)
                    .build();

            // 8. 阶段1：同步执行工具循环（收集工具/待确认事件，meta后统一发出）
            List<String> preStreamEvents = new ArrayList<>();
            callWithTools(messages, toolOptions, chatModel, toolCallbacks, preStreamEvents);

            // 9. 阶段2：构建不带工具回调的流式Options，避免流式阶段触发工具调用导致空文本
            OpenAiChatOptions streamOptions = OpenAiChatOptions.builder()
                    .apiKey(baseOptions.getApiKey())
                    .baseUrl(baseOptions.getBaseUrl())
                    .model(baseOptions.getModel())
                    .build();

            log.info("开始流式输出: conversationId={}", finalConversationId);

            // 元数据消息（第一个SSE事件，结构化JSON：包含type和conversationId；访客模式返回guest标识）
            String metaJson = "{\"type\":\"meta\",\"conversationId\":\""
                    + (finalConversationId != null ? finalConversationId : "guest") + "\"}";

            Flux<String> tokenStream = chatModel.stream(new Prompt(messages, streamOptions))
                    .mapNotNull(response -> {
                        if (response.getResult() == null || response.getResult().getOutput() == null) {
                            return null;
                        }
                        String text = response.getResult().getOutput().getText();
                        return (text != null && !text.isEmpty()) ? text : null;
                    });

            // 用 Flux.create 包装：同时流式输出 + 累积完整文本用于保存
            // 每个token统一包装为结构化JSON：{"type":"token","content":"<token内容>"}
            // 通过JSON字符串编码，换行符等特殊字符被转义为单行传输，避免SSE帧被破坏
            Flux<String> saveTrigger = Flux.create(sink -> {
                StringBuilder fullText = new StringBuilder();
                tokenStream.subscribe(
                        token -> {
                            fullText.append(token);
                            sink.next(wrapToken(token));
                        },
                        error -> {
                            log.error("流式输出异常: conversationId={}", finalConversationId, error);
                            // 异常时保存已生成的部分文本，保证历史上下文完整（访客模式跳过）
                            String partialText = fullText.toString();
                            if (finalConversationId != null && !partialText.isEmpty()) {
                                saveMessage(finalConversationId, MessageRoleEnum.ASSISTANT.getCode(),
                                        partialText, null);
                                conversationService.updateStats(finalConversationId, partialText.length() / 4);
                            }
                            sink.next(wrapError("AI服务暂时不可用，请稍后重试"));
                            sink.next("[FINAL]");
                            sink.complete();
                        },
                        () -> {
                            // 流完成，保存完整回复到DB（访客模式跳过）
                            String finalText = fullText.toString();
                            if (finalConversationId != null && !finalText.isEmpty()) {
                                saveMessage(finalConversationId, MessageRoleEnum.ASSISTANT.getCode(),
                                        finalText, null);
                                int tokenCount = finalText.length() / 4;
                                conversationService.updateStats(finalConversationId, tokenCount);
                                log.info("流式输出完成并保存: conversationId={}, length={}",
                                        finalConversationId, finalText.length());
                            }
                            // 发送明确结束标记，前端据此判断流正常结束
                            sink.next("[FINAL]");
                            sink.complete();
                        }
                );
            });

            // concat保证顺序：元数据 → 工具/待确认事件 → token流
            return Flux.just(metaJson)
                    .concatWith(Flux.fromIterable(preStreamEvents))
                    .concatWith(saveTrigger);

        } catch (Exception e) {
            log.error("聊天失败: conversationId={}", finalConversationId, e);
            return Flux.just(wrapError("AI服务暂时不可用，请稍后重试"));
        } finally {
            UserContextHolder.clear();
        }
    }

    /**
     * 工具调用循环（同步）
     * 最多执行 MAX_TOOL_ITERATIONS 轮，每轮处理AI返回的工具调用
     * <p>
     * 当AI返回无工具调用时，该最终回复不加入messages（留给stream阶段重新生成），
     * 避免stream阶段看到"已回答过"导致返回空流。
     *
     * @param messages       消息列表（会被追加工具调用链）
     * @param options        带工具回调的Options
     * @param chatModel      聊天模型
     * @param toolCallbacks  当前可用工具回调
     */
    private void callWithTools(List<Message> messages, OpenAiChatOptions options, ChatModel chatModel,
                               ToolCallback[] toolCallbacks, List<String> preStreamEvents) {
        for (int i = 0; i < MAX_TOOL_ITERATIONS; i++) {
            ChatResponse response = chatModel.call(new Prompt(messages, options));
            AssistantMessage assistantMsg = response.getResult().getOutput();

            // 无工具调用 = 最终回复，不加入messages，交给stream阶段生成
            if (assistantMsg.getToolCalls() == null || assistantMsg.getToolCalls().isEmpty()) {
                log.info("工具调用阶段结束，无更多工具调用，迭代次数={}", i + 1);
                return;
            }

            // 仅含工具调用的assistant消息才加入（保持调用链完整）
            messages.add(assistantMsg);

            // 处理工具调用
            log.info("工具调用迭代 {}: {} 次工具调用", i + 1, assistantMsg.getToolCalls().size());

            for (AssistantMessage.ToolCall toolCall : assistantMsg.getToolCalls()) {
                log.info("  执行工具: id={}, name={}, args={}", toolCall.id(), toolCall.name(),
                        toolCall.arguments().length() > 100
                                ? toolCall.arguments().substring(0, 100) + "..."
                                : toolCall.arguments());
                // 收集工具事件（meta后发出，前端渲染工具调用徽章/步骤）
                preStreamEvents.add("{\"type\":\"tool\",\"name\":"
                        + objectMapper.writeValueAsString(toolCall.name()) + "}");
                String result = executeTool(toolCallbacks, toolCall.name(), toolCall.arguments());
                // 待确认操作：解析工具结果，向流中注入结构化确认卡片事件
                collectPendingActionEvent(result, preStreamEvents);
                // 截断过长的工具结果，防止撑爆上下文
                String truncatedResult = truncateResult(result);
                log.info("  工具结果: length={}, 截断后length={}",
                        result != null ? result.length() : 0,
                        truncatedResult.length());

                // 将工具结果作为消息加入列表
                messages.add(ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                toolCall.id(), toolCall.name(), truncatedResult
                        )))
                        .build());
            }
        }
        log.warn("工具调用达到最大迭代次数: {}", MAX_TOOL_ITERATIONS);
    }

    /**
     * 解析工具结果中的待确认操作信息，向前置事件列表注入确认卡片事件
     * 事件格式：{"type":"pending_action","id":123,"sql":"...","question":"..."}
     *
     * @param toolResult      工具原始结果JSON字符串
     * @param preStreamEvents 前置事件收集器
     */
    private void collectPendingActionEvent(String toolResult, List<String> preStreamEvents) {
        if (toolResult == null || !toolResult.contains("\"pendingActionId\"")) {
            return;
        }
        try {
            var node = objectMapper.readTree(toolResult);
            if (node.get("pendingActionId") == null || node.get("pendingActionId").isNull()) {
                return;
            }
            StringBuilder json = new StringBuilder("{\"type\":\"pending_action\",\"id\":")
                    .append(node.get("pendingActionId").asLong());
            if (node.get("sql") != null && !node.get("sql").isNull()) {
                json.append(",\"sql\":").append(objectMapper.writeValueAsString(node.get("sql").asString()));
            }
            if (node.get("question") != null && !node.get("question").isNull()) {
                json.append(",\"question\":").append(objectMapper.writeValueAsString(node.get("question").asString()));
            }
            json.append("}");
            preStreamEvents.add(json.toString());
        } catch (Exception e) {
            log.warn("解析待确认操作事件失败，跳过确认卡片推送", e);
        }
    }

    /**
     * 截断工具结果，防止撑爆LLM上下文窗口
     *
     * @param result 原始工具结果
     * @return 截断后的结果
     */
    private String truncateResult(String result) {
        if (result == null) {
            return "";
        }
        if (result.length() <= MAX_TOOL_RESULT_LENGTH) {
            return result;
        }
        return result.substring(0, MAX_TOOL_RESULT_LENGTH) + "...(结果已截断)";
    }

    /**
     * 将token包装为结构化JSON字符串
     * 通过JSON编码，token中的换行符等特殊字符被转义，确保SSE单行传输
     *
     * @param token 流式token文本
     * @return 结构化JSON字符串，格式：{"type":"token","content":"<token>"}
     */
    private String wrapToken(String token) {
        try {
            return "{\"type\":\"token\",\"content\":" + objectMapper.writeValueAsString(token) + "}";
        } catch (JacksonException e) {
            log.warn("JSON编码token失败，降级为空内容", e);
            return "{\"type\":\"token\",\"content\":\"\"}";
        }
    }

    /**
     * 将错误信息包装为结构化JSON字符串（脱敏，不暴露内部实现细节）
     *
     * @param message 错误消息
     * @return 结构化JSON字符串，格式：{"type":"error","message":"<错误信息>"}
     */
    private String wrapError(String message) {
        try {
            return "{\"type\":\"error\",\"message\":" + objectMapper.writeValueAsString(message) + "}";
        } catch (JacksonException e) {
            log.warn("JSON编码错误消息失败，降级为固定提示", e);
            return "{\"type\":\"error\",\"message\":\"AI服务异常\"}";
        }
    }

    /**
     * 执行单个工具调用
     *
     * @param callbacks 工具回调数组
     * @param toolName  工具名称
     * @param arguments 工具参数JSON
     * @return 工具执行结果
     */
    private String executeTool(ToolCallback[] callbacks, String toolName, String arguments) {
        for (ToolCallback callback : callbacks) {
            if (callback.getToolDefinition().name().equals(toolName)) {
                try {
                    return callback.call(arguments);
                } catch (Exception e) {
                    log.error("工具执行失败: {}", toolName, e);
                    return "工具执行失败: " + e.getMessage();
                }
            }
        }
        return "未知工具: " + toolName;
    }

    /**
     * 保存聊天消息
     *
     * @param conversationId 会话ID
     * @param role           消息角色
     * @param content        消息内容
     * @param toolName       工具名称（非工具消息为null）
     */
    private void saveMessage(Long conversationId, Integer role, String content, String toolName) {
        // 访客临时会话无持久化目标，直接跳过
        if (conversationId == null) {
            return;
        }
        ChatMessage msg = new ChatMessage();
        msg.setConversationId(conversationId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setMessageType(MESSAGE_TYPE_TEXT);
        // 工具消息补全工具名称，保证历史上下文可重建为 ToolResponseMessage
        msg.setToolName(toolName);
        msg.setStatus(MessageStatusEnum.COMPLETED.getCode());
        chatMessageMapper.insert(msg);
    }
}
