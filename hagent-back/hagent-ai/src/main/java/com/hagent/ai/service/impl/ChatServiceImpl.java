package com.hagent.ai.service.impl;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.hagent.ai.config.AiConfig;
import com.hagent.ai.core.AgentToolLoopService;
import com.hagent.ai.core.AiModelProvider;
import com.hagent.ai.core.ChatPromptHolder;
import com.hagent.ai.core.ContextTokenEstimator;
import com.hagent.ai.core.ConversationTitleService;
import com.hagent.ai.core.ModelRouter;
import com.hagent.ai.core.OpenCodeGatewayHeaders;
import com.hagent.ai.core.RagService;
import com.hagent.ai.core.ThinkingStageSupport;
import com.hagent.ai.core.UserContextHolder;
import com.hagent.ai.mapper.ChatMessageMapper;
import com.hagent.ai.mcp.AnalyzeImageTool;
import com.hagent.ai.memory.ConversationMemoryService;
import com.hagent.ai.model.dto.AttachmentDTO;
import com.hagent.ai.model.dto.ChatRequest;
import com.hagent.ai.model.entity.ChatConversation;
import com.hagent.ai.model.entity.ChatMessage;
import com.hagent.ai.model.entity.ChatPendingAction;
import com.hagent.ai.model.enums.MessageRoleEnum;
import com.hagent.ai.model.enums.MessageStatusEnum;
import com.hagent.ai.model.enums.PendingActionStatusEnum;
import com.hagent.ai.model.vo.ContextUsageSnapshot;
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
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.URLConnection;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /** AI 配置（上下文窗口分母来源，可在设置页改） */
    private final AiConfig aiConfig;
    /** 会话摘要压缩服务（内存 T4 链路接入：历史组装读摘要、落库后异步触发压缩） */
    private final ConversationMemoryService conversationMemoryService;

    /** Redis 模板（RAG 修正值持久化，重启后自适应占位恢复；异常时降级内存态） */
    private final StringRedisTemplate stringRedisTemplate;

    /** 统一工具回调（管理端与门户端共用同一注册集：知识库检索 + Text to SQL + 图片识别） */
    private final ToolCallback[] unifiedToolCallbacks;

    /** 上轮 RAG 修正后的 ragTokens（按会话记，供下轮估算自适应占位；内存态单机生效，重启后经 Redis 恢复） */
    private final ConcurrentHashMap<Long, Integer> lastRagTokensByConversation = new ConcurrentHashMap<>();

    /** 上轮 RAG 修正值 Redis key 前缀：key = 前缀 + 会话ID，重启后首轮自适应占位不回落 */
    private static final String CTX_RAG_REDIS_KEY_PREFIX = "hagent:ctx:rag:";

    /** RAG 修正值 Redis 过期时间（7 天，自动清理死会话） */
    private static final Duration CTX_RAG_REDIS_TTL = Duration.ofDays(7);

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

    /** 附件图片单张 token 估算口径：每张固定 1000 token，并入 system 段（实现最简，不新增 SSE breakdown 字段，事件协议不变） */
    private static final int IMAGE_TOKENS_PER_ATTACHMENT = 1000;

    /** data URL 的 base64 数据段标记（附件图片解析用） */
    private static final String BASE64_MARKER = ";base64,";

    /**
     * 当轮用户消息中 [文档：name|fileId] 新格式留痕标记识别正则（group(1)=文件名, group(2)=知识库文件ID）；
     * 新格式由 buildDocumentMarkers 生成（document 附件带 fileId 时），旧格式 [文档：name] 无 fileId 不匹配
     */
    private static final Pattern DOC_MARKER_WITH_FILE_ID_PATTERN =
            Pattern.compile("\\[文档：([^\\]\\n|]+)\\|(\\d+)\\]");

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
     * @param modelRouter         多级降级路由
     * @param aiConfig            AI 配置（上下文窗口分母来源）
     * @param conversationMemoryService 会话摘要压缩服务
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
                           ModelRouter modelRouter,
                           AiConfig aiConfig,
                           ConversationMemoryService conversationMemoryService,
                           StringRedisTemplate stringRedisTemplate) {
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
        this.aiConfig = aiConfig;
        this.conversationMemoryService = conversationMemoryService;
        this.stringRedisTemplate = stringRedisTemplate;
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
        // 0. 重新生成/编辑重发前置校验：两者互斥且必须基于已保存的会话（访客/无会话拒绝；
        // HTTP 入口已由 @ValidRegenerateOrEdit 类级校验返回 400，此处兜底服务层直调场景） @author H
        boolean regenerateOp = Boolean.TRUE.equals(request.getRegenerate());
        boolean editOp = request.getEditMessageId() != null;
        if ((regenerateOp || editOp) && (userId == null || request.getConversationId() == null)) {
            log.warn("重新生成/编辑重发被拒绝（需要已保存的会话）: userId={}, conversationId={}, regenerate={}, editMessageId={}",
                    userId, request.getConversationId(), request.getRegenerate(), request.getEditMessageId());
            return Flux.just(wrapError("该操作需要已保存的会话"), "[DONE]");
        }

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

        // 1.5 重新生成/编辑重发语义处理（在 USER 落库之前分支，后续 RAG/工具/流式/落库走既有流程不动）；
        // 软删走 MyBatis-Plus 逻辑删除（chat_message.deleted @TableLogic，不手写 UPDATE），
        // 滑窗与上下文估算的历史查询自动过滤 deleted=0，软删后的状态自然生效，无需额外处理 @author H
        boolean skipUserPersist = false;
        if (regenerateOp) {
            // 重新生成：忽略请求 message 文本，重用该会话最后一条未删除 USER 消息作为本轮输入
            ChatMessage lastUser = chatMessageMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
                            .eq(ChatMessage::getConversationId, conversationId)
                            .eq(ChatMessage::getRole, MessageRoleEnum.USER.getCode())
                            .orderByDesc(ChatMessage::getId)
                            .last("LIMIT 1"));
            if (lastUser == null) {
                log.warn("重新生成被拒绝（会话中无可复用的用户提问）: userId={}, conversationId={}",
                        userId, conversationId);
                return Flux.just(wrapError("会话中没有可重新生成的提问"), "[DONE]");
            }
            // 软删该 USER 之后最后一条 ASSISTANT 回复（deleteById 走 @TableLogic 逻辑删除）
            ChatMessage lastAssistant = chatMessageMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
                            .eq(ChatMessage::getConversationId, conversationId)
                            .eq(ChatMessage::getRole, MessageRoleEnum.ASSISTANT.getCode())
                            .gt(ChatMessage::getId, lastUser.getId())
                            .orderByDesc(ChatMessage::getId)
                            .last("LIMIT 1"));
            if (lastAssistant != null) {
                chatMessageMapper.deleteById(lastAssistant.getId());
            }
            log.info("回答重新生成: conversationId={}, 复用用户消息ID={}, 软删助手消息ID={}",
                    conversationId, lastUser.getId(), lastAssistant != null ? lastAssistant.getId() : null);
            // 以该 USER 内容作为本轮输入，不新增 USER 落库
            request.setMessage(lastUser.getContent());
            skipUserPersist = true;
        } else if (editOp) {
            // 编辑重发：校验目标消息存在、属于本会话且为 USER 角色（selectById 已自动滤 deleted=0）
            ChatMessage editMsg = chatMessageMapper.selectById(request.getEditMessageId());
            if (editMsg == null || !conversationId.equals(editMsg.getConversationId())
                    || !MessageRoleEnum.USER.getCode().equals(editMsg.getRole())) {
                log.warn("编辑重发被拒绝（消息不存在/不属于本会话/非用户消息）: userId={}, conversationId={}, editMessageId={}",
                        userId, conversationId, request.getEditMessageId());
                return Flux.just(wrapError("原始消息不存在或不可编辑"), "[DONE]");
            }
            // 软删该消息及其后所有消息（同会话 id >= editMessageId，逻辑删除不手写 UPDATE）
            chatMessageMapper.delete(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
                            .eq(ChatMessage::getConversationId, conversationId)
                            .ge(ChatMessage::getId, request.getEditMessageId()));
            log.info("编辑重发: conversationId={}, editMessageId={}, 该消息及其后消息已软删，以新文本重新提问",
                    conversationId, request.getEditMessageId());
        }

        final Long finalConversationId = conversationId;
        log.info("聊天请求: userId={}, conversationId={}, message={}",
                userId, finalConversationId, request.getMessage());

        // 2. 保存用户消息（访客模式跳过持久化；重新生成分支重用历史 USER，不新增落库）；
        // 带附件时落库内容末尾追加图片占位标记（不加 DB 列，历史可见），
        // 并记录消息 ID 供后续组装时识别当轮新消息（构建含 Media 的多模态 UserMessage）
        Long currentUserMessageId = null;
        if (userId != null && !skipUserPersist) {
            currentUserMessageId = saveMessage(finalConversationId, MessageRoleEnum.USER.getCode(),
                    buildUserMessageContent(request), null);
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
            // 过期 pending 不得劫持正常追问：expireTime 为 null 视为不过期；已过期则标记为已过期并视为无 pending，走正常聊天循环（确认词逻辑不动） @author H
            if (pending != null && pending.getExpireTime() != null
                    && LocalDateTime.now().isAfter(pending.getExpireTime())) {
                log.info("待确认操作已过期，跳过短路消费: pendingId={}, conversationId={}",
                        pending.getId(), finalConversationId);
                try {
                    ChatPendingAction expiredMark = new ChatPendingAction();
                    expiredMark.setId(pending.getId());
                    expiredMark.setStatus(PendingActionStatusEnum.EXPIRED.getCode());
                    pendingActionService.updateById(expiredMark);
                } catch (Exception markEx) {
                    log.warn("标记过期待确认操作失败: pendingId={}", pending.getId(), markEx);
                }
                pending = null;
            }
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
                        triggerCompressAsync(finalConversationId, userId);
                        return Flux.just(wrapText(text), "[DONE]");
                    } catch (BusinessException e) {
                        log.warn("确认操作失败: pendingId={}, msg={}", pending.getId(), e.getMessage());
                        String errText = e.getMessage() != null ? e.getMessage() : "操作执行失败";
                        saveMessage(finalConversationId, MessageRoleEnum.ASSISTANT.getCode(), errText, null);
                        triggerCompressAsync(finalConversationId, userId);
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
                        triggerCompressAsync(finalConversationId, userId);
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
            // 5.1 知识库文件精确引用注入：当轮用户消息含 [文档：name|fileId] 新格式标记时，在系统提示追加指引，
            // 提示模型检索该文件内容时使用 searchKnowledge 的 fileId 参数精确锁定（检索层仅向量通道按 file_id 过滤），
            // 不再依赖文件名语义检索；置于 systemPrompt 拼装处，工具阶段与流式阶段共用同一消息列表 @author H
            systemPrompt += buildReferencedFilePrompt(request);

            // 6. 构建消息历史（摘要 + 滑窗：首条为 running summary 系统消息，不计轮数；
            // 随后为最近 keepTurns 轮 DB 原文；访客模式无历史）
            // 说明：工具调用过程不落库（仅落最终回复），因此历史中不会出现 TOOL 角色；
            // 若未来开始落库 TOOL 消息，必须同时落库带 tool_calls 的 assistant 消息，否则重建的
            // 消息序列会被 OpenAI 接口以 "tool must follow tool_calls" 拒绝
            List<Message> messages = new ArrayList<>();
            // 6.1 首条 system：running summary（覆盖写回，缺失或失败时回退纯滑窗）
            if (finalConversationId != null) {
                try {
                    String rawSummary = conversationMemoryService.loadLatestSummary(finalConversationId);
                    String decoratedSummary = conversationMemoryService.decorateIfNeeded(rawSummary);
                    if (decoratedSummary != null && !decoratedSummary.isBlank()) {
                        messages.add(new SystemMessage(decoratedSummary));
                    }
                } catch (Exception e) {
                    log.warn("读取会话摘要失败，回退纯滑窗: conversationId={}, error={}",
                            finalConversationId, e.getMessage());
                }
            }
            messages.add(new SystemMessage(systemPrompt));

            // 6.2 最近 keepTurns 轮整轮保留：DB 查询最近 keepTurns*2 条
            // （实体 @TableLogic 逻辑删除使查询自动滤 deleted=0，重新生成/编辑重发软删的消息不会进入滑窗与上下文估算） @author H
            int keepTurns = resolveKeepTurns();
            int historyLimit = Math.max(2, keepTurns * 2);
            List<ChatMessage> history = finalConversationId == null
                    ? new ArrayList<>()
                    : chatMessageMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
                            .eq(ChatMessage::getConversationId, finalConversationId)
                            .orderByDesc(ChatMessage::getCreatedAt)
                            .last("LIMIT " + historyLimit));
            // 反转为时间正序
            java.util.Collections.reverse(history);

            // 当轮新消息带图片附件：按落库消息 ID 精确识别，组装含 Media 的多模态 UserMessage；
            // 仅 image 类型进 Media（document 走知识库 RAG 通道，仅落库留痕标记，不进多模态）；
            // 仅对当轮新消息生效，历史消息无附件不处理（历史里只有落库的占位标记文本）
            boolean hasImageAttachments = request.getAttachments() != null && request.getAttachments().stream()
                    .anyMatch(a -> a != null && AttachmentDTO.TYPE_IMAGE.equals(a.getType()));

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
                    case USER -> {
                        if (hasImageAttachments && currentUserMessageId != null
                                && currentUserMessageId.equals(msg.getId())) {
                            // 当轮文本追加文档留痕标记：document 不进 Media，模型通过消息文本里的 [文档：name](url) 标记感知
                            messages.add(buildMediaUserMessage(
                                    request.getMessage() + buildDocumentMarkers(request.getAttachments()),
                                    request.getAttachments()));
                        } else {
                            messages.add(new UserMessage(msg.getContent()));
                        }
                    }
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
            // 阶段1工具调用 Options 注入 opencode 网关会话头（缺失返回 400，会话级稳定ID） @author H
            OpenAiChatOptions toolOptions = OpenCodeGatewayHeaders.applyTo(OpenAiChatOptions.builder()
                    .apiKey(baseOptions.getApiKey())
                    .baseUrl(baseOptions.getBaseUrl())
                    .model(baseOptions.getModel()), finalConversationId)
                    .toolCallbacks(unifiedToolCallbacks)
                    .build();

            // 8. 累积思考内容，用于最终持久化（工具阶段 + 流式阶段共享）
            StringBuilder thinkingAccum = new StringBuilder();
            // 累积RAG命中来源（用于SSE推送与持久化）
            final List<Map<String, Object>> sourcesAccum = new ArrayList<>();

            // 9. 阶段2：构建不带工具回调的流式Options，避免流式阶段触发工具调用导致空文本（降级时按模型重建）
            // 阶段2流式 Options 同样注入会话头（流式请求与工具阶段同一网关强制校验） @author H
            OpenAiChatOptions streamOptions = OpenCodeGatewayHeaders.applyTo(OpenAiChatOptions.builder()
                    .apiKey(baseOptions.getApiKey())
                    .baseUrl(baseOptions.getBaseUrl())
                    .model(baseOptions.getModel()), finalConversationId)
                    .build();

            // 上下文 token 首值估算：与历史打开接口共用 estimateSnapshot 同一代码路径，不允许两套算法分叉；
            // 当轮附件图片按每张固定 1000 token 计入 system 段（口径见 IMAGE_TOKENS_PER_ATTACHMENT）；
            // 仅统计 image 类型：document 走知识库 RAG 通道，不占视觉 token @author H
            int imageCount = hasImageAttachments
                    ? (int) request.getAttachments().stream()
                            .filter(a -> a != null && AttachmentDTO.TYPE_IMAGE.equals(a.getType()))
                            .count()
                    : 0;
            ContextUsageSnapshot snapshot = estimateSnapshot(systemPrompt, conversation, history, imageCount);
            int systemTokens = snapshot.getBreakdown().getSystem();
            int summaryTokens = snapshot.getBreakdown().getSummary();
            int historyTokens = snapshot.getBreakdown().getHistory();
            int ragTokens = snapshot.getBreakdown().getRag();
            int promptTokens = snapshot.getPromptTokens();
            int contextWindow = snapshot.getContextWindow();
            int percent = snapshot.getPercent();

            // 元数据消息（第一个SSE事件，结构化JSON：包含type和conversationId；访客模式返回guest标识）
            // promptTokens/contextWindow/percent/breakdown 为新增展示字段，前端忽略未知字段保持兼容
            String metaJson = "{\"type\":\"meta\",\"conversationId\":\""
                    + (finalConversationId != null ? finalConversationId : "guest") + "\""
                    + ",\"promptTokens\":" + promptTokens
                    + ",\"contextWindow\":" + contextWindow
                    + ",\"percent\":" + percent
                    + ",\"stage\":\"estimated\""
                    + ",\"breakdown\":{\"history\":" + historyTokens
                    + ",\"summary\":" + summaryTokens
                    + ",\"rag\":" + ragTokens
                    + ",\"system\":" + systemTokens + "}}";

            // 上下文快照盒子：RAG 修正与 usage 校准覆盖同一快照（后值覆盖前值，不累加；system 每轮相同只计一次） @author H
            final int systemTokensSnap = systemTokens;
            final int summaryTokensSnap = summaryTokens;
            final int historyTokensSnap = historyTokens;
            final int contextWindowSnap = contextWindow;
            final int[] ragTokensBox = {ragTokens};
            final int[] promptTokensBox = {promptTokens};
            final int[] percentBox = {percent};

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

                    // RAG 两阶段修正：source 到达后用 snippet 全文替换重估 rag 段（非累加）；
                    // 同一轮重复 source 以 sourcesAccum 最新全量去重集合为准；snippet 为空时保留初始占位 @author H
                    try {
                        int snippetTokens = estimateRagTokensFromSources(sourcesAccum);
                        if (snippetTokens > 0 && !sink.isCancelled()) {
                            int ragTokensOld = ragTokensBox[0];
                            // rag 段 = snippet 全文 + 工具 schema 基数（真实请求两者都进 prompt，收窄与 actual 的缺口）
                            int schemaTokens = estimateToolSchemaTokens();
                            int ragTokensNew = snippetTokens + schemaTokens;
                            ragTokensBox[0] = ragTokensNew;
                            // 自适应占位回写：钳位上限为窗口 50%，防单轮异常命中回声放大污染后续轮估算；
                            // 同步写 Redis 持久化（7 天过期），重启后首轮经 Redis 恢复不回落 @author H
                            if (finalConversationId != null) {
                                int cappedRag = Math.min(ragTokensNew, contextWindowSnap / 2);
                                lastRagTokensByConversation.put(finalConversationId, cappedRag);
                                try {
                                    stringRedisTemplate.opsForValue().set(CTX_RAG_REDIS_KEY_PREFIX + finalConversationId,
                                            String.valueOf(cappedRag), CTX_RAG_REDIS_TTL);
                                } catch (Exception e) {
                                    log.warn("RAG 修正值写 Redis 失败（内存态仍生效）: conversationId={}", finalConversationId, e);
                                }
                            }
                            promptTokensBox[0] = systemTokensSnap + summaryTokensSnap + historyTokensSnap + ragTokensNew;
                            percentBox[0] = contextWindowSnap > 0
                                    ? (int) Math.floor(promptTokensBox[0] * 100.0 / contextWindowSnap) : 0;
                            log.info("RAG 修正: conversationId={}, ragTokens={}->{}(snippet={}, schema={}), promptTokens={}, breakdown(history={}, summary={}, rag={}, system={})",
                                    finalConversationId, ragTokensOld, ragTokensNew, snippetTokens, schemaTokens,
                                    promptTokensBox[0], historyTokensSnap, summaryTokensSnap, ragTokensBox[0], systemTokensSnap);
                            sink.next(buildContextUpdateJson(finalConversationId, promptTokensBox[0],
                                    contextWindowSnap, percentBox[0], historyTokensSnap, summaryTokensSnap,
                                    ragTokensBox[0], systemTokensSnap, 0, "estimated"));
                        }
                    } catch (Exception e) {
                        log.warn("RAG 修正失败，保留初始估算", e);
                    }

                    // 阶段进度：构建输出结构（工具调用结束、流式开始前，对应第 7 阶段）
                    sink.next(stageSupport.wrapStage(ThinkingStageSupport.THINK_STAGE_STRUCTURE));
                    // 最终润色阶段（流式正文输出前的最后准备）
                    sink.next(stageSupport.wrapStage(ThinkingStageSupport.THINK_STAGE_POLISH));
                } catch (Exception e) {
                    log.error("聊天失败: conversationId={}", finalConversationId, e);
                    // 工具阶段异常同样落库助手占位回复，避免留下无答复的孤儿 USER，保证历史上下文完整（与流式异常分支一致） @author H
                    if (finalConversationId != null) {
                        saveMessage(finalConversationId, MessageRoleEnum.ASSISTANT.getCode(), FALLBACK_TEXT, null);
                        conversationService.updateStats(finalConversationId, estimateTokens(FALLBACK_TEXT));
                        triggerCompressAsync(finalConversationId, userId);
                    }
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
                // 流式 usage 回执暂存：每轮 ChatResponse 到达时以后值覆盖前值，多轮取最新轮 @author H
                final AtomicInteger streamedUsagePromptTokens = new AtomicInteger(0);
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
                                // 追问提示词泄漏防护：长度 >500 且含“你是HAgent追问助手”的 thinking 直接过滤不推送不落库 @author H
                                if (com.hagent.ai.core.ThinkingStageSupport.isFollowupPromptLeak(thinkingChunk)) {
                                    log.warn("流式 thinking 追问提示词泄漏已过滤: conversationId={} length={}", finalConversationId, thinkingChunk.length());
                                } else {
                                    // 累积思考内容用于入库持久化（仅新增 delta，避免落库翻倍）
                                    thinkingAccum.append(thinkingChunk);
                                    streamedThinkingSeen.append(thinkingChunk);
                                    stageSupport.emitThinkingToSink(sink, thinkingChunk);
                                }
                            }
                            // 2) 再发射 token 增量前暂存 usage 回执（国产接口缺字段时静默跳过，保留估算） @author H
                            try {
                                Integer usagePrompt = extractPromptTokens(response);
                                if (usagePrompt != null && usagePrompt > 0) {
                                    streamedUsagePromptTokens.set(usagePrompt);
                                }
                            } catch (Exception ignore) {
                                log.debug("暂存流式 usage 回执失败，保留估算");
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
                            // 前端主动取消：不计失败、不落库、不推送错误，由前端 MessageAggregator 正常取消 @author H
                            if (isCancel(error)) {
                                log.info("流式已取消: conversationId={} cause={}", finalConversationId, error.toString());
                                sink.next("[FINAL]");
                                sink.complete();
                                return;
                            }
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
                                triggerCompressAsync(finalConversationId, userId);
                            }
                            sink.next(wrapError("AI服务暂时不可用，请稍后重试"));
                            sink.next("[FINAL]");
                            sink.complete();
                        },
                        () -> {
                            // usage 校准（业界口径：回答完成后环形圈收敛到实际消耗）：用模型 usage 回执的
                            // promptTokens 覆盖估算快照（总量与 percent 均以 actual 为准，stage=actual 供前端徽标切换）；
                            // actual 为模型侧精确上报且估算缺口已收敛（<1%），floor 下随轮次单调不跌；
                            // 无回执（国产接口缺 usage 字段）静默跳过保留估算，stage 维持 estimated @author H
                            try {
                                int actualPrompt = streamedUsagePromptTokens.get();
                                if (actualPrompt > 0 && !sink.isCancelled()) {
                                    int estimatedBefore = promptTokensBox[0];
                                    promptTokensBox[0] = actualPrompt;
                                    percentBox[0] = contextWindowSnap > 0
                                            ? (int) Math.floor(actualPrompt * 100.0 / contextWindowSnap) : 0;
                                    log.info("usage 校准: estimated={}, actual={}, 下发(promptTokens={}, percent={}, stage=actual)",
                                            estimatedBefore, actualPrompt, promptTokensBox[0], percentBox[0]);
                                    sink.next(buildContextUpdateJson(finalConversationId, promptTokensBox[0],
                                            contextWindowSnap, percentBox[0], historyTokensSnap, summaryTokensSnap,
                                            ragTokensBox[0], systemTokensSnap, actualPrompt, "actual"));
                                }
                            } catch (Exception e) {
                                log.warn("usage 校准失败，保留估算", e);
                            }
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
                                triggerCompressAsync(finalConversationId, userId);
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
    /**
     * 判断是否为前端主动取消（CANCEL）
     * 特征：okhttp StreamResetException: CANCEL / stream was reset: CANCEL / IOExceptionWrappingSequence hasNext 为 CANCEL
     *
     * @param e 异常
     * @return 是否为取消
     * @author H
     */
    private static boolean isCancel(Throwable e) {
        if (e == null) {
            return false;
        }
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 10) {
            String cls = cur.getClass().getSimpleName();
            String msg = cur.getMessage();
            if (cls != null && (cls.toLowerCase().contains("cancel") || cls.contains("StreamResetException"))) {
                return true;
            }
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("cancel")) {
                    return true;
                }
                if (lower.contains("stream was reset") && lower.contains("cancel")) {
                    return true;
                }
                if (lower.contains("ioexceptionwrappingsequence") && lower.contains("cancel")) {
                    return true;
                }
            }
            cur = cur.getCause();
            depth++;
        }
        String topMsg = e.getMessage();
        return topMsg != null && topMsg.toLowerCase().contains("cancel");
    }

    /**
     * 判断是否可重试（模型侧 5xx/429/timeout）
     *
     * @param e 异常
     * @return 是否可重试
     * @author H
     */
    private static boolean isRetryable(Throwable e) {
        if (isCancel(e)) {
            return false;
        }
        if (e instanceof java.util.concurrent.TimeoutException) {
            return true;
        }
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 10) {
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("first packet timeout")) {
                    return true;
                }
                if (lower.contains("429") || lower.contains("too many requests") || lower.contains("rate limit")) {
                    return true;
                }
                if (lower.contains("500") || lower.contains("502") || lower.contains("503") || lower.contains("504")
                        || lower.contains("internal server error") || lower.contains("bad gateway")
                        || lower.contains("service unavailable") || lower.contains("gateway timeout")) {
                    return true;
                }
                if (lower.contains("openaiioexception") || lower.contains("stream failed")) {
                    return true;
                }
            }
            if (cur.getClass().getSimpleName().toLowerCase().contains("timeout")) {
                return true;
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    private Flux<ChatResponse> streamWithFallbackInternal(List<ChatModel> ordered, List<Message> messages, OpenAiChatOptions baseStreamOptions, int index) {
        if (index >= ordered.size()) {
            return Flux.error(new RuntimeException("all chat models failed"));
        }
        ChatModel current = ordered.get(index);
        OpenAiChatOptions curOptions;
        if (current instanceof OpenAiChatModel openAiChatModel) {
            OpenAiChatOptions base = openAiChatModel.getOptions();
            // 降级重建 Options 时透传基准流式 Options 的网关会话头（每级降级都必须携带，缺失 400；
            // 经基准 Options 数据流传递而非 ThreadLocal，保证 reactor 递归线程中会话级 ID 不退化） @author H
            curOptions = OpenAiChatOptions.builder()
                    .apiKey(base.getApiKey())
                    .baseUrl(base.getBaseUrl())
                    .model(base.getModel())
                    .customHeaders(baseStreamOptions.getCustomHeaders())
                    .build();
        } else {
            curOptions = baseStreamOptions;
        }
        Prompt prompt = new Prompt(messages, curOptions);
        return current.stream(prompt)
                .onErrorResume(e -> {
                    // 前端主动取消：直接透传，不再 WARN fallback，避免熔断误触发
                    if (isCancel(e)) {
                        log.info("stream cancel detected, no fallback model={} cause={}", resolveModelName(current), e.toString());
                        return Flux.error(e);
                    }
                    // 仅 5xx/429/timeout 才重试；其他非重试异常直接透传
                    if (!isRetryable(e)) {
                        log.info("stream non-retryable error, no fallback model={} cause={}", resolveModelName(current), e.toString());
                        return Flux.error(e);
                    }
                    if (index >= ordered.size() - 1) {
                        return Flux.error(e);
                    }
                    ChatModel next = ordered.get(index + 1);
                    log.warn("fallback to level {}/{} {}", index, index + 1, resolveModelName(next), e);
                    return streamWithFallbackInternal(ordered, messages, baseStreamOptions, index + 1);
                });
    }

    /**
     * 现算历史打开首值（上下文用量的唯一真源，只读不落库）
     * 默认提示词取管理端（接口归属 /admin/ai/conversations），自定义部分取会话 systemPrompt；
     * 核心估算委托 estimateSnapshot，与聊天 meta 首值同一代码路径
     *
     * @param conversationId 会话ID
     * @return 上下文用量快照
     * @author H
     */
    @Override
    public ContextUsageSnapshot estimateInitial(Long conversationId) {
        ChatConversation conversation = conversationService.getById(conversationId);
        if (conversation == null) {
            throw new BusinessException("会话不存在: id=" + conversationId);
        }
        String systemPrompt = promptHolder.getAdminPrompt();
        if (conversation.getSystemPrompt() != null && !conversation.getSystemPrompt().isBlank()) {
            systemPrompt += "\n\n附加上下文: " + conversation.getSystemPrompt();
        }
        int keepTurns = resolveKeepTurns();
        int historyLimit = Math.max(2, keepTurns * 2);
        List<ChatMessage> history = chatMessageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, conversationId)
                        .orderByDesc(ChatMessage::getCreatedAt)
                        .last("LIMIT " + historyLimit));
        java.util.Collections.reverse(history);
        // 历史页快照审计：只读现算结果落一行日志，逻辑与返回值不动 @author H
        // 历史页无当轮附件，图片数固定传 0
        ContextUsageSnapshot initialSnapshot = estimateSnapshot(systemPrompt, conversation, history, 0);
        log.info("历史页快照: conversationId={}, promptTokens={}, percent={}, msgCount={}",
                conversationId, initialSnapshot.getPromptTokens(), initialSnapshot.getPercent(),
                history != null ? history.size() : 0);
        return initialSnapshot;
    }

    /**
     * 上下文首值估算核心（展示用初筛，经 meta 事件透出；不影响模型调用与累计口径）
     * system 段 = 默认提示词 + 自定义 systemPrompt + 附件图片固定 token（每张 1000，口径见 IMAGE_TOKENS_PER_ATTACHMENT，
     * 并入 system 段为实现最简方案，不新增 SSE breakdown 字段，事件协议不变）；summary 段 = 会话摘要正文 summaryText；
     * history 段 = DB 历史原文求和；rag 段 = 工具 schema 占位（source 到达后二次修正）
     *
     * @param systemPrompt 已拼装的系统提示词（默认加自定义）
     * @param conversation 会话实体（取摘要正文，可空）
     * @param history      DB 历史消息（取内容原文，可空）
     * @param imageCount   当轮附件图片张数（历史页等无附件场景传 0）
     * @return 上下文用量快照
     * @author H
     */
    private ContextUsageSnapshot estimateSnapshot(String systemPrompt, ChatConversation conversation,
                                                  List<ChatMessage> history, int imageCount) {
        // 附件图片口径：每张固定按 1000 token 计入 system 段（多模态图片无字符数可估，取业界常用固定值）
        int imageTokens = imageCount * IMAGE_TOKENS_PER_ATTACHMENT;
        int systemTokens = ContextTokenEstimator.estimate(systemPrompt) + imageTokens;
        String summaryTextForTokens = (conversation != null && conversation.getSummaryText() != null
                && !conversation.getSummaryText().isBlank()) ? conversation.getSummaryText() : null;
        int summaryTokens = ContextTokenEstimator.estimate(summaryTextForTokens);
        List<String> historyParts = new ArrayList<>();
        if (history != null) {
            for (ChatMessage msg : history) {
                if (msg.getContent() == null) {
                    continue;
                }
                // 与模型侧组装同口径：跳过非法 role 与空回复兜底文案，避免估算高估 @author H
                MessageRoleEnum roleEnum = MessageRoleEnum.fromCode(msg.getRole());
                if (roleEnum == null) {
                    continue;
                }
                if (roleEnum == MessageRoleEnum.ASSISTANT && FALLBACK_TEXT.equals(msg.getContent())) {
                    continue;
                }
                historyParts.add(msg.getContent());
            }
        }
        int historyTokens = ContextTokenEstimator.estimateMessages(historyParts);
        int ragTokens = estimateToolSchemaTokens();
        // 自适应 RAG 占位（三级回退：内存 Map → Redis → 默认 schema 占位）：上轮修正后的 rag 段总值
        // 替代默认占位，消除每轮重置 154 的系统性低估；重启后内存清空经 Redis 恢复，避免首轮显示假跌；
        // 访客无会话 ID 时沿用默认占位 @author H
        Long ragConversationId = conversation != null ? conversation.getId() : null;
        String ragPlaceholder = "默认(" + ragTokens + ")";
        if (ragConversationId != null) {
            Integer adaptiveRag = lastRagTokensByConversation.get(ragConversationId);
            if (adaptiveRag == null) {
                // 内存 Map 未命中（重启后清空）查 Redis 恢复；异常降级回退默认占位
                try {
                    String cached = stringRedisTemplate.opsForValue().get(CTX_RAG_REDIS_KEY_PREFIX + ragConversationId);
                    if (cached != null) {
                        adaptiveRag = Integer.parseInt(cached.trim());
                    }
                } catch (Exception e) {
                    log.debug("读取 RAG 占位 Redis 失败，回退默认: conversationId={}", ragConversationId, e);
                }
            }
            if (adaptiveRag != null) {
                if (adaptiveRag != ragTokens) {
                    ragPlaceholder = "自适应(" + adaptiveRag + ")";
                }
                ragTokens = adaptiveRag;
            }
        }
        int promptTokens = systemTokens + summaryTokens + historyTokens + ragTokens;
        int contextWindow = 128000;
        try {
            Integer configured = aiConfig.getChat().getContextWindow();
            if (configured != null && configured > 0) {
                contextWindow = configured;
            }
        } catch (Exception e) {
            log.warn("读取上下文窗口失败，回退 128000", e);
        }
        // floor 取整：分子单调增时显示百分比永不回退（round 在占位回落边界会出现真实值涨但显示跌）
        int percent = contextWindow > 0 ? (int) Math.floor(promptTokens * 100.0 / contextWindow) : 0;
        Long logConversationId = conversation != null ? conversation.getId() : null;
        // 可审计输入量：历史条数与原文字符数、系统提示词与摘要字符数（仅日志，不影响估算口径） @author H
        int historyChars = 0;
        for (String part : historyParts) {
            historyChars += part.length();
        }
        int systemChars = systemPrompt != null ? systemPrompt.length() : 0;
        int summaryChars = summaryTextForTokens != null ? summaryTextForTokens.length() : 0;
        log.info("上下文估算: conversationId={}, promptTokens={}, contextWindow={}, percent={}, breakdown(history={}, summary={}, rag={}, system={}), msgCount={}, historyChars={}, systemChars={}, summaryChars={}, ragPlaceholder={}, images={}",
                logConversationId, promptTokens, contextWindow, percent,
                historyTokens, summaryTokens, ragTokens, systemTokens,
                historyParts.size(), historyChars, systemChars, summaryChars, ragPlaceholder, imageCount);
        return ContextUsageSnapshot.of(promptTokens, contextWindow, percent,
                historyTokens, summaryTokens, ragTokens, systemTokens);
    }

    /**
     * 估算工具 schema 段 token 数（按 JSON 长度同口径计入 rag 段）
     *
     * @return 工具 schema 估算 token 数，失败时返回 0
     * @author H
     */
    private int estimateToolSchemaTokens() {
        try {
            List<Map<String, String>> schemas = new ArrayList<>();
            if (unifiedToolCallbacks != null) {
                for (ToolCallback callback : unifiedToolCallbacks) {
                    Map<String, String> schema = new LinkedHashMap<>();
                    try {
                        schema.put("name", String.valueOf(callback.getToolDefinition().name()));
                        schema.put("description", String.valueOf(callback.getToolDefinition().description()));
                    } catch (Exception e) {
                        schema.put("name", callback.toString());
                    }
                    schemas.add(schema);
                }
            }
            return ContextTokenEstimator.estimate(objectMapper.writeValueAsString(schemas));
        } catch (Exception e) {
            log.warn("估算工具 schema token 失败", e);
            return 0;
        }
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
     * 用累积来源的 snippet 全文重估本轮 rag 段（替换非累加，全量去重）
     * snippet 为空或缺失时贡献 0，调用方在返回值小于等于 0 时保留初始占位
     *
     * @param sourcesAccum 累积的来源列表（含 snippet 字段）
     * @return rag 段重估 token 数，失败时返回 0
     * @author H
     */
    private int estimateRagTokensFromSources(List<Map<String, Object>> sourcesAccum) {
        try {
            if (sourcesAccum == null || sourcesAccum.isEmpty()) {
                return 0;
            }
            Set<String> snippets = new LinkedHashSet<>();
            for (Map<String, Object> hit : sourcesAccum) {
                if (hit == null) {
                    continue;
                }
                Object snippet = hit.get("snippet");
                if (snippet != null) {
                    String text = String.valueOf(snippet);
                    if (!text.isBlank()) {
                        snippets.add(text);
                    }
                }
            }
            return ContextTokenEstimator.estimateMessages(new ArrayList<>(snippets));
        } catch (Exception e) {
            log.warn("重估 RAG snippet token 失败", e);
            return 0;
        }
    }

    /**
     * 构建 context-update 事件（与 meta 同 shape，仅 type 不同，前端忽略未知 type 保持兼容）
     * 环形圈数值口径由 stage 标注：estimated=流中估算（发送/RAG修正），actual=回答完成的真实消耗（usage回执）
     *
     * @param conversationId 会话ID
     * @param promptTokens 提示 token 总数
     * @param contextWindow 上下文窗口
     * @param percent 使用百分比
     * @param historyTokens 历史段 token
     * @param summaryTokens 摘要段 token
     * @param ragTokens RAG 段 token
     * @param systemTokens 系统段 token
     * @param actualPromptTokens 上轮实际 promptTokens（无回执时传 0，前端缺省 0）
     * @param stage 数值口径：estimated=估算 / actual=实际（前端徽标展示用）
     * @return context-update 事件 JSON
     * @author H
     */
    private String buildContextUpdateJson(Long conversationId, int promptTokens, int contextWindow, int percent,
                                          int historyTokens, int summaryTokens, int ragTokens, int systemTokens,
                                          int actualPromptTokens, String stage) {
        return "{\"type\":\"context-update\",\"conversationId\":\""
                + (conversationId != null ? conversationId : "guest") + "\""
                + ",\"promptTokens\":" + promptTokens
                + ",\"contextWindow\":" + contextWindow
                + ",\"percent\":" + percent
                + ",\"stage\":\"" + stage + "\""
                + ",\"actualPromptTokens\":" + actualPromptTokens
                + ",\"breakdown\":{\"history\":" + historyTokens
                + ",\"summary\":" + summaryTokens
                + ",\"rag\":" + ragTokens
                + ",\"system\":" + systemTokens + "}}";
    }

    /**
     * 从 ChatResponse 提取服务端 usage 回执的实际 promptTokens
     * 国产接口缺字段时返回 null，调用方静默跳过保留估算
     *
     * @param response 聊天响应
     * @return 实际 promptTokens，拿不到时返回 null
     * @author H
     */
    private Integer extractPromptTokens(ChatResponse response) {
        try {
            if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
                return null;
            }
            Integer promptTokens = response.getMetadata().getUsage().getPromptTokens();
            return (promptTokens != null && promptTokens > 0) ? promptTokens : null;
        } catch (Exception e) {
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
     * 解析历史保留轮数（整轮保留，DB 查询条数为 keepTurns*2）
     *
     * @return 保留轮数，配置缺失或非法时回退 10
     * @author H
     */
    private int resolveKeepTurns() {
        try {
            AiConfig.MemorySummary summary = aiConfig.getMemorySummary();
            if (summary != null && summary.getKeepTurns() >= 1 && summary.getKeepTurns() <= 50) {
                return summary.getKeepTurns();
            }
            log.warn("摘要保留轮数越界，回退 10");
        } catch (Exception e) {
            log.warn("读取摘要保留轮数失败，回退 10", e);
        }
        return 10;
    }

    /**
     * 异步触发会话摘要压缩（assistant 落库后调用一次，失败仅日志不影响主流程）
     *
     * @param conversationId 会话ID
     * @param userId         用户ID
     * @author H
     */
    private void triggerCompressAsync(Long conversationId, Long userId) {
        try {
            conversationMemoryService.compressIfNeededAsync(conversationId, userId);
        } catch (Exception e) {
            log.warn("触发摘要压缩失败: conversationId={}, error={}", conversationId, e.getMessage());
        }
    }

    /**
     * 构建用户消息落库内容：带附件时末尾追加标记（不加 DB 列，历史与前端回显可见）
     * 图片（既有逻辑不动）：URL 形态逐张追加 ![图片](url) markdown；纯 base64 无 URL 时维持（附图片 N 张）文字标记；
     * 文档：逐个追加 [文档：name](url) 留痕标记（与前端历史解析契约锁死，前端按该格式精确匹配），
     * 排在图片 markdown 之后；无 url 时退化为 [文档：name]
     *
     * @param request 聊天请求
     * @return 落库内容（无附件时为原始消息原文）
     * @author H
     */
    private String buildUserMessageContent(ChatRequest request) {
        List<AttachmentDTO> attachments = request.getAttachments();
        if (attachments == null || attachments.isEmpty()) {
            return request.getMessage();
        }
        // 图片附件（既有口径不变）：收集 URL 形态（base64 形态无 URL，不产生 markdown）
        List<AttachmentDTO> images = attachments.stream()
                .filter(a -> a != null && AttachmentDTO.TYPE_IMAGE.equals(a.getType()))
                .toList();
        List<String> imageUrls = images.stream()
                .map(AttachmentDTO::getDataUrl)
                .filter(url -> url != null && (url.startsWith("http://") || url.startsWith("https://")))
                .toList();
        StringBuilder content = new StringBuilder(request.getMessage());
        if (!imageUrls.isEmpty()) {
            // URL 形态图片逐张追加 markdown（历史与前端可直接回显）
            for (String url : imageUrls) {
                content.append("\n![图片](").append(url).append(")");
            }
        } else if (!images.isEmpty()) {
            // 纯 base64 图片无 URL，维持文字占位（仅统计图片，文档不占视觉口径）
            content.append("（附图片 ").append(images.size()).append(" 张）");
        }
        // 文档留痕标记排在图片 markdown 之后（与前端解析顺序契约一致）
        content.append(buildDocumentMarkers(attachments));
        return content.toString();
    }

    /**
     * 构建文档附件留痕标记后缀（当轮模型文本与落库内容共用，保证两处格式一致）
     * 与前端历史解析契约锁死，新旧两格式均合法：
     * ① 新格式（document 附件带 fileId）："\n[文档：name|fileId](url)"，无 url 退化为 "\n[文档：name|fileId]"；
     * ② 旧格式（无 fileId，历史消息/旧前端兼容）："\n[文档：name](url)"，无 url 退化为 "\n[文档：name]"；
     * 新格式供模型经 searchKnowledge 的 fileId 参数精确锁定检索（见 buildReferencedFilePrompt 注入指引），
     * 文档已在上传时入知识库并向量化，消息侧仅留痕，模型经知识库 RAG 通道感知内容
     *
     * @param attachments 附件列表
     * @return 文档标记串（无文档附件时为空串）
     * @author H
     */
    private String buildDocumentMarkers(List<AttachmentDTO> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return "";
        }
        StringBuilder markers = new StringBuilder();
        for (AttachmentDTO attachment : attachments) {
            if (attachment == null || !AttachmentDTO.TYPE_DOCUMENT.equals(attachment.getType())) {
                continue;
            }
            String name = attachment.getName() != null ? attachment.getName() : "";
            // 新格式带 |fileId（精确检索锁定），旧格式无 |fileId 继续合法，前端解析两者兼容
            markers.append("\n[文档：").append(name);
            if (attachment.getFileId() != null) {
                markers.append("|").append(attachment.getFileId());
            }
            markers.append("]");
            if (attachment.getDataUrl() != null && !attachment.getDataUrl().isBlank()) {
                markers.append("(").append(attachment.getDataUrl()).append(")");
            }
        }
        return markers.toString();
    }

    /**
     * 构建知识库文件精确引用指引（当轮用户消息含 [文档：name|fileId] 新格式标记时追加到系统提示）
     * <p>
     * 检测口径与落库/当轮组消息同源（buildUserMessageContent），保证标记生成与识别一致；
     * 重新生成场景 message 即历史落库原文（可能含新格式标记），同样覆盖；
     * 旧格式 [文档：name]（无 fileId）不匹配、不注入，模型按既有文件名语义检索不受影响
     *
     * @param request 聊天请求
     * @return 指引文本（每个引用文件一行；无精确引用时为空串，直接拼接安全）
     * @author H
     */
    private String buildReferencedFilePrompt(ChatRequest request) {
        String userContent = buildUserMessageContent(request);
        Matcher matcher = DOC_MARKER_WITH_FILE_ID_PATTERN.matcher(userContent);
        StringBuilder instruction = new StringBuilder();
        while (matcher.find()) {
            instruction.append("\n用户引用了知识库文件《").append(matcher.group(1))
                    .append("》(id=").append(matcher.group(2))
                    .append(")，检索该文件内容时请使用 searchKnowledge 的 fileId 参数");
        }
        return instruction.toString();
    }

    /**
     * 构建含图片 Media 的多模态用户消息（仅当轮新消息使用）
     * base64 形态：dataUrl 前缀解析 MimeType，base64 解码为 ByteArrayResource 交给 Spring AI Media（构造签名以依赖 jar 实测为准）；
     * URL 形态：Media.builder().data(URI) 交给底层（Spring AI 2.0.1 OpenAI 适配对 image/* + URI 数据原样透传 image_url，
     * 由模型侧拉取，服务端不预下载探测），mime 从 URL 扩展名推断、无法识别时回退 image/png；
     * 元素级合法性已由 @ValidAttachments 在入口校验，此处解析失败按业务异常兜底（中文提示，不 500）
     *
     * @param text        用户消息文本（原始消息，不含落库占位标记）
     * @param attachments 图片附件列表（入口已校验非空且合法）
     * @return 含 Media 的多模态 UserMessage
     * @author H
     */
    private UserMessage buildMediaUserMessage(String text, List<AttachmentDTO> attachments) {
        List<Media> mediaList = new ArrayList<>();
        for (AttachmentDTO attachment : attachments) {
            // 仅 image 类型进 Media；document 走知识库 RAG 通道，模型通过消息文本里的 [文档：name](url) 标记感知
            if (attachment == null || !AttachmentDTO.TYPE_IMAGE.equals(attachment.getType())
                    || attachment.getDataUrl() == null) {
                continue;
            }
            String dataUrl = attachment.getDataUrl();
            // URL 形态：URI 交给底层延迟拉取，mime 仅需 image/* 前缀以命中 OpenAI 适配的 image 分支
            if (dataUrl.startsWith("http://") || dataUrl.startsWith("https://")) {
                try {
                    Media.Builder mediaBuilder = Media.builder()
                            .mimeType(MimeType.valueOf(resolveUrlMime(dataUrl)))
                            .data(URI.create(dataUrl));
                    if (attachment.getName() != null && !attachment.getName().isBlank()) {
                        mediaBuilder.name(attachment.getName());
                    }
                    mediaList.add(mediaBuilder.build());
                } catch (Exception e) {
                    throw new BusinessException("附件图片链接解析失败，请提供合法的 http(s) 图片链接");
                }
                continue;
            }
            // base64 形态：解码为字节数组内联传输
            int markerIdx = dataUrl.indexOf(BASE64_MARKER);
            if (markerIdx < 0 || !dataUrl.startsWith("data:")) {
                throw new BusinessException("附件图片数据格式不合法，必须为 data:image/*;base64 数据或 http(s) 图片链接");
            }
            String mime = dataUrl.substring("data:".length(), dataUrl.indexOf(";"));
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(dataUrl.substring(markerIdx + BASE64_MARKER.length()));
            } catch (IllegalArgumentException e) {
                throw new BusinessException("附件图片数据解码失败，请提供合法的 base64 内容");
            }
            try {
                Media.Builder mediaBuilder = Media.builder()
                        .mimeType(MimeType.valueOf(mime))
                        .data(new ByteArrayResource(bytes));
                if (attachment.getName() != null && !attachment.getName().isBlank()) {
                    mediaBuilder.name(attachment.getName());
                }
                mediaList.add(mediaBuilder.build());
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                throw new BusinessException("附件图片解析失败，请检查图片数据格式");
            }
        }
        return UserMessage.builder().text(text).media(mediaList).build();
    }

    /**
     * 从 URL 路径推断图片 MIME 类型（仅取路径段，忽略查询参数；无法识别时回退 image/png）
     *
     * @param url 图片 URL
     * @return image/* MIME 类型
     * @author H
     */
    private String resolveUrlMime(String url) {
        String path = url;
        int queryIdx = path.indexOf('?');
        if (queryIdx >= 0) {
            path = path.substring(0, queryIdx);
        }
        String mime = URLConnection.guessContentTypeFromName(path);
        if (mime != null && mime.startsWith("image/")) {
            return mime;
        }
        return "image/png";
    }

    /**
     * 保存聊天消息（无思考内容）
     *
     * @param conversationId 会话ID
     * @param role           消息角色
     * @param content        消息内容
     * @param toolName       工具名称（非工具消息为null）
     * @return 消息ID（访客临时会话无持久化目标时返回 null）
     * @author H
     */
    private Long saveMessage(Long conversationId, Integer role, String content, String toolName) {
        return saveMessage(conversationId, role, content, toolName, null);
    }

    /**
     * 保存聊天消息（含思考内容持久化）
     *
     * @param conversationId  会话ID
     * @param role            消息角色
     * @param content         消息内容
     * @param toolName        工具名称（非工具消息为null）
     * @param thinkingContent 思考过程内容（仅助手消息可能携带，可为null）
     * @return 消息ID（访客临时会话无持久化目标时返回 null）
     * @author H
     */
    private Long saveMessage(Long conversationId, Integer role, String content, String toolName, String thinkingContent) {
        return saveMessage(conversationId, role, content, toolName, thinkingContent, null);
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
     * @return 消息ID（访客临时会话无持久化目标时返回 null）
     * @author H
     */
    private Long saveMessage(Long conversationId, Integer role, String content, String toolName, String thinkingContent, String sourcesJson) {
        // 访客临时会话无持久化目标，直接跳过
        if (conversationId == null) {
            return null;
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
        // MyBatis-Plus 插入后回填自增主键，供当轮多模态消息组装时按 ID 识别
        return msg.getId();
    }
}
