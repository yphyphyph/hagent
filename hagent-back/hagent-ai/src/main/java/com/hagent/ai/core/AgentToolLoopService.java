package com.hagent.ai.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.FluxSink;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent 工具调用循环服务
 * <p>
 * 执行契约：本服务是工具调用的唯一执行方。Spring AI 2.0.1 的 OpenAiChatModel.internalCall
 * 已彻底移除模型内部工具执行分支（反编译核实：internalCall 无 executeToolCalls 调用），
 * chatModel.call 返回的原始 tool_calls 由本服务手动执行；若未来升级后行为变化，
 * 需在升级回归中确认工具不被框架重复执行。
 * <p>
 * 收敛机制：
 * 1. 跟踪 lastToolName / lastToolArgsNormalized / repeatCount
 * 2. 对每次工具调用归一化参数后判定相似度>80%则视为重复
 * 3. 连续重复达到 REPEAT_TOOL_LIMIT 时注入 CONVERGE_INSTRUCTION 并结束工具循环
 * 4. 循环结束后无论是否自然退出，均确保流式阶段带有收敛指令（hasConvergedMessage 判定）
 *
 * @author H
 */
@Slf4j
@Service
public class AgentToolLoopService {

    /** 工具调用最大迭代次数（配置化，默认 8，可经 system_config ai.tools.max-iterations 配置） */
    @Value("${ai.tools.max-iterations:8}")
    private int maxToolIterations;

    /** 兼容旧键 hagent.agent.max-tool-iterations，避免存量部署直接失效 */
    @Value("${hagent.agent.max-tool-iterations:#{null}}")
    private Integer legacyMaxToolIterations;

    @Autowired
    private com.hagent.ai.config.AiConfig aiConfig;

    /** 连续重复工具调用检测阈值：同一工具连续相似调用达到该次数视为发散循环，强制收敛 */
    private static final int REPEAT_TOOL_LIMIT = 4;

    /** 知识检索连续无相关结果终止阈值：达到该次数注入收敛指令，引导模型直接基于自身知识回答（防"换词重搜"发散循环） */
    private static final int RAG_MISS_LIMIT = 2;

    /** 知识检索无相关结果提示语前缀（与 KnowledgeTool.NO_HIT_HINT 保持一致，用于工具结果判定） */
    private static final String RAG_MISS_MARKER = "知识库中未找到";

    /** 工具循环总时间预算（毫秒）：超时强制收敛进入流式阶段，防止慢模型（改写+推理）耗尽 SSE 5 分钟超时 */
    private static final long TOOL_LOOP_BUDGET_MS = 90_000L;

    /** 工具结果最大字符数（超出截断，防止撑爆上下文） */
    private static final int MAX_TOOL_RESULT_LENGTH = 4000;

    /** 日志中工具参数最大截断长度 */
    private static final int MAX_LOG_ARGS_LEN = 100;

    /** 收敛指令：工具阶段结束，强制模型基于已有信息一次性给出完整回答，禁止再发起工具调用或输出"需要检索"的意图声明（依据测试一"半截回答"教训强化措辞） */
    private static final String CONVERGE_INSTRUCTION = "工具调用阶段已结束。请基于已有信息直接给出完整、连贯的最终回答。即使你认为还需要检索知识库或调用工具，也不得发起或以文本形式输出任何工具调用（如 <tool_call> 等标签），不得输出“我需要先检索一下”之类的意图声明后停止作答；若已有信息不足以完整回答，直接依据你自身的知识作答，或如实向用户说明信息不足。请一次性输出完整回答，不要话说一半停止。";

    /** 意图声明残段判定最大长度：流式最终回复短于该值且含检索/查询意图短语时，视为"话说一半"的残段（依据测试四 HXX 25 字残段教训） */
    private static final int INTENT_STUB_MAX_LEN = 100;

