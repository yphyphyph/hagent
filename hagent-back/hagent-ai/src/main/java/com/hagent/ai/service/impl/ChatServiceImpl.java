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
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
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
    private static final int MAX_TOOL_ITERATIONS = 8;

    /** 连续重复工具调用检测阈值：同一工具连续调用达到该次数视为发散循环，强制收敛，避免无限空转 */
    private static final int REPEAT_TOOL_CALL_LIMIT = 4;

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

    /** 思考内容分片大小（用于非流式场景的模拟流式分片） */
    private static final int THINKING_CHUNK_SIZE = 30;

    /** 思考分片间的发射间隔，确保前端收到事件在时间上分散而非一次性爆发 */
    private static final Duration THINKING_DELAY = Duration.ofMillis(30);

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

    /** 幻觉工具调用标签过滤：匹配 <tool_call>...</tool_call>（DOTALL，大小写敏感） */
    private static final Pattern HALLUCINATED_TOOL_CALL_PATTERN =
            Pattern.compile("<tool_call>.*?</tool_call>", Pattern.DOTALL);

    /** 幻觉函数标签残留过滤：匹配 <function=...> 片段 */
    private static final Pattern HALLUCINATED_FUNCTION_PATTERN =
            Pattern.compile("<function=.*?>");

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

            数据库 Schema（精简）：
            - knowledge_file(id,file_name,deleted)
            - chat_conversation(id,user_id,title,deleted)
            - chat_message(id,conversation_id,role,deleted)
            - hagent_user(id,username,deleted)
            - chat_pending_action(...)

            规则：仅生成单条 SELECT/INSERT/UPDATE/DELETE，需符合校验器规则，UPDATE/DELETE 必须带 WHERE。
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

            数据库 Schema（精简）：
            - knowledge_file(id,file_name,deleted)
            - chat_conversation(id,user_id,title,deleted)
            - chat_message(id,conversation_id,role,deleted)
            - hagent_user(id,username,deleted)
            - chat_pending_action(...)

            规则：仅生成单条 SELECT/INSERT/UPDATE/DELETE，需符合校验器规则，UPDATE/DELETE 必须带 WHERE。
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

            // 8. 累积思考内容，用于最终持久化（工具阶段 + 流式阶段共享）
            StringBuilder thinkingAccum = new StringBuilder();

            // 9. 阶段2：构建不带工具回调的流式Options，避免流式阶段触发工具调用导致空文本
            OpenAiChatOptions streamOptions = OpenAiChatOptions.builder()
                    .apiKey(baseOptions.getApiKey())
                    .baseUrl(baseOptions.getBaseUrl())
                    .model(baseOptions.getModel())
                    .build();

            // 元数据消息（第一个SSE事件，结构化JSON：包含type和conversationId；访客模式返回guest标识）
            String metaJson = "{\"type\":\"meta\",\"conversationId\":\""
                    + (finalConversationId != null ? finalConversationId : "guest") + "\"}";

            // 真流式改造：工具循环移入 Flux.create，订阅即刻发射 meta/stage，
            // 工具阶段的 thinking/tool 事件随执行实时写入 sink 直达前端，不再缓存到列表末尾集中爆发
            // subscribeOn(boundedElastic)：阻塞的工具循环不占用SSE订阅线程，订阅句柄立即可用于取消
            return Flux.<String>create(sink -> {
                try {
                    // 工具执行依赖 ThreadLocal 用户上下文，此处线程与 doChat 调用线程不同，需重新设置
                    UserContextHolder.setUserId(userId);
                    UserContextHolder.setConversationId(finalConversationId);

                    sink.next(metaJson);
                    // 阶段进度：正在理解问题...
                    sink.next(wrapStage("正在理解问题..."));
                    // 阶段进度：正在分析问题...（进入工具调用循环前，顺序保证在 tool 事件前）
                    sink.next(wrapStage("正在分析问题..."));

                    // 阶段1：同步工具循环（阻塞执行，但事件实时发射到前端）
                    callWithTools(messages, toolOptions, chatModel, toolCallbacks, sink, thinkingAccum);

                    // 阶段进度：正在组织答案...（工具调用结束后、流式开始前）
                    sink.next(wrapStage("正在组织答案..."));
                } catch (Exception e) {
                    log.error("聊天失败: conversationId={}", finalConversationId, e);
                    sink.next(wrapError("AI服务暂时不可用，请稍后重试"));
                    sink.next("[FINAL]");
                    sink.complete();
                    return;
                } finally {
                    // 工具循环在本线程执行，清理 ThreadLocal 防止线程复用时身份串扰
                    UserContextHolder.clear();
                }

                log.info("开始流式输出: conversationId={}", finalConversationId);

                StringBuilder fullText = new StringBuilder();
                // 文本工具调用块剥离状态（流式阶段）：模型可能在工具循环结束后仍以 <tool_call> 文本方式表达工具意图，
                // 该块会被拆分到多个 token 逐片到达，单 token 正则无法命中，需按状态机累积完整块后整体剥离
                final boolean[] inToolCallBlock = {false};
                final StringBuilder toolCallBlockBuf = new StringBuilder();
                // 阶段2：真正的增量流，逐个响应提取 thinking 与 token 并交织发射
                // 每个token统一包装为结构化JSON：{"type":"token","content":"<token内容>"}
                // 通过JSON字符串编码，换行符等特殊字符被转义为单行传输，避免SSE帧被破坏
                Flux<ChatResponse> rawStream = chatModel.stream(new Prompt(messages, streamOptions));
                Disposable rawDisposable = rawStream.subscribe(
                        response -> {
                            // 1) 优先发射 thinking 增量（若模型提供了 reasoning_content / thinking）
                            // 真流式：直接透传每个增量的 thinking delta；大块增量切分后同步按间隔发射，保证与 token 的先后顺序
                            String thinkingChunk = extractThinking(response);
                            if (thinkingChunk != null && !thinkingChunk.isEmpty()) {
                                // 累积思考内容用于入库持久化
                                thinkingAccum.append(thinkingChunk);
                                emitThinkingToSink(sink, thinkingChunk);
                            }
                            // 2) 再发射 token 增量
                            String text = null;
                            if (response.getResult() != null && response.getResult().getOutput() != null) {
                                text = response.getResult().getOutput().getText();
                            }
                            if (text != null && !text.isEmpty()) {
                                // 剥离可能被拆分到多个 token 的文本工具调用块（如 <tool_call>...</tool_call>），防止 XML 碎片泄漏为正文
                                String filtered = stripToolCallBlock(text, inToolCallBlock, toolCallBlockBuf);
                                // 兜底：过滤单 token 内完整的幻觉工具调用标签残留
                                filtered = HALLUCINATED_TOOL_CALL_PATTERN.matcher(filtered).replaceAll("");
                                filtered = HALLUCINATED_FUNCTION_PATTERN.matcher(filtered).replaceAll("");
                                // 过滤后为空或仅空白则跳过该 token，不向前端发射
                                if (filtered.isBlank()) {
                                    return;
                                }
                                fullText.append(filtered);
                                sink.next(wrapToken(filtered));
                            }
                        },
                        error -> {
                            log.error("流式输出异常: conversationId={}", finalConversationId, error);
                            // 异常时保存已生成的部分文本+思考内容，保证历史上下文完整（访客模式跳过）
                            String partialText = fullText.toString();
                            String partialThinking = thinkingAccum.length() > 0 ? thinkingAccum.toString() : null;
                            if (finalConversationId != null && (!partialText.isEmpty() || partialThinking != null)) {
                                saveMessage(finalConversationId, MessageRoleEnum.ASSISTANT.getCode(),
                                        partialText, null, partialThinking);
                                conversationService.updateStats(finalConversationId, partialText.length() / CHARS_PER_TOKEN);
                            }
                            sink.next(wrapError("AI服务暂时不可用，请稍后重试"));
                            sink.next("[FINAL]");
                            sink.complete();
                        },
                        () -> {
                            // 流完成，保存完整回复+思考内容到DB（访客模式跳过）
                            String finalText = fullText.toString();
                            String finalThinking = thinkingAccum.length() > 0 ? thinkingAccum.toString() : null;
                            if (finalConversationId != null && (!finalText.isEmpty() || finalThinking != null)) {
                                saveMessage(finalConversationId, MessageRoleEnum.ASSISTANT.getCode(),
                                        finalText, null, finalThinking);
                                int tokenCount = finalText.length() / CHARS_PER_TOKEN;
                                conversationService.updateStats(finalConversationId, tokenCount);
                                log.info("流式输出完成并保存: conversationId={}, length={}, thinkingLength={}",
                                        finalConversationId, finalText.length(),
                                        finalThinking != null ? finalThinking.length() : 0);
                            }
                            // 发送明确结束标记，前端据此判断流正常结束
                            sink.next("[FINAL]");
                            sink.complete();
                        }
                );
                // 客户端断连/取消时同步取消底层模型流，避免继续生成浪费token
                sink.onDispose(rawDisposable);
            }).subscribeOn(Schedulers.boundedElastic());

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
     * @param sink           SSE事件sink，工具/思考事件实时写入直达前端
     * @param thinkingAccum  思考内容累积器（用于入库持久化）
     */
    private void callWithTools(List<Message> messages, OpenAiChatOptions options, ChatModel chatModel,
                               ToolCallback[] toolCallbacks, FluxSink<String> sink,
                               StringBuilder thinkingAccum) {
        // 连续重复工具调用追踪：判定模型陷入发散循环（同一工具反复调用但无实质收敛）时强制结束
        String lastToolName = null;
        int repeatCount = 0;
        for (int i = 0; i < MAX_TOOL_ITERATIONS; i++) {
            // 客户端断连后终止工具循环，避免继续消耗模型token
            if (sink.isCancelled()) {
                log.info("SSE已取消，终止工具调用循环");
                return;
            }
            // 已注入收敛指令（模型陷入重复调用被强制收尾），直接结束工具循环
            if (!messages.isEmpty() && messages.get(messages.size() - 1) instanceof SystemMessage) {
                log.info("已注入收敛指令，结束工具调用循环");
                return;
            }
            ChatResponse response = chatModel.call(new Prompt(messages, options));
            AssistantMessage assistantMsg = response.getResult().getOutput();

            // 提取推理过程：兼容 reasoning_content / reasoningContent / thinking / reasoning 等键
            // 工具阶段使用 chatModel.call() 同步获取，thinking 到达后实时切分发射到前端（边执行边展示）
            String thinkingChunk = extractThinking(response);
            if (thinkingChunk != null && !thinkingChunk.isEmpty()) {
                // 累积到 thinkingAccum 用于入库，并实时发射供前端流式展示
                if (thinkingAccum != null) {
                    thinkingAccum.append(thinkingChunk);
                }
                emitThinkingToSink(sink, thinkingChunk);
                log.debug("工具阶段思考内容已实时发射: length={}, iter={}", thinkingChunk.length(), i + 1);
            }

            // 无工具调用 = 最终回复，不加入messages，交给stream阶段生成
            if (assistantMsg.getToolCalls() == null || assistantMsg.getToolCalls().isEmpty()) {
                // 兜底：部分模型会以 <tool_call> 文本格式输出工具调用（而非原生 tool_calls），
                // 若不处理，这段XML会泄漏为最终回答。此处解析并按原生协议执行
                String text = assistantMsg.getText() == null ? "" : assistantMsg.getText();
                if (text.contains(TEXT_TOOL_CALL_MARKER) && i < MAX_TOOL_ITERATIONS - 1) {
                    AssistantMessage.ToolCall synthetic = parseTextToolCall(text);
                    if (synthetic != null) {
                        // 顺序计数：切换工具时重置计数，连续同一工具达到阈值判定为发散循环
                        if (lastToolName != null && lastToolName.equals(synthetic.name())) {
                            repeatCount++;
                        } else {
                            lastToolName = synthetic.name();
                            repeatCount = 1;
                        }
                        if (repeatCount >= REPEAT_TOOL_CALL_LIMIT) {
                            log.warn("检测到连续重复工具调用: {}，强制收敛终止工具循环", synthetic.name());
                            injectConvergenceMessage(messages);
                            return;
                        }
                        log.warn("检测到文本形式工具调用，已转换执行: name={}, args={}", synthetic.name(), synthetic.arguments());
                        // 以原生 tool_calls 结构回填消息，保证协议合法（tool 消息必须跟在带 tool_calls 的 assistant 消息之后）
                        messages.add(AssistantMessage.builder()
                                .content("")
                                .toolCalls(List.of(synthetic))
                                .build());
                        sink.next("{\"type\":\"tool\",\"name\":"
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
                // 顺序计数：切换工具时重置计数，连续同一工具达到阈值判定为发散循环，强制收敛避免无限空转
                if (lastToolName != null && lastToolName.equals(toolCall.name())) {
                    repeatCount++;
                } else {
                    lastToolName = toolCall.name();
                    repeatCount = 1;
                }
                if (repeatCount >= REPEAT_TOOL_CALL_LIMIT) {
                    log.warn("检测到连续重复工具调用: {}，强制收敛终止工具循环", toolCall.name());
                    injectConvergenceMessage(messages);
                    return;
                }
                log.info("  执行工具: id={}, name={}, args={}", toolCall.id(), toolCall.name(),
                        toolCall.arguments().length() > MAX_LOG_ARGS_LEN
                                ? toolCall.arguments().substring(0, MAX_LOG_ARGS_LEN) + "..."
                                : toolCall.arguments());
                // 工具事件实时发射（前端渲染工具调用徽章/步骤）
                sink.next("{\"type\":\"tool\",\"name\":"
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
        // 达到最大迭代次数仍未见最终答复：注入收敛指令，确保流式阶段模型明确收尾作答（不再输出文本工具调用）
        log.warn("工具调用达到最大迭代次数: {}，注入收敛指令强制收尾", MAX_TOOL_ITERATIONS);
        injectConvergenceMessage(messages);
    }

    /**
     * 注入收敛指令：告知模型工具调用阶段已结束，基于已有信息直接作答，禁止再发起任何工具调用
     *
     * @param messages 对话消息列表
     */
    private void injectConvergenceMessage(List<Message> messages) {
        // 防止重复注入
        if (!messages.isEmpty() && messages.get(messages.size() - 1) instanceof SystemMessage) {
            return;
        }
        messages.add(new SystemMessage("工具调用阶段已结束。请基于已有信息直接回答用户问题，"
                + "不要再发起任何新的工具调用，也不要以文本形式输出工具调用片段（如 <tool_call> 等）。"));
    }

    /**
     * 剥离流式阶段被拆分到多个 token 的文本工具调用块
     * 模型有时会在流式输出中以 XML 文本形式再次表达工具意图（如 <tool_call><function=...>...），
     * 该块会被 tokenizer 拆分成多条增量逐片到达，单 token 正则无法整块命中，需要按状态机累积后整体剥离。
     *
     * @param chunk  当前 token 的文本增量
     * @param inBlock 是否正处于 <tool_call> 块内（跨 token 状态）
     * @param buf    块内累积缓冲（跨 token）
     * @return 剥离工具调用块后的残留文本（正常正文原样保留）
     */
    private static String stripToolCallBlock(String chunk, boolean[] inBlock, StringBuilder buf) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < chunk.length()) {
            if (inBlock[0]) {
                // 正在工具调用块内：查找结束标记
                int end = chunk.indexOf("</tool_call>", i);
                if (end < 0) {
                    // 本 token 未见结束标记，整体累积后等待下一个 token
                    buf.append(chunk.substring(i));
                    return out.toString();
                }
                // 缓冲累积完整块后清除，块内容全部丢弃
                buf.append(chunk.substring(i, end));
                buf.setLength(0);
                inBlock[0] = false;
                i = end + "</tool_call>".length();
            } else {
                // 非块内：查找开始标记
                int start = chunk.indexOf("<tool_call", i);
                if (start < 0) {
                    // 无开始标记，剩余均为正常正文
                    out.append(chunk.substring(i));
                    i = chunk.length();
                } else {
                    // 开始标记之前的正文保留，进入块内状态（含未闭合的 <tool_call 前缀，由下一轮累积）
                    out.append(chunk.substring(i, start));
                    inBlock[0] = true;
                    buf.setLength(0);
                    i = start;
                }
            }
        }
        return out.toString();
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
     * 将思考/推理增量包装为结构化JSON字符串
     * 前端据 type=thinking 字段区分并以折叠/流式方式展示思考过程
     *
     * @param thinkingChunk 思考内容增量（可能为完整块或delta）
     * @return 结构化JSON字符串，格式：{"type":"thinking","content":"<思考增量>"}
     */
    private String wrapThinking(String thinkingChunk) {
        try {
            return "{\"type\":\"thinking\",\"content\":" + objectMapper.writeValueAsString(thinkingChunk) + "}";
        } catch (JacksonException e) {
            log.warn("JSON编码thinking失败，降级为空内容", e);
            return "{\"type\":\"thinking\",\"content\":\"\"}";
        }
    }

    /**
     * 将阶段进度包装为结构化JSON字符串
     * 前端据 type=stage 字段展示阶段进度（如“正在理解问题...”）
     * 通过 objectMapper 编码 content，确保中文与特殊字符安全传输
     *
     * @param content 阶段文案（如“正在理解问题...”）
     * @return 结构化JSON字符串，格式：{"type":"stage","content":"<阶段文案>"}
     */
    private String wrapStage(String content) {
        try {
            return "{\"type\":\"stage\",\"content\":" + objectMapper.writeValueAsString(content) + "}";
        } catch (JacksonException e) {
            log.warn("JSON编码stage失败，降级为空内容", e);
            return "{\"type\":\"stage\",\"content\":\"\"}";
        }
    }

    /**
     * 从 ChatResponse 中提取思考/推理内容
     * 兼容多种来源与键名：
     * 1) AssistantMessage.getMetadata() 中的 "reasoning_content" / "reasoningContent" / "reasoning" / "thinking"
     * 2) Generation.getMetadata() 中的同名键（部分模型将推理写入 generation metadata）
     * 3) ChatResponse.getMetadata() 中的同名键（兜底，兼容不同 OpenAI 兼容网关实现）
     * 流式场景下每次调用返回增量 delta，调用方需直接透传 SSE；非流式场景返回完整块
     *
     * @param response ChatModel 返回的响应对象
     * @return 思考内容字符串，不存在时返回 null
     */
    @Nullable
    private String extractThinking(ChatResponse response) {
        if (response == null) {
            return null;
        }
        // 1) 优先从 AssistantMessage.metadata 提取（最常见：OpenAI reasoning_content 映射于此）
        if (response.getResult() != null && response.getResult().getOutput() != null) {
            AssistantMessage output = response.getResult().getOutput();
            Map<String, Object> meta = output.getMetadata();
            if (meta != null && !meta.isEmpty()) {
                String v = getThinkingFromMap(meta);
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
            // 2) 从 Generation metadata 提取（部分版本/网关将 reasoning 写入 generation metadata）
            var genMeta = response.getResult().getMetadata();
            if (genMeta != null && !genMeta.isEmpty()) {
                for (String key : List.of("reasoning_content", "reasoningContent", "reasoning", "thinking")) {
                    if (genMeta.containsKey(key)) {
                        Object obj = genMeta.get(key);
                        if (obj instanceof String s && !s.isBlank()) {
                            return s;
                        }
                    }
                }
            }
        }
        // 3) 兜底：从 ChatResponse 级别 metadata 提取（兼容不同网关/代理实现）
        var respMeta = response.getMetadata();
        if (respMeta != null && !respMeta.isEmpty()) {
            for (String key : List.of("reasoning_content", "reasoningContent", "reasoning", "thinking")) {
                if (respMeta.containsKey(key)) {
                    Object obj = respMeta.get(key);
                    if (obj instanceof String s && !s.isBlank()) {
                        return s;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 从 Map 中按优先级提取思考内容
     *
     * @param meta 元数据 Map
     * @return 思考内容字符串，不存在时返回 null
     */
    @Nullable
    private String getThinkingFromMap(Map<String, Object> meta) {
        // 优先级：reasoning_content > reasoningContent > reasoning > thinking
        for (String key : List.of("reasoning_content", "reasoningContent", "reasoning", "thinking")) {
            Object v = meta.get(key);
            if (v instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    /**
     * 将思考内容按固定大小切分为多段
     * 用于大块 thinking 的模拟流式：切分后由 emitThinkingToSink 按 THINKING_DELAY 间隔实时发射
     *
     * @param thinking  完整思考内容
     * @param chunkSize 切分大小
     * @return 切分后的片段列表
     * @author H
     */
    private List<String> splitThinking(String thinking, int chunkSize) {
        List<String> pieces = new ArrayList<>();
        for (int start = 0; start < thinking.length(); start += chunkSize) {
            int end = Math.min(start + chunkSize, thinking.length());
            pieces.add(thinking.substring(start, end));
        }
        return pieces;
    }

    /**
     * 将思考增量实时写入 SSE sink
     * 大块增量按 THINKING_CHUNK_SIZE 切分、分片间休眠 THINKING_DELAY，模拟逐字输出且保持与后续事件的先后顺序
     *
     * @param sink          SSE 事件sink
     * @param thinkingChunk 本次思考增量
     * @author H
     */
    private void emitThinkingToSink(FluxSink<String> sink, String thinkingChunk) {
        if (thinkingChunk == null || thinkingChunk.isEmpty()) {
            return;
        }
        if (thinkingChunk.length() <= 50) {
            // 小增量直接发射，体现流式 delta 透传
            sink.next(wrapThinking(thinkingChunk));
            return;
        }
        for (String piece : splitThinking(thinkingChunk, THINKING_CHUNK_SIZE)) {
            // 客户端断连后停止发射
            if (sink.isCancelled()) {
                return;
            }
            sink.next(wrapThinking(piece));
            sleepQuietly(THINKING_DELAY.toMillis());
        }
    }

    /**
     * 休眠指定毫秒（思考分片发射间隔），被中断时恢复中断标记并立即返回
     *
     * @param millis 休眠毫秒数
     * @author H
     */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
     * 保存聊天消息（无思考内容）
     *
     * @param conversationId 会话ID
     * @param role           消息角色
     * @param content        消息内容
     * @param toolName       工具名称（非工具消息为null）
     * @author H
     */
    private void saveMessage(Long conversationId, Integer role, String content, String toolName) {
        saveMessage(conversationId, role, content, toolName, null);
    }

    /**
     * 保存聊天消息（含思考内容持久化）
     *
     * @param conversationId 会话ID
     * @param role           消息角色
     * @param content        消息内容
     * @param toolName       工具名称（非工具消息为null）
     * @param thinkingContent 思考过程内容（仅助手消息可能携带，可为null）
     * @author H
     */
    private void saveMessage(Long conversationId, Integer role, String content, String toolName, String thinkingContent) {
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
        // 思考过程内容持久化（历史回显用，空串按 null 处理）
        if (thinkingContent != null && !thinkingContent.isBlank()) {
            msg.setThinkingContent(thinkingContent);
        }
        msg.setStatus(MessageStatusEnum.COMPLETED.getCode());
        chatMessageMapper.insert(msg);
    }
}
