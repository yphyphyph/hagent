package com.hagent.ai.service.impl;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.hagent.ai.core.AgentToolLoopService;
import com.hagent.ai.core.AiModelProvider;
import com.hagent.ai.core.ChatPromptHolder;
import com.hagent.ai.core.ConversationTitleService;
import com.hagent.ai.core.ModelRouter;
import com.hagent.ai.core.RagService;
import com.hagent.ai.core.ThinkingStageSupport;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 聊天服务实现（编排层）
 * 负责会话生命周期、鉴权短路、历史组装、流式输出订阅与消息持久化；
 * 工具循环/收敛委托 {@link AgentToolLoopService}，思考阶段事件委托 {@link ThinkingStageSupport}，
 * 提示词委托 {@link ChatPromptHolder}，标题生成委托 {@link ConversationTitleService}
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
    /** RAG服务，用于获取命中来源元数据（file_id、chunk_id、score、file_name、snippet） */
    private final RagService ragService;
    /** 工具调用循环服务 */
    private final AgentToolLoopService agentToolLoopService;
    /** 思考阶段支持组件 */
    private final ThinkingStageSupport stageSupport;
    /** 聊天提示词持有者 */
    private final ChatPromptHolder promptHolder;
    /** 会话标题异步生成服务 */
    private final ConversationTitleService titleService;
    /** 多级降级路由（无 Ollama，仅备用 API 模型） */
    private final ModelRouter modelRouter;

    /** 统一工具回调（管理端与门户端共用同一注册集：知识库检索 + Text to SQL + 图片识别） */
    private final ToolCallback[] unifiedToolCallbacks;

    /** 历史消息最大保留条数（滑动窗口，防止上下文超限） */
    private static final int MAX_HISTORY_MESSAGES = 20;

    /** 标题最大字符数 */
    private static final int TITLE_MAX_LENGTH = 20;

    /** 新会话默认标题 */
    private static final String DEFAULT_TITLE = "新会话";

    /** 空内容兜底文案（落库与前端推送共用；历史组装时跳过该类消息，避免污染模型上下文） */
    private static final String FALLBACK_TEXT = "已根据已有信息整理回答，详见思考过程";

    /** 消息类型文本 */
    private static final String MESSAGE_TYPE_TEXT = "TEXT";

    /** 访客拦截提示消息（演示模式：未登录用户不消耗真实模型token） */
    private static final String GUEST_BLOCKED_MESSAGE = "演示模式：登录后可体验真实 AI 能力";

    /** 确认词（精确匹配用，中文直接 equals，英文忽略大小写） */
    private static final String[] CONFIRM_WORDS = {"是", "确认", "可以", "OK", "ok", "执行", "确定", "好", "yes", "Yes", "行", "嗯"};

    /** 取消词 */
    private static final String[] CANCEL_WORDS = {"取消", "不", "不用", "算了", "no", "No", "放弃", "停止"};

    /**
     * 构造方法：注入聊天依赖并统一注册工具回调
     * 管理端与门户端共用同一工具集（知识库检索 + Text to SQL + 图片识别），仅系统提示词不同
     *
     * @param aiModelProvider     AI模型提供者
     * @param conversationService 会话服务
     * @param chatMessageMapper   聊天消息Mapper
     * @param chatClient          ChatClient（可空，当前未使用）
     * @param objectMapper        JSON序列化工具
     * @param knowledgeTool       知识库检索工具
     * @param textToSqlTool       Text to SQL 工具
     * @param analyzeImageTool    图片识别工具
     * @param pendingActionService 待确认操作服务
     * @param ragService          RAG服务
     * @param agentToolLoopService 工具调用循环服务
     * @param stageSupport        思考阶段支持组件
     * @param promptHolder        聊天提示词持有者
     * @param titleService        会话标题异步生成服务
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
                           PendingActionService pendingActionService,
                           RagService ragService,
                           AgentToolLoopService agentToolLoopService,
                           ThinkingStageSupport stageSupport,
                           ChatPromptHolder promptHolder,
                           ConversationTitleService titleService,
                           ModelRouter modelRouter) {
        this.aiModelProvider = aiModelProvider;
        this.conversationService = conversationService;
        this.chatMessageMapper = chatMessageMapper;
        this.objectMapper = objectMapper;
        this.pendingActionService = pendingActionService;
        this.ragService = ragService;
        this.agentToolLoopService = agentToolLoopService;
        this.stageSupport = stageSupport;
        this.promptHolder = promptHolder;
        this.titleService = titleService;
        this.modelRouter = modelRouter;
        // 统一工具集：知识库检索 + Text to SQL + 图片识别（管理端与门户端共用）
        this.unifiedToolCallbacks = ToolCallbacks.from(knowledgeTool, textToSqlTool, analyzeImageTool);
    }

    /**
     * 解析模型名称，用于降级日志
     *
     * @param chatModel 对话模型
     * @return 模型名称
     * @author H
     */
    private String resolveModelName(ChatModel chatModel) {
        if (chatModel instanceof OpenAiChatModel openAiChatModel) {
            try {
                String name = openAiChatModel.getOptions().getModel();
                return name != null ? name : chatModel.getClass().getSimpleName();
            } catch (Exception e) {
                return chatModel.getClass().getSimpleName();
            }
        }
        return chatModel.getClass().getSimpleName();
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
        return doChat(userId, request, promptHolder.getAdminPrompt());
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
        return doChat(userId, request, promptHolder.getPortalPrompt());
    }

    /**
     * 核心聊天逻辑 — 两阶段策略
     * <p>
     * 阶段1：同步执行工具循环（chatModel.call）
     * 阶段2：真正流式输出最终文本（chatModel.stream，不带工具回调，避免重复调用）
     *
     * @param userId              用户ID
     * @param request             聊天请求
     * @param defaultSystemPrompt 默认系统提示词
     * @return SSE流式响应
     */
    private Flux<String> doChat(Long userId, ChatRequest request, String defaultSystemPrompt) {
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

        // 3. 首条消息自动生成标题：先以截断占位（列表立即可见），回答完成后由 ConversationTitleService 异步 LLM 生成
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
                        conversationService.updateStats(finalConversationId, estimateTokens(text));
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
                        conversationService.updateStats(finalConversationId, estimateTokens(text));
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
            // 说明：工具调用过程不落库（仅落最终回复），因此历史中不会出现 TOOL 角色；
            // 若未来开始落库 TOOL 消息，必须同时落库带 tool_calls 的 assistant 消息，否则重建的
            // 消息序列会被 OpenAI 接口以 "tool must follow tool_calls" 拒绝
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
                // 跳过空回复兜底文案：它不是真实回答，进入上下文会误导模型认为上一问已被作答
                if (roleEnum == MessageRoleEnum.ASSISTANT && FALLBACK_TEXT.equals(msg.getContent())) {
                    continue;
                }
                switch (roleEnum) {
                    case USER -> messages.add(new UserMessage(msg.getContent()));
                    case ASSISTANT -> messages.add(new AssistantMessage(msg.getContent()));
                    case SYSTEM -> messages.add(new SystemMessage(msg.getContent()));
                    default -> {
                        // TOOL 角色当前不落库（见方法注释），忽略历史中的意外角色
                    }
                }
            }

            // 7. 构建带工具回调的Options（仅用于阶段1工具调用）— 多级降级：基于有序模型列表（主 order0 + 备用 1..N）
            List<ChatModel> orderedChatModels = modelRouter.getOrderedModels();
            if (orderedChatModels == null || orderedChatModels.isEmpty()) {
                log.error("有序对话模型列表为空");
                return Flux.just(wrapError("AI服务配置异常，请稍后重试"));
            }
            ChatModel chatModel = orderedChatModels.get(0);
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
                    .toolCallbacks(unifiedToolCallbacks)
                    .build();

            // 8. 累积思考内容，用于最终持久化（工具阶段 + 流式阶段共享）
            StringBuilder thinkingAccum = new StringBuilder();
            // 累积RAG命中来源（用于SSE推送与持久化）
            final List<Map<String, Object>> sourcesAccum = new ArrayList<>();

            // 9. 阶段2：构建不带工具回调的流式Options，避免流式阶段触发工具调用导致空文本（降级时按模型重建）
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
                    // 阶段进度：8 阶段序列前三阶段（前端据此将折叠块标题动态置为对应阶段）
                    sink.next(stageSupport.wrapStage(ThinkingStageSupport.THINK_STAGE_UNDERSTAND));
                    sink.next(stageSupport.wrapStage(ThinkingStageSupport.THINK_STAGE_ANALYZE));
                    sink.next(stageSupport.wrapStage(ThinkingStageSupport.THINK_STAGE_STRATEGY));

                    // 阶段1：同步工具循环（阻塞执行，但事件实时发射到前端，命中来源一并推送）— 多级降级
                    // AgentToolLoopService 内部已按 order 0..N 逐级重试（每级仅一次，总重试 N 次），此处外层仅透传；
                    // 若需整阶段重试，可按 orderedChatModels 再次兜底（全部失败抛最后异常）
                    agentToolLoopService.callWithTools(messages, toolOptions, chatModel,
                            unifiedToolCallbacks, sink, thinkingAccum, sourcesAccum);

                    // 阶段进度：构建输出结构（工具调用结束、流式开始前，对应第 7 阶段）
                    sink.next(stageSupport.wrapStage(ThinkingStageSupport.THINK_STAGE_STRUCTURE));
                    // 最终润色阶段（流式正文输出前的最后准备）
                    sink.next(stageSupport.wrapStage(ThinkingStageSupport.THINK_STAGE_POLISH));
                } catch (Exception e) {
                    log.error("聊天失败: conversationId={}", finalConversationId, e);
                    sink.next(wrapError("AI服务暂时不可用，请稍后重试"));
                    sink.next("[FINAL]");
                    sink.complete();
                    return;
                } finally {
                    // 工具循环在本线程执行，清理 ThreadLocal 防止线程复用时身份串扰
                    UserContextHolder.clear();
                    // 清理 RAG 命中 ThreadLocal，避免线程复用污染
                    try {
                        ragService.clearLastHits();
                    } catch (Exception e) {
                        log.warn("清理RAG命中缓存失败", e);
                    }
                }

                log.info("开始流式输出: conversationId={}", finalConversationId);

                StringBuilder fullText = new StringBuilder();
                // 流式阶段 thinking 去重状态：网关可能在每个增量响应中重放已见的完整 thinking，本流内用累积去重，仅发送新增 delta
                final StringBuilder streamedThinkingSeen = new StringBuilder();
                // 文本工具调用块剥离状态（流式阶段）：模型可能在工具循环结束后仍以 <tool_call> 文本方式表达工具意图，
                // 该块会被拆分到多个 token 逐片到达，单 token 正则无法命中，需按状态机累积完整块后整体剥离
                final boolean[] inToolCallBlock = {false};
                final StringBuilder toolCallBlockBuf = new StringBuilder();
                // 阶段2：真正的增量流，逐个响应提取 thinking 与 token 并交织发射
                // 每个token统一包装为结构化JSON：{"type":"token","content":"<token内容>"}
                // 通过JSON字符串编码，换行符等特殊字符被转义为单行传输，避免SSE帧被破坏
                // 多级降级流式：按 order 0..N 逐级重试，每级仅一次，总重试 N 次，不循环
                Flux<ChatResponse> rawStream = streamWithFallback(orderedChatModels, messages, streamOptions);
                Disposable rawDisposable = rawStream.subscribe(
                        response -> {
                            // 1) 优先发射 thinking 增量（若模型提供了 reasoning_content / thinking）
                            // 网关可能在每个增量中重放已见的完整 thinking，用 dedupThinkingDelta 仅发送新增后缀，避免 thinking-token 交织重复
                            String rawThinking = stageSupport.extractThinking(response);
                            String thinkingChunk = ThinkingStageSupport.dedupThinkingDelta(
                                    streamedThinkingSeen.length() > 0 ? streamedThinkingSeen.toString() : null,
                                    rawThinking);
                            if (thinkingChunk != null && !thinkingChunk.isEmpty()) {
                                // 累积思考内容用于入库持久化（仅新增 delta，避免落库翻倍）
                                thinkingAccum.append(thinkingChunk);
                                streamedThinkingSeen.append(thinkingChunk);
                                stageSupport.emitThinkingToSink(sink, thinkingChunk);
                            }
                            // 2) 再发射 token 增量
                            String text = null;
                            if (response.getResult() != null && response.getResult().getOutput() != null) {
                                text = response.getResult().getOutput().getText();
                            }
                            if (text != null && !text.isEmpty()) {
                                // 剥离可能被拆分到多个 token 的文本工具调用块（如 <tool_call>...</tool_call>），防止 XML 碎片泄漏为正文
                                String filtered = AgentToolLoopService.stripToolCallBlock(text, inToolCallBlock, toolCallBlockBuf);
                                // 兜底：过滤单 token 内完整的幻觉工具调用标签残留
                                filtered = AgentToolLoopService.filterHallucinatedTags(filtered);
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
                            // 异常时保存已生成的部分文本+思考内容+来源，保证历史上下文完整（访客模式跳过）
                            String partialText = fullText.toString();
                            String partialThinking = thinkingAccum.length() > 0 ? thinkingAccum.toString() : null;
                            String partialSources = buildSourcesJson(sourcesAccum);
                            // 空白内容回退：无论 thinking 是否存在，只要 content 为空白就使用兜底文案，避免持久化空串导致前端显示"(无回复内容)" @author H
                            if (partialText == null || partialText.isBlank()) {
                                partialText = FALLBACK_TEXT;
                            }
                            if (finalConversationId != null && (!partialText.isBlank() || partialThinking != null)) {
                                saveMessage(finalConversationId, MessageRoleEnum.ASSISTANT.getCode(),
                                        partialText, null, partialThinking, partialSources);
                                conversationService.updateStats(finalConversationId, estimateTokens(partialText));
                            }
                            sink.next(wrapError("AI服务暂时不可用，请稍后重试"));
                            sink.next("[FINAL]");
                            sink.complete();
                        },
                        () -> {
                            // 流完成，保存完整回复+思考内容+来源到DB（访客模式跳过）
                            String finalText = fullText.toString();
                            String finalThinking = thinkingAccum.length() > 0 ? thinkingAccum.toString() : null;
                            String finalSources = buildSourcesJson(sourcesAccum);
                            // 空白内容回退：无论 thinking 是否存在，只要 content 为空白就使用兜底文案，避免持久化空串导致前端显示"(无回复内容)" @author H
                            boolean isEmptyFallback = finalText == null || finalText.isBlank();
                            if (isEmptyFallback) {
                                finalText = FALLBACK_TEXT;
                            }
                            if (finalConversationId != null && (!finalText.isBlank() || finalThinking != null)) {
                                saveMessage(finalConversationId, MessageRoleEnum.ASSISTANT.getCode(),
                                        finalText, null, finalThinking, finalSources);
                                conversationService.updateStats(finalConversationId, estimateTokens(finalText));
                                log.info("流式输出完成并保存: conversationId={}, length={}, thinkingLength={}, sources={}",
                                        finalConversationId, finalText.length(),
                                        finalThinking != null ? finalThinking.length() : 0,
                                        finalSources != null ? sourcesAccum.size() : 0);
                            }
                            // 空内容兜底增量推送：当过滤后 fullText 为空时，前端尚未收到任何 token，需将兜底文案作为 token 事件增量推送，避免前端显示"(无回复内容)"直至刷库 @author H
                            if (isEmptyFallback && !sink.isCancelled()) {
                                sink.next(wrapToken(finalText));
                            }
                            // 首轮回答完成后异步生成 LLM 标题（仅当标题仍为占位时触发）
                            titleService.generateIfNeeded(finalConversationId, conversation.getTitle(), request.getMessage());
                            // 思考结束阶段推送（前端据此将标题置为"思考结束"，800ms 后自动收起）
                            if (!sink.isCancelled()) {
                                sink.next(stageSupport.wrapStage(ThinkingStageSupport.THINK_STAGE_DONE));
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
     * 多级降级流式（无 Ollama，仅备用 API 模型）
     * 按 order 0..N 逐级重试，每级仅一次，总重试 N 次，日志 WARN fallback to level
     *
     * @param ordered 有序模型列表
     * @param messages 消息历史
     * @param baseStreamOptions 基准流式 Options（用于非 OpenAi 兜底）
     * @return 流式响应 Flux
     * @author H
     */
    private Flux<ChatResponse> streamWithFallback(List<ChatModel> ordered, List<Message> messages, OpenAiChatOptions baseStreamOptions) {
        return streamWithFallbackInternal(ordered, messages, baseStreamOptions, 0);
    }

    /**
     * 流式降级递归
     *
     * @param ordered 有序模型
     * @param messages 消息
     * @param baseStreamOptions 基准 Options
     * @param index 当前下标
     * @return Flux
     * @author H
     */
    private Flux<ChatResponse> streamWithFallbackInternal(List<ChatModel> ordered, List<Message> messages, OpenAiChatOptions baseStreamOptions, int index) {
        if (index >= ordered.size()) {
            return Flux.error(new RuntimeException("all chat models failed"));
        }
        ChatModel current = ordered.get(index);
        OpenAiChatOptions curOptions;
        if (current instanceof OpenAiChatModel openAiChatModel) {
            OpenAiChatOptions base = openAiChatModel.getOptions();
            curOptions = OpenAiChatOptions.builder()
                    .apiKey(base.getApiKey())
                    .baseUrl(base.getBaseUrl())
                    .model(base.getModel())
                    .build();
        } else {
            curOptions = baseStreamOptions;
        }
        Prompt prompt = new Prompt(messages, curOptions);
        return current.stream(prompt)
                .onErrorResume(e -> {
                    if (index >= ordered.size() - 1) {
                        return Flux.error(e);
                    }
                    ChatModel next = ordered.get(index + 1);
                    log.warn("fallback to level {}/{} {}", index, index + 1, resolveModelName(next), e);
                    return streamWithFallbackInternal(ordered, messages, baseStreamOptions, index + 1);
                });
    }

    /**
     * 估算文本 token 数（中英混合）
     * 中文字符约 0.6 token/字，其他字符约 4 字符/token；比单一 len/4 对中文场景更接近真实
     *
     * @param text 文本
     * @return 估算 token 数
     * @author H
     */
    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN) {
                cjk++;
            } else {
                other++;
            }
        }
        return Math.max(1, Math.round(cjk * 0.6f + other / 4.0f));
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
     * 构建 sources JSON 字符串（用于持久化）
     *
     * @param sourcesAccum 累积的来源列表
     * @return JSON字符串，为空时返回null
     * @author H
     */
    private String buildSourcesJson(List<Map<String, Object>> sourcesAccum) {
        if (sourcesAccum == null || sourcesAccum.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(sourcesAccum);
        } catch (JacksonException e) {
            log.warn("序列化sources失败，跳过持久化", e);
            return null;
        }
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
     * @param conversationId  会话ID
     * @param role            消息角色
     * @param content         消息内容
     * @param toolName        工具名称（非工具消息为null）
     * @param thinkingContent 思考过程内容（仅助手消息可能携带，可为null）
     * @author H
     */
    private void saveMessage(Long conversationId, Integer role, String content, String toolName, String thinkingContent) {
        saveMessage(conversationId, role, content, toolName, thinkingContent, null);
    }

    /**
     * 保存聊天消息（含思考内容与来源持久化）
     *
     * @param conversationId  会话ID
     * @param role            消息角色
     * @param content         消息内容
     * @param toolName        工具名称（非工具消息为null）
     * @param thinkingContent 思考过程内容（仅助手消息可能携带，可为null）
     * @param sourcesJson     来源引用JSON数组（RAG命中，可为null）
     * @author H
     */
    private void saveMessage(Long conversationId, Integer role, String content, String toolName, String thinkingContent, String sourcesJson) {
        // 访客临时会话无持久化目标，直接跳过
        if (conversationId == null) {
            return;
        }
        ChatMessage msg = new ChatMessage();
        msg.setConversationId(conversationId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setMessageType(MESSAGE_TYPE_TEXT);
        msg.setToolName(toolName);
        // 思考过程内容持久化（历史回显用，空串按 null 处理）
        if (thinkingContent != null && !thinkingContent.isBlank()) {
            msg.setThinkingContent(thinkingContent);
        }
        // 来源引用持久化（RAG命中，空串按 null 处理）
        if (sourcesJson != null && !sourcesJson.isBlank()) {
            msg.setSources(sourcesJson);
        }
        msg.setStatus(MessageStatusEnum.COMPLETED.getCode());
        chatMessageMapper.insert(msg);
    }
}