    /**
     * 纠正性重流指令：流式最终回复被判定为意图声明残段后注入，强制模型基于已有知识直接给出完整回答。
     * 场景：推理模型在无工具定义的流式阶段重新得出"应先检索知识库"的结论（flip-flop），仅输出意图声明即停笔，
     * 指令层（收敛指令）无法可靠压制，需由编排层检测残段后机械兜底重流一次 @author H
     */
    public static final String CORRECTIVE_ANSWER_INSTRUCTION = "系统检测到你刚才的回复只是一句检索意图声明（如“我需要先检索一下知识库”），并没有实际回答用户的问题。当前对话已经没有任何可用工具，检索请求永远无法执行。请立即直接回答用户最初的问题：基于你已有的知识（包括系统提示中的产品身份信息）给出完整、连贯、结构化的中文回答；如果确实不知道，请如实说明。严禁再次输出检索意图声明、任何形式的工具调用，或“我需要先……”之后停止作答的残句。";

    /** 文本形式工具调用标记（部分模型在无工具定义阶段会退化为此格式） */
    private static final String TEXT_TOOL_CALL_MARKER = "<tool_call>";

    /** 流式剥离用起始标记前缀（不含右尖括号，兼容 <tool_call> 完整形式） */
    private static final String STRIP_START_MARKER = "<tool_call";

    /** 流式剥离用结束标记 */
    private static final String STRIP_END_MARKER = "</tool_call>";

    /** 流式剥离用 think 块起始标记（推理模型在正文通道内联输出思考块时出现） */
    private static final String THINK_OPEN_MARKER = "<think>";

    /** 流式剥离用 think 块结束标记（正文通道无配对 <think> 时为孤儿标记，仅剥离标记本身保留正文） */
    private static final String THINK_CLOSE_MARKER = "</think>";

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

    private final ObjectMapper objectMapper;
    private final ThinkingStageSupport stageSupport;
    private final RagService ragService;
    /** 有序模型提供者（用于多级降级，无 Ollama，仅备用 API 模型） */
    private final AiModelProvider aiModelProvider;

    /** 文本形式工具调用的合成ID序列 */
    private final AtomicInteger textToolCallSeq = new AtomicInteger();

    public AgentToolLoopService(ObjectMapper objectMapper,
                                ThinkingStageSupport stageSupport,
                                RagService ragService,
                                @Lazy AiModelProvider aiModelProvider) {
        this.objectMapper = objectMapper;
        this.stageSupport = stageSupport;
        this.ragService = ragService;
        this.aiModelProvider = aiModelProvider;
    }

