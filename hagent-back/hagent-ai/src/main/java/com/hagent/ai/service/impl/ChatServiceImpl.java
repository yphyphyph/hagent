package com.hagent.ai.service.impl;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.hagent.ai.core.AiModelProvider;
import com.hagent.ai.core.UserContextHolder;
import com.hagent.ai.mapper.ChatMessageMapper;
import com.hagent.ai.mcp.AnalyzeImageTool;
import com.hagent.ai.model.dto.ChatRequest;
import com.hagent.ai.model.entity.ChatConversation;
import com.hagent.ai.model.entity.ChatMessage;
import com.hagent.ai.model.entity.ChatPendingAction;
import com.hagent.ai.model.enums.MessageRoleEnum;
import com.hagent.ai.model.enums.MessageStatusEnum;
import com.hagent.ai.model.vo.ConversationVO;
import com.hagent.ai.model.vo.PendingActionVO;
import com.hagent.ai.service.ChatService;
import com.hagent.ai.service.ConversationService;
import com.hagent.ai.service.PendingActionService;
import com.hagent.ai.tool.KnowledgeTool;
import com.hagent.ai.tool.TextToSqlTool;
import com.hagent.common.exception.BusinessException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /** 待确认操作服务，用于确认短路逻辑 */
    private final PendingActionService pendingActionService;

    /** 统一工具回调（管理端与门户端共用同一注册集：知识库检索 + Text to SQL + 图片识别） */
    private final ToolCallback[] unifiedToolCallbacks;

    /** 工具调用最大迭代次数 */
    private static final int MAX_TOOL_ITERATIONS = 5;

    /** 历史消息最大保留条数（滑动窗口，防止上下文超限） */
    private static final int MAX_HISTORY_MESSAGES = 20;

    /** 工具结果最大字符数（超出截断，防止撑爆上下文） */
    private static final int MAX_TOOL_RESULT_LENGTH = 4000;

    /** 标题最大字符数 */
    private static final int TITLE_MAX_LENGTH = 20;

    /** 日志中工具参数最大截断长度 */
    private static final int MAX_LOG_ARGS_LEN = 100;

    /** 字符数转 token 数的估算比例（约 4 字符/token） */
    private static final int CHARS_PER_TOKEN = 4;

    /** 新会话默认标题 */
    private static final String DEFAULT_TITLE = "新会话";

    /** 文本形式工具调用标记（部分模型在无工具定义阶段会退化为此格式） */
    private static final String TEXT_TOOL_CALL_MARKER = "<tool_call>";

    /** 文本形式工具调用解析：<function=name> */
    private static final Pattern TEXT_FUNCTION_PATTERN = Pattern.compile("<function=([\\w.-]+)\\s*>");

    /** 文本形式工具调用解析：<parameter=key>value</parameter> */
    private static final Pattern TEXT_PARAM_PATTERN = Pattern.compile("<parameter=([\\w.-]+)\\s*>\\s*([\\s\\S]*?)</parameter>");

    /** 纯数字（用于 topK 等参数的字符串→数值转换） */
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("-?\\d+");

    /** 文本形式工具调用的合成ID序列 */
    private final AtomicInteger textToolCallSeq = new AtomicInteger();

    /** 消息类型文本 */
    private static final String MESSAGE_TYPE_TEXT = "TEXT";

    /** 访客拦截提示消息（演示模式：未登录用户不消耗真实模型token） */
    private static final String GUEST_BLOCKED_MESSAGE = "演示模式：登录后可体验真实 AI 能力";

    /** 确认词（精确匹配用，中文直接 equals，英文忽略大小写） */
    private static final String[] CONFIRM_WORDS = {"是", "确认", "可以", "OK", "ok", "执行", "确定", "好", "yes", "Yes", "行", "嗯"};

    /** 取消词 */
    private static final String[] CANCEL_WORDS = {"取消", "不", "不用", "算了", "no", "No", "放弃", "停止"};

    /**
     * 管理端系统提示词
     * 工具说明与统一注册集一一对应：searchKnowledge / queryBusinessData / analyze_image
     *
     * @author H
     */
    private static final String ADMIN_SYSTEM_PROMPT = """
            你是 HAgent 智能助手，可以使用以下三个工具：
            - searchKnowledge: 按语义检索知识库文档的内容片段。适用于问"某主题讲了什么/要点/内容/细节"
            - queryBusinessData: 把问题转成 SQL 查询业务数据（生成后需用户在确认卡片上确认才执行）。适用于任何计数/统计/排名/聚合类问题
            - analyze_image: 使用多模态视觉模型识别图片并回答问题，图片支持本地路径、http(s) 链接或 base64

            工具选择规则（必须严格遵守）：
            1. 问"有多少/数量/几个/统计/排名/前N/最大/最小/平均"等计数或聚合类问题时，
               必须使用 queryBusinessData（如"知识库有多少文件"→统计 knowledge_file 表），
               禁止用 searchKnowledge 检索结果来猜测数量
            2. 问某主题的内容、要点、详情时，使用 searchKnowledge
            3. 当用户提供图片或要求识别图片内容时，使用 analyze_image
            4. 不要用相似关键词重复调用同一工具；结果为空或不理想时，应换不同表述一次，
               仍不行就基于已有信息如实回答并说明局限
            5. 回答必须基于工具返回的真实数据，禁止编造数字或结论
            6. 写操作（INSERT/UPDATE/DELETE）需用户回复确认后执行，询问确认时绝不展示SQL语句。
            """;

    /**
     * 门户端系统提示词
     * 与管理端共用同一工具注册集，仅人设定位不同：作品集网站助手
     *
     * @author H
     */
    private static final String PORTAL_SYSTEM_PROMPT = """
            你是 HAgent 智能助手，一个 Java 全栈与 AI 应用开发者（H）的作品集网站助手。
            你可以使用以下三个工具：
            - searchKnowledge: 按语义检索网站知识库文档的内容片段。适用于问"某主题讲了什么/要点/内容/细节"
            - queryBusinessData: 把问题转成 SQL 查询业务数据（生成后需用户在确认卡片上确认才执行）。适用于任何计数/统计/排名/聚合类问题
            - analyze_image: 使用多模态视觉模型识别图片并回答问题，图片支持本地路径、http(s) 链接或 base64

            请遵循以下原则：
            1. 保持友好、简洁、专业，回答中文，以清晰易读的格式展示结果
            2. 问"有多少/数量/几个/统计/排名"等计数或聚合类问题时，使用 queryBusinessData，
               禁止用 searchKnowledge 检索结果来猜测数量
            3. 当问题涉及知识库内容、要点、详情时，先调用 searchKnowledge 检索再回答
            4. 当用户提供图片或要求识别图片内容时，使用 analyze_image
            5. 不确定的内容不要编造，明确说明；回答必须基于工具返回的真实数据
            6. 写操作（INSERT/UPDATE/DELETE）需用户回复确认后执行，询问确认时绝不展示SQL语句。
            """;

    /**
     * 构造方法：注入聊天依赖并统一注册工具回调
     * 管理端与门户端共用同一工具集（知识库检索 + Text to SQL + 图片识别），仅系统提示词不同
     *
     * @param aiModelProvider    AI模型提供者
     * @param conversationService 会话服务
     * @param chatMessageMapper  聊天消息Mapper
     * @param chatClient         ChatClient（可空，当前未使用）
     * @param objectMapper       JSON序列化工具
     * @param knowledgeTool      知识库检索工具
     * @param textToSqlTool      Text to SQL 工具
     * @param analyzeImageTool   图片识别工具
     * @author H
     */
    public ChatServiceImpl(AiModelProvider aiModelProvider,
                           ConversationService conversationService,
                           ChatMessageMapper chatMessageMapper,
                           @Nullable org.springframework.ai.chat.client.ChatClient chatClient,
                           ObjectMapper objectMapper,
                           @Lazy KnowledgeTool knowledgeTool,
                           @Lazy TextToSqlTool textToSqlTool,
                           @Lazy AnalyzeImageTool analyzeImageTool,
                           PendingActionService pendingActionService) {
        this.aiModelProvider = aiModelProvider;
        this.conversationService = conversationService;
        this.chatMessageMapper = chatMessageMapper;
        this.objectMapper = objectMapper;
        this.pendingActionService = pendingActionService;
        // 统一工具集：知识库检索 + Text to SQL + 图片识别（管理端与门户端共用）
        this.unifiedToolCallbacks = ToolCallbacks.from(knowledgeTool, textToSqlTool, analyzeImageTool);
    }

    /**
     * 管理端聊天：使用统一工具集与管理端系统提示词
     *
     * @param userId  用户ID
     * @param request 聊天请求
     * @return SSE流式响应
     * @author H
     */
    @Override
    public Flux<String> chatAdmin(Long userId, ChatRequest request) {
        return doChat(userId, request, unifiedToolCallbacks, ADMIN_SYSTEM_PROMPT);
    }

    /**
     * 门户端聊天：使用统一工具集与门户端系统提示词
     *
     * @param userId  用户ID（为空时访客请求被拦截）
     * @param request 聊天请求
     * @return SSE流式响应
     * @author H
     */
    @Override
    public Flux<String> chatPortal(Long userId, ChatRequest request) {
        return doChat(userId, request, unifiedToolCallbacks, PORTAL_SYSTEM_PROMPT);
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
            // 访客拦截：演示模式下不进入真实模型工具循环，直接返回提示并结束流，避免浪费token
            log.info("访客请求已拦截，演示模式返回固定提示: scene={}", request.getScene());
            return Flux.just(wrapError(GUEST_BLOCKED_MESSAGE), "[DONE]");
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

        // 4.1 确认/取消短路：若存在待确认操作且用户发送确认/取消词，直接执行对应操作
        if (conversation != null && userId != null) {
            ChatPendingAction pending = pendingActionService.findLatestPendingByConversationId(finalConversationId);
            if (pending != null) {
                String userMsg = request.getMessage();
                if (isConfirm(userMsg)) {
                    try {
                        PendingActionVO confirmed = pendingActionService.confirmAction(pending.getId(), userId);
                        int rowCount = extractRowCount(confirmed.getPayloadJson());
                        String text = "操作已执行完成，共影响 " + rowCount + " 行数据。";
                        // 保存助手回复到历史，确保上下文完整
                        saveMessage(finalConversationId, MessageRoleEnum.ASSISTANT.getCode(), text, null);
                        conversationService.updateStats(finalConversationId, text.length() / CHARS_PER_TOKEN);
                        return Flux.just(wrapText(text), "[DONE]");
                    } catch (BusinessException e) {
                        log.warn("确认操作失败: pendingId={}, msg={}", pending.getId(), e.getMessage());
                        String errText = e.getMessage() != null ? e.getMessage() : "操作执行失败";
                        saveMessage(finalConversationId, MessageRoleEnum.ASSISTANT.getCode(), errText, null);
                        return Flux.just(wrapText(errText), "[DONE]");
                    } finally {
                        UserContextHolder.clear();
                    }
                } else if (isCancel(userMsg)) {
                    try {
                        pendingActionService.cancelAction(pending.getId(), userId);
                        String text = "操作已取消。";
                        saveMessage(finalConversationId, MessageRoleEnum.ASSISTANT.getCode(), text, null);
                        conversationService.updateStats(finalConversationId, text.length() / CHARS_PER_TOKEN);
                        return Flux.just(wrapText(text), "[DONE]");
                    } catch (BusinessException e) {
                        log.warn("取消操作失败: pendingId={}, msg={}", pending.getId(), e.getMessage());
                        String errText = e.getMessage() != null ? e.getMessage() : "取消失败";
                        return Flux.just(wrapText(errText), "[DONE]");
                    } finally {
                        UserContextHolder.clear();
                    }
                }
                // 非确认/取消词，落入正常工具循环，pending 保留
            }
        }

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
                                conversationService.updateStats(finalConversationId, partialText.length() / CHARS_PER_TOKEN);
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
                                int tokenCount = finalText.length() / CHARS_PER_TOKEN;
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
                // 兜底：部分模型会以 <tool_call> 文本格式输出工具调用（而非原生 tool_calls），
                // 若不处理，这段XML会泄漏为最终回答。此处解析并按原生协议执行
                String text = assistantMsg.getText() == null ? "" : assistantMsg.getText();
                if (text.contains(TEXT_TOOL_CALL_MARKER) && i < MAX_TOOL_ITERATIONS - 1) {
                    AssistantMessage.ToolCall synthetic = parseTextToolCall(text);
                    if (synthetic != null) {
                        log.warn("检测到文本形式工具调用，已转换执行: name={}, args={}", synthetic.name(), synthetic.arguments());
                        // 以原生 tool_calls 结构回填消息，保证协议合法（tool 消息必须跟在带 tool_calls 的 assistant 消息之后）
                        messages.add(AssistantMessage.builder()
                                .content("")
                                .toolCalls(List.of(synthetic))
                                .build());
                        preStreamEvents.add("{\"type\":\"tool\",\"name\":"
                                + objectMapper.writeValueAsString(synthetic.name()) + "}");
                        String result = executeTool(toolCallbacks, synthetic.name(), synthetic.arguments());
                        messages.add(ToolResponseMessage.builder()
                                .responses(List.of(new ToolResponseMessage.ToolResponse(
                                        synthetic.id(), synthetic.name(), truncateResult(result))))
                                .build());
                        continue;
                    }
                }
                log.info("工具调用阶段结束，无更多工具调用，迭代次数={}", i + 1);
                return;
            }

            // 仅含工具调用的assistant消息才加入（保持调用链完整）
            messages.add(assistantMsg);

            // 处理工具调用
            log.info("工具调用迭代 {}: {} 次工具调用", i + 1, assistantMsg.getToolCalls().size());

            for (AssistantMessage.ToolCall toolCall : assistantMsg.getToolCalls()) {
                log.info("  执行工具: id={}, name={}, args={}", toolCall.id(), toolCall.name(),
                        toolCall.arguments().length() > MAX_LOG_ARGS_LEN
                                ? toolCall.arguments().substring(0, MAX_LOG_ARGS_LEN) + "..."
                                : toolCall.arguments());
                // 收集工具事件（meta后发出，前端渲染工具调用徽章/步骤）
                preStreamEvents.add("{\"type\":\"tool\",\"name\":"
                        + objectMapper.writeValueAsString(toolCall.name()) + "}");
                String result = executeTool(toolCallbacks, toolCall.name(), toolCall.arguments());
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
     * 解析文本形式的工具调用
     * 格式：<tool_call>\n<function=name>\n<parameter=k>v</parameter>...\n</tool_call>
     *
     * @param text 模型输出的文本
     * @return 合成的原生 ToolCall（ID前缀 call_text_），无法解析时返回 null
     */
    private AssistantMessage.ToolCall parseTextToolCall(String text) {
        try {
            Matcher func = TEXT_FUNCTION_PATTERN.matcher(text);
            if (!func.find()) {
                return null;
            }
            String name = func.group(1).trim();
            Map<String, Object> args = new LinkedHashMap<>();
            Matcher param = TEXT_PARAM_PATTERN.matcher(text);
            while (param.find()) {
                String key = param.group(1).trim();
                String value = param.group(2).trim();
                // topK 等数值参数转数字，避免 JSON Schema 校验失败
                if (NUMERIC_PATTERN.matcher(value).matches() && key.toLowerCase().matches(".*(top_?k|limit|count|size|page).*")) {
                    args.put(key, Long.parseLong(value));
                } else {
                    args.put(key, value);
                }
            }
            return new AssistantMessage.ToolCall(
                    "call_text_" + textToolCallSeq.incrementAndGet(), "function", name,
                    objectMapper.writeValueAsString(args));
        } catch (Exception e) {
            log.warn("解析文本形式工具调用失败，放弃转换", e);
            return null;
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
     * 判断是否为确认词
     * 逻辑：trim 后，中文精确 equals，英文精确 equalsIgnoreCase
     *
     * @param message 用户消息
     * @return 是否为确认词
     * @author H
     */
    private boolean isConfirm(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        for (String word : CONFIRM_WORDS) {
            if (word.equals(normalized) || word.equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否为取消词
     *
     * @param message 用户消息
     * @return 是否为取消词
     * @author H
     */
    private boolean isCancel(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        for (String word : CANCEL_WORDS) {
            if (word.equals(normalized) || word.equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将文本包装为 token 类型的 SSE JSON 字符串
     *
     * @param text 文本内容
     * @return SSE JSON 字符串
     * @author H
     */
    private String wrapText(String text) {
        return wrapToken(text);
    }

    /**
     * 从 payloadJson 中提取 rowCount 字段
     *
     * @param payloadJson 载荷 JSON
     * @return 行数，解析失败返回 0
     * @author H
     */
    private int extractRowCount(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return 0;
        }
        try {
            var node = objectMapper.readTree(payloadJson);
            if (node.has("rowCount") && node.get("rowCount").isNumber()) {
                return node.get("rowCount").asInt();
            }
        } catch (Exception e) {
            log.warn("解析 rowCount 失败: {}", payloadJson, e);
        }
        return 0;
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