    /**
     * 解析模型名称，用于降级日志
     *
     * @param chatModel 对话模型
     * @return 模型名称
     * @author H
     */
    private String getModelName(ChatModel chatModel) {
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
     * 为指定模型构建带工具回调的 Options（复用备用模型的 apiKey/baseUrl/model）
     *
     * @param chatModel     目标模型
     * @param toolCallbacks 工具回调
     * @param fallbackOptions 兜底 Options（非 OpenAi 时使用）
     * @return 对应模型的 Options
     * @author H
     */
    private OpenAiChatOptions buildToolOptionsForModel(ChatModel chatModel, ToolCallback[] toolCallbacks, OpenAiChatOptions fallbackOptions) {
        if (chatModel instanceof OpenAiChatModel openAiChatModel) {
            OpenAiChatOptions base = openAiChatModel.getOptions();
            // 降级模型重建工具 Options 时注入 opencode 网关会话头（缺失 400）：
            // 会话ID取 UserContextHolder（callWithTools 运行线程已在 Flux.create 内重设），
            // 无会话上下文时回退实例级 UUID @author H
            return OpenCodeGatewayHeaders.applyTo(OpenAiChatOptions.builder()
                    .apiKey(base.getApiKey())
                    .baseUrl(base.getBaseUrl())
                    .model(base.getModel()), UserContextHolder.getConversationId())
                    .toolCallbacks(toolCallbacks)
                    .build();
        }
        return fallbackOptions;
    }

    /**
     * 工具调用循环（同步）— 稳定收敛方案
     * 最多执行 maxToolIterations 轮，每轮处理AI返回的工具调用
     * <p>
     * 当AI返回无工具调用时，该最终回复不加入messages（留给stream阶段重新生成），
     * 避免stream阶段看到"已回答过"导致返回空流。
     *
     * @param messages       消息列表（会被追加工具调用链）
     * @param options        带工具回调的Options
     * @param chatModel      聊天模型
     * @param toolCallbacks  当前可用工具回调
     * @param sink           SSE事件sink，工具/思考/来源事件实时写入直达前端
     * @param thinkingAccum  思考内容累积器（用于入库持久化）
     * @param sourcesAccum   RAG来源累积器（用于SSE推送与最终持久化）
     */
    /**
     * 解析生效的最大迭代次数
     * 优先级：AiConfig.tools.maxIterations（system_config ai.tools.max-iterations）> legacy hagent.agent.max-tool-iterations > @Value ai.tools.max-iterations
     *
     * @return 生效值，范围 1..20 兜底 8
     * @author H
     */
    private int effectiveMaxIterations() {
        // 1. AiConfig（system_config 热刷新，优先）
        try {
            if (aiConfig != null && aiConfig.getTools() != null) {
                int v = aiConfig.getTools().getMaxIterations();
                if (v >= 1 && v <= 20) {
                    return v;
                }
                if (v != 0) {
                    log.warn("AiConfig.tools.maxIterations 越界 {}，回退 @Value", v);
                }
            }
        } catch (Exception e) {
            log.debug("读取 AiConfig.tools.maxIterations 失败，回退 @Value: {}", e.getMessage());
        }
        // 2. 兼容旧键
        if (legacyMaxToolIterations != null && legacyMaxToolIterations >= 1 && legacyMaxToolIterations <= 20) {
            return legacyMaxToolIterations;
        }
        // 3. 新键 @Value
        if (maxToolIterations >= 1 && maxToolIterations <= 20) {
            return maxToolIterations;
        }
        return 8;
    }

    public void callWithTools(List<Message> messages, OpenAiChatOptions options, ChatModel chatModel,
                               ToolCallback[] toolCallbacks, FluxSink<String> sink,
                               StringBuilder thinkingAccum,
                               List<Map<String, Object>> sourcesAccum) {
        // 连续重复工具调用追踪：判定模型陷入发散循环（同一工具反复调用但无实质收敛）时强制结束
        String lastToolName = null;
        String lastToolArgsNormalized = null;
        int repeatCount = 0;
        // 连续知识检索无相关结果计数：达到 RAG_MISS_LIMIT 强制收敛（P1-2）
        int ragMissCount = 0;
        // 工具循环起始时间：总时间预算超时强制收敛（P2）
        long loopStartMs = System.currentTimeMillis();
        int effectiveMax = effectiveMaxIterations();
        for (int i = 0; i < effectiveMax; i++) {
            // 客户端断连后终止工具循环，避免继续消耗模型token
            if (sink.isCancelled()) {
                log.info("SSE已取消，终止工具调用循环");
                return;
            }
            // 总时间预算超时：强制收敛进入流式阶段（P2，防慢模型拖满 SSE 5 分钟超时后被动中断）
            long elapsedMs = System.currentTimeMillis() - loopStartMs;
            if (elapsedMs > TOOL_LOOP_BUDGET_MS) {
                log.warn("工具循环超时（{}ms > 预算 {}ms），强制收敛进入流式阶段", elapsedMs, TOOL_LOOP_BUDGET_MS);
                injectConvergenceMessage(messages);
                return;
            }
            // 已注入收敛指令（模型陷入重复调用被强制收尾），直接结束工具循环
            if (hasConvergedMessage(messages)) {
                log.info("已注入收敛指令，结束工具调用循环");
                return;
            }
            // 多级降级：按 order 0..N 顺序 for 循环重试，每级仅一次，总重试 N 次，不循环，全部失败抛最后异常
            ChatResponse response = null;
            Exception lastChatException = null;
            List<ChatModel> orderedModels = aiModelProvider.getChatModels();
            // 若有序列表为空或仅主模型，直接使用传入模型兼容
            if (orderedModels == null || orderedModels.isEmpty()) {
                orderedModels = List.of(chatModel);
            }
            for (int m = 0; m < orderedModels.size(); m++) {
                ChatModel curModel = orderedModels.get(m);
                OpenAiChatOptions curOptions = buildToolOptionsForModel(curModel, toolCallbacks, options);
                try {
                    response = curModel.call(new Prompt(messages, curOptions));
                    break;
                } catch (Exception e) {
                    lastChatException = e;
                    // 取消/不可重试守卫：与 ModelRouter.streamWithFallbackInternal 同策略（P0-2）
                    // ModelRouter.isCancel/isRetryable 为同包静态方法，可直接复用，无需放开可见性或抽公共 ErrorClassifier @author H
                    if (ModelRouter.isCancel(e)) {
                        log.info("工具阶段检测到取消，直接中止不再降级");
                        throw e instanceof RuntimeException re ? re : new RuntimeException(e);
                    }
                    if (!ModelRouter.isRetryable(e)) {
                        log.info("工具阶段不可重试异常，直接透传不再降级 cause={}", e.toString());
                        throw e instanceof RuntimeException re ? re : new RuntimeException(e);
                    }
                    if (m == orderedModels.size() - 1) {
                        break;
                    }
                    ChatModel next = orderedModels.get(m + 1);
                    log.warn("fallback to level {}/{} {}", m, m + 1, getModelName(next), e);
                }
            }
            if (response == null) {
                if (lastChatException instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(lastChatException);
            }
            AssistantMessage assistantMsg = response.getResult().getOutput();

            // 提取推理过程：兼容 reasoning_content / reasoningContent / thinking / reasoning 等键
            // 网关可能在每次 call 响应中重放已见的完整 thinking，用 dedupThinkingDelta 仅取新增后缀，避免落库与推送翻倍
            String rawThinking = stageSupport.extractThinking(response);
            String thinkingChunk = ThinkingStageSupport.dedupThinkingDelta(
                    thinkingAccum != null && thinkingAccum.length() > 0 ? thinkingAccum.toString() : null,
                    rawThinking);
            if (thinkingChunk != null && !thinkingChunk.isEmpty()) {
                // 追问提示词泄漏防护：长度 >500 且含“你是HAgent追问助手”直接过滤不推送不落库 @author H
                if (ThinkingStageSupport.isFollowupPromptLeak(thinkingChunk)) {
                    log.warn("工具阶段 thinking 追问提示词泄漏已过滤: length={} iter={}", thinkingChunk.length(), i + 1);
                } else {
                    if (thinkingAccum != null) {
                        thinkingAccum.append(thinkingChunk);
                    }
                    stageSupport.emitThinkingToSink(sink, thinkingChunk);
                    log.debug("工具阶段思考内容已实时发射: length={}, iter={}", thinkingChunk.length(), i + 1);
                }
            }

            // 无工具调用 = 最终回复，不加入messages，交给stream阶段生成
            if (assistantMsg.getToolCalls() == null || assistantMsg.getToolCalls().isEmpty()) {
                // 兜底：部分模型会以 <tool_call> 文本格式输出工具调用（而非原生 tool_calls），
                // 若不处理，这段XML会泄漏为最终回答。此处解析并按原生协议执行
                String text = assistantMsg.getText() == null ? "" : assistantMsg.getText();
                if (text.contains(TEXT_TOOL_CALL_MARKER) && i < effectiveMax - 1) {
                    AssistantMessage.ToolCall synthetic = parseTextToolCall(text);
                    if (synthetic != null) {
                        // 归一化当前参数并判定与上次是否相似（相似度>80%视为重复）
                        String currentNormalized = normalizeArgs(synthetic.arguments());
                        boolean similar = lastToolName != null
                                && lastToolName.equals(synthetic.name())
                                && isSimilarArgs(lastToolArgsNormalized, currentNormalized);
                        repeatCount = similar ? repeatCount + 1 : 1;
                        lastToolName = synthetic.name();
                        lastToolArgsNormalized = currentNormalized;
                        if (repeatCount >= REPEAT_TOOL_LIMIT) {
                            log.warn("检测到连续重复工具调用: {}（相似参数），强制收敛终止工具循环，repeatCount={}", synthetic.name(), repeatCount);
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
                        // 根据工具名推送对应阶段标题（前端据 stage 动态切换思考标题）
                        stageSupport.emitStageForTool(sink, synthetic.name());
                        String result = executeTool(toolCallbacks, synthetic.name(), synthetic.arguments());
                        // RAG工具结果后组装来源并推送 source 事件（与 thinking 同级结构化事件）
                        if (ThinkingStageSupport.RAG_TOOL_NAME.equals(synthetic.name())) {
                            emitSourceIfAvailable(sink, sourcesAccum);
                        }
                        messages.add(ToolResponseMessage.builder()
                                .responses(List.of(new ToolResponseMessage.ToolResponse(
                                        synthetic.id(), synthetic.name(), truncateResult(result))))
                                .build());
                        // 连续知识检索无相关结果：达到阈值强制收敛（P1-2）
                        ragMissCount = updateRagMissCount(ragMissCount, synthetic.name(), result);
                        if (ragMissCount >= RAG_MISS_LIMIT) {
                            log.warn("连续 {} 次知识检索无相关结果，强制收敛引导直接回答", ragMissCount);
                            injectConvergenceMessage(messages);
                            return;
                        }
                        continue;
                    }
                }
                log.info("工具调用阶段结束，无更多工具调用，迭代次数={}", i + 1);
                break;
            }

            // 仅含工具调用的assistant消息才加入（保持调用链完整）
            messages.add(assistantMsg);

            log.info("工具调用迭代 {}: {} 次工具调用", i + 1, assistantMsg.getToolCalls().size());

            for (AssistantMessage.ToolCall toolCall : assistantMsg.getToolCalls()) {
                // 归一化当前参数并与上次对比，相似度>80%视为重复
                String currentNormalized = normalizeArgs(toolCall.arguments());
                boolean similar = lastToolName != null
                        && lastToolName.equals(toolCall.name())
                        && isSimilarArgs(lastToolArgsNormalized, currentNormalized);
                repeatCount = similar ? repeatCount + 1 : 1;
                lastToolName = toolCall.name();
                lastToolArgsNormalized = currentNormalized;
                if (repeatCount >= REPEAT_TOOL_LIMIT) {
                    log.warn("检测到连续重复工具调用: {}（相似参数），强制收敛终止工具循环，repeatCount={}", toolCall.name(), repeatCount);
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
                stageSupport.emitStageForTool(sink, toolCall.name());
                String result = executeTool(toolCallbacks, toolCall.name(), toolCall.arguments());
                // 截断过长的工具结果，防止撑爆上下文
                String truncatedResult = truncateResult(result);
                log.info("  工具结果: length={}, 截断后length={}",
                        result != null ? result.length() : 0,
                        truncatedResult.length());
                // RAG工具结果后组装来源并推送 source 事件（结构化JSON，前端据 type=source 渲染角标/抽屉）
                if (ThinkingStageSupport.RAG_TOOL_NAME.equals(toolCall.name())) {
                    emitSourceIfAvailable(sink, sourcesAccum);
                }

                messages.add(ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                toolCall.id(), toolCall.name(), truncatedResult
                        )))
                        .build());

                // 连续知识检索无相关结果：达到阈值先回填本次工具结果保持协议链完整，再注入收敛指令终止循环（P1-2）
                ragMissCount = updateRagMissCount(ragMissCount, toolCall.name(), truncatedResult);
                if (ragMissCount >= RAG_MISS_LIMIT) {
                    log.warn("连续 {} 次知识检索无相关结果，强制收敛引导直接回答", ragMissCount);
                    injectConvergenceMessage(messages);
                    return;
                }
            }
        }
        // 循环结束后（无论自然退出还是达到上限），确保流式阶段始终带有收敛指令，避免模型再以文本形式输出工具调用
        if (!hasConvergedMessage(messages)) {
            log.info("工具调用循环结束，注入收敛指令以保障流式阶段稳定收敛");
            injectConvergenceMessage(messages);
        }
    }

    /**
     * 注入收敛指令：告知模型工具调用阶段已结束，基于已有信息直接作答，禁止再发起任何工具调用
     *
     * @param messages 对话消息列表
     */
    private void injectConvergenceMessage(List<Message> messages) {
        if (hasConvergedMessage(messages)) {
            return;
        }
        messages.add(new org.springframework.ai.chat.messages.SystemMessage(CONVERGE_INSTRUCTION));
    }

    /**
     * 更新知识检索连续未命中计数
     * searchKnowledge 返回以 RAG_MISS_MARKER 开头的空结果提示时计数+1；
     * 命中内容或调用其他工具均重置为 0（仅"连续"未命中才收敛，避免误伤正常多轮检索场景）
     *
     * @param ragMissCount 当前计数
     * @param toolName     工具名
     * @param result       工具结果文本
     * @return 更新后的计数
     * @author H
     */
    private int updateRagMissCount(int ragMissCount, String toolName, String result) {
        if (ThinkingStageSupport.RAG_TOOL_NAME.equals(toolName)
                && result != null && result.startsWith(RAG_MISS_MARKER)) {
            return ragMissCount + 1;
        }
        return 0;
    }

    /**
     * 判断消息列表中是否已包含收敛指令
     *
     * @param messages 对话消息列表
     * @return 是否已包含收敛指令
     */
    private boolean hasConvergedMessage(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        for (Message msg : messages) {
            if (msg instanceof org.springframework.ai.chat.messages.SystemMessage sm) {
                String text = sm.getText();
                if (text != null && (text.contains(CONVERGE_INSTRUCTION) || text.contains("工具调用阶段已结束"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 归一化工具参数：转小写、去首尾空格、移除标点与空白字符
     * 用于重复调用的相似度判定
     *
     * @param args 原始参数 JSON 字符串
     * @return 归一化后的字符串
     */
    private String normalizeArgs(String args) {
        if (args == null) {
            return "";
        }
        return args.toLowerCase().trim().replaceAll("[\\p{Punct}\\s]+", "");
    }

    /**
     * 判断两次归一化参数是否相似（相似度>80%）
     * 策略：contains 互包含 或 Levenshtein 距离 < 20% 长度
     *
     * @param lastArgs 上次归一化参数
     * @param curArgs  当前归一化参数
     * @return 是否相似
     */
    private boolean isSimilarArgs(String lastArgs, String curArgs) {
        if (lastArgs == null || curArgs == null) {
            return false;
        }
        if (lastArgs.isEmpty() && curArgs.isEmpty()) {
            return true;
        }
        if (lastArgs.isEmpty() || curArgs.isEmpty()) {
            return false;
        }
        // 互包含判定：若一个包含另一个，视为高度相似
        if (lastArgs.contains(curArgs) || curArgs.contains(lastArgs)) {
            return true;
        }
        // Levenshtein 距离判定：距离小于较长字符串长度的20%则相似度>80%
        int maxLen = Math.max(lastArgs.length(), curArgs.length());
        if (maxLen == 0) {
            return true;
        }
        int distance = levenshteinDistance(lastArgs, curArgs);
        double similarity = 1.0 - ((double) distance / maxLen);
        return similarity > 0.8;
    }

    /**
     * 计算 Levenshtein 编辑距离
     *
     * @param a 字符串 a
     * @param b 字符串 b
     * @return 编辑距离
     */
    private int levenshteinDistance(String a, String b) {
        int n = a.length();
        int m = b.length();
        // 优化：交换使 m 更小以减少空间
        if (n < m) {
            String tmp = a;
            a = b;
            b = tmp;
            n = a.length();
            m = b.length();
        }
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[m];
    }

    /**
     * 剥离流式阶段被拆分到多个 token 的文本工具调用块
     * 模型有时会在流式输出中以 XML 文本形式再次表达工具意图（如 <tool_call><function=...>...），
     * 该块会被 tokenizer 拆分成多条增量逐片到达，单 token 正则无法整块命中，需要按状态机累积后整体剥离。
     * 国产网关常把标签本身切碎：非块内保留 trailing 疑似区（尾部疑似起始标记前缀暂存 buf 待下一分片联合判定），
     * 块内用 buf+chunk 联合搜索结束标记，避免碎片泄漏为正文或 inBlock 永久为 true 吞掉后续正文。
     *
     * @param chunk   当前 token 的文本增量
     * @param inBlock 是否正处于 <tool_call> 块内（跨 token 状态）
     * @param buf     跨 token 缓冲：块内累积块内容，非块内暂存 trailing 疑似前缀
     * @return 剥离工具调用块后的残留文本（正常正文原样保留）
     * @author H
     */
    public static String stripToolCallBlock(String chunk, boolean[] inBlock, StringBuilder buf) {
        // 跨分片联合：先将上轮暂存的前缀疑似区/块内累积与本轮拼接后再扫描
        String data = chunk;
        if (buf.length() > 0) {
            data = buf.toString() + chunk;
            buf.setLength(0);
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < data.length()) {
            if (inBlock[0]) {
                // 正在工具调用块内：用联合串查找结束标记
                int end = data.indexOf(STRIP_END_MARKER, i);
                if (end < 0) {
                    // 本轮未见完整结束标记（含结束标记被切成两半），整体累积后等待下一个分片
                    buf.append(data.substring(i));
                    return out.toString();
                }
                // 块内容全部丢弃（含被切碎后经联合才完整的块）
                inBlock[0] = false;
                i = end + STRIP_END_MARKER.length();
            } else {
                // 非块内：查找开始标记
                int start = data.indexOf(STRIP_START_MARKER, i);
                if (start < 0) {
                    // 无完整开始标记：尾部可能是被切碎的起始标记前缀（如以 <tool_ 结尾），暂存待下一分片联合判定
                    String rest = data.substring(i);
                    int hold = trailingMarkerPrefixLen(rest, STRIP_START_MARKER);
                    out.append(rest, 0, rest.length() - hold);
                    buf.append(rest.substring(rest.length() - hold));
                    return out.toString();
                }
                // 开始标记之前的正文保留，进入块内状态，跳过起始标记本身
                out.append(data, i, start);
                inBlock[0] = true;
                i = start + STRIP_START_MARKER.length();
            }
        }
        return out.toString();
    }

    /**
     * 计算文本尾部与标记头部的最长前后缀重合长度
     * 用于跨分片场景：尾部疑似被切碎的标记前缀时暂不输出，待下一分片到达后联合判定
     *
     * @param text   待检查文本
     * @param marker 标记（如 <tool_call）
     * @return 重合长度（0 表示无疑似前缀）
     * @author H
     */
    private static int trailingMarkerPrefixLen(String text, String marker) {
        int max = Math.min(text.length(), marker.length() - 1);
        for (int k = max; k >= 1; k--) {
            if (text.regionMatches(text.length() - k, marker, 0, k)) {
                return k;
            }
        }
        return 0;
    }

    /**
     * 剥离流式正文中的 think 标签（推理模型在正文通道泄漏的思考标记）
     * 两种情况：① 内联 <think>...</think> 成对出现（可能被拆分到多个 token）——思考内容不属于正文，整块抑制；
     * ② 孤儿 </think>（开标签已被网关抽到 reasoning_content 通道时常见）——仅剥离标记本身，正文保留。
     * 跨分片处理与 stripToolCallBlock 同构：块内累积等待结束标记，非块内暂存尾部疑似前缀待下一分片联合判定。
     *
     * @param chunk   当前 token 的文本增量
     * @param inThink 是否正处于 <think> 块内（跨 token 状态）
     * @param buf     跨 token 缓冲：块内累积内容，非块内暂存尾部疑似前缀
     * @return 剥离后的正文增量
     * @author H
     */
    public static String stripThinkTags(String chunk, boolean[] inThink, StringBuilder buf) {
        // 跨分片联合：先将上轮暂存的疑似前缀/块内累积与本轮拼接后再扫描
        String data = chunk;
        if (buf.length() > 0) {
            data = buf.toString() + chunk;
            buf.setLength(0);
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < data.length()) {
            if (inThink[0]) {
                int end = data.indexOf(THINK_CLOSE_MARKER, i);
                if (end < 0) {
                    // 块内未见完整结束标记（含被切碎的情况），整体累积后等待下一个分片
                    buf.append(data.substring(i));
                    return out.toString();
                }
                // 思考内容整体丢弃（含结束标记本身）
                inThink[0] = false;
                i = end + THINK_CLOSE_MARKER.length();
            } else {
                int open = data.indexOf(THINK_OPEN_MARKER, i);
                int close = data.indexOf(THINK_CLOSE_MARKER, i);
                if (open >= 0 && (close < 0 || open < close)) {
                    // 成对 think 块开始：保留块前正文，进入块内抑制状态
                    out.append(data, i, open);
                    inThink[0] = true;
                    i = open + THINK_OPEN_MARKER.length();
                } else if (close >= 0) {
                    // 孤儿 </think>（正文通道无配对开标签）：仅剥离标记，正文保留
                    out.append(data, i, close);
                    i = close + THINK_CLOSE_MARKER.length();
                } else {
                    // 无标记：尾部可能是被切碎的标记前缀（如 </thi），暂存待下一分片联合判定
                    String rest = data.substring(i);
                    int hold = Math.max(
                            trailingMarkerPrefixLen(rest, THINK_OPEN_MARKER),
                            trailingMarkerPrefixLen(rest, THINK_CLOSE_MARKER));
                    out.append(rest, 0, rest.length() - hold);
                    if (hold > 0) {
                        buf.append(rest.substring(rest.length() - hold));
                    }
                    return out.toString();
                }
            }
        }
        return out.toString();
    }

    /**
     * 判定流式最终回复是否为"意图声明残段"
     * 推理模型在无工具定义的流式阶段可能重新得出"应先检索知识库"的结论，随后仅输出
     * "我需要先检索一下知识库中关于 X 的信息。"之类的意图声明即停止作答（该阶段无工具可调，检索永远无法兑现）。
     * 形态特征：整体很短且含检索/查询意图短语。命中后由调用方注入纠正指令并重流一次兜底 @author H
     *
     * @param text 流式最终回复全文
     * @return true 表示为意图声明残段，需要纠正性重流
     */
    public static boolean isIntentStub(String text) {
        if (text == null) {
            return false;
        }
        String t = text.strip();
        if (t.isEmpty() || t.length() >= INTENT_STUB_MAX_LEN) {
            return false;
        }
        return t.contains("先检索") || t.contains("先搜索") || t.contains("先查询") || t.contains("先查一下")
                || t.contains("检索一下") || t.contains("搜索一下") || t.contains("查询一下") || t.contains("查一下知识库")
                || t.contains("需要检索") || t.contains("需要搜索") || t.contains("需要查询")
                || t.contains("先从知识库") || t.contains("检索知识库") || t.contains("搜索知识库") || t.contains("查询知识库");
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
     * 若存在RAG命中则推送 source 事件并累积到 sourcesAccum
     * 去重：按 file_id+chunk_id 排重，避免同一片段多次推送
     *
     * @param sink         SSE sink
     * @param sourcesAccum 累积器
     */
    private void emitSourceIfAvailable(FluxSink<String> sink, List<Map<String, Object>> sourcesAccum) {
        try {
            List<Map<String, Object>> hits = ragService.getLastHits();
            if (hits == null || hits.isEmpty()) {
                log.debug("RAG命中为空，跳过source推送");
                return;
            }
            // 组装本次推送列表，去重后追加到累积器
            List<Map<String, Object>> toPush = new ArrayList<>();
            for (Map<String, Object> hit : hits) {
                boolean exists = sourcesAccum.stream().anyMatch(existing ->
                        java.util.Objects.equals(existing.get("file_id"), hit.get("file_id"))
                                && java.util.Objects.equals(existing.get("chunk_id"), hit.get("chunk_id")));
                if (!exists) {
                    sourcesAccum.add(hit);
                    toPush.add(hit);
                }
            }
            if (toPush.isEmpty()) {
                log.debug("RAG命中均已推送，跳过重复");
                return;
            }
            if (sink.isCancelled()) {
                log.debug("SSE已取消，跳过source推送");
                return;
            }
            sink.next("{\"type\":\"source\",\"chunks\":" + objectMapper.writeValueAsString(toPush) + "}");
            log.info("已推送source事件: chunks={}, totalAccum={}", toPush.size(), sourcesAccum.size());
        } catch (Exception e) {
            log.warn("推送source事件失败", e);
        } finally {
            // 本次命中已消费，清理 ThreadLocal 避免下一轮误复用
            try {
                ragService.clearLastHits();
            } catch (Exception e) {
                log.warn("清理RAG命中缓存失败", e);
            }
        }
    }

    /**
     * 过滤幻觉工具调用标签残留（流式正文清理用）
     *
     * @param text 正文增量
     * @return 清理后的文本
     */
    public static String filterHallucinatedTags(String text) {
        String filtered = HALLUCINATED_TOOL_CALL_PATTERN.matcher(text).replaceAll("");
        return HALLUCINATED_FUNCTION_PATTERN.matcher(filtered).replaceAll("");
    }
}
