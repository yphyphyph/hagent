package com.hagent.ai.service.impl;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.hagent.ai.core.AiModelProvider;
import com.hagent.ai.core.OpenCodeGatewayHeaders;
import com.hagent.ai.core.RagService;
import com.hagent.ai.core.UserContextHolder;
import com.hagent.ai.model.dto.AiToolRequest;
import com.hagent.ai.service.AiToolService;
import com.hagent.ai.service.TextToSqlService;
import com.hagent.knowledge.entity.KnowledgeFile;
import com.hagent.knowledge.mapper.KnowledgeFileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 智能工具服务实现
 * <p>
 * 按 toolKey 装配系统提示词并调用大模型流式输出，或结合 RAG/Text-to-SQL 能力：
 * - writing：文章写作助手（主题/风格/字数）
 * - seo：SEO 优化建议（针对内容）
 * - rag_qa：知识库问答（检索 + 引用出处 + 流式回答）
 * - text2sql：自然语言问数（返回 SQL 与结果）
 * - report：运营报告（问数结果 + AI 总结）
 *
 * @author H
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiToolServiceImpl implements AiToolService {

    private final AiModelProvider aiModelProvider;
    private final RagService ragService;
    private final TextToSqlService textToSqlService;
    private final ObjectMapper objectMapper;

    /** 知识库文件 Mapper（可选注入，用于 file_name 兜底） */
    @Autowired(required = false)
    @Lazy
    private KnowledgeFileMapper knowledgeFileMapper;

    /** RAG 检索 TopK */
    private static final int RAG_TOP_K = 5;
    /** RAG 相似度阈值 */
    private static final double RAG_THRESHOLD = 0.5;
    /** RAG 上下文单段最大字符数 */
    private static final int RAG_SEGMENT_MAX = 800;

    /** chunk_id 前缀解析正则：file-<fileId>-chunk-<index> */
    private static final Pattern CHUNK_ID_PATTERN = Pattern.compile("^file-(.+?)-chunk-.*$");

    @Override
    public List<Map<String, Object>> listTools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(tool("writing", "文章写作助手", "根据主题、风格、字数生成文章草稿", "writing",
                List.of(
                        input("topic", "主题", "text", true),
                        input("style", "风格", "text", false),
                        input("length", "字数", "number", false)
                )));
        tools.add(tool("seo", "SEO 优化建议", "为文章内容生成标题优化、关键词布局与标签建议", "seo",
                List.of(input("content", "文章内容", "textarea", true))));
        tools.add(tool("rag_qa", "知识库问答", "基于知识库检索并回答问题，附引用出处", "knowledge",
                List.of(input("question", "问题", "text", true))));
        tools.add(tool("text2sql", "自然语言问数", "用自然语言查询业务数据（返回 SQL 与结果）", "analysis",
                List.of(input("question", "问题", "text", true))));
        tools.add(tool("report", "运营报告", "基于自然语言问数结果生成运营分析报告", "analysis",
                List.of(input("question", "分析目标", "text", true))));
        return tools;
    }

    @Override
    public Flux<String> execute(Long userId, AiToolRequest request) {
        if (request == null || !StringUtils.hasText(request.getToolKey())) {
            return Flux.just(wrapError("缺少工具标识 toolKey"));
        }
        Map<String, Object> params = request.getParams() != null ? request.getParams() : Map.of();
        try {
            return switch (request.getToolKey()) {
                case "writing" -> writing(params, request.getPromptOverride());
                case "seo" -> seo(params, request.getPromptOverride());
                case "rag_qa" -> ragQa(params, request.getFileIds());
                case "text2sql" -> text2sql(params);
                case "report" -> report(params);
                default -> Flux.just(wrapError("未知工具: " + request.getToolKey()));
            };
        } catch (Exception e) {
            log.error("AI工具执行异常: toolKey={}", request.getToolKey(), e);
            return Flux.just(wrapError("工具执行失败，请稍后重试"));
        }
    }

    /**
     * 文章写作助手
     */
    private Flux<String> writing(Map<String, Object> params, String promptOverride) {
        String topic = str(params.get("topic"));
        if (!StringUtils.hasText(topic)) {
            return Flux.just(wrapError("请提供写作主题 topic"));
        }
        String style = StringUtils.hasText(str(params.get("style"))) ? str(params.get("style")) : "专业";
        String length = StringUtils.hasText(str(params.get("length"))) ? str(params.get("length")) : "800";
        String system = StringUtils.hasText(promptOverride) ? promptOverride
                : "你是一名资深内容创作者，擅长撰写结构清晰、逻辑连贯、有观点的文章。";
        String user = String.format(
                "请围绕主题「%s」，以%s的风格撰写一篇约%s字的文章，要求结构清晰、逻辑连贯、有观点，使用 Markdown 格式。",
                topic, style, length);
        return streamChat(system, user);
    }

    /**
     * SEO 优化建议
     */
    private Flux<String> seo(Map<String, Object> params, String promptOverride) {
        String content = str(params.get("content"));
        if (!StringUtils.hasText(content)) {
            return Flux.just(wrapError("请提供文章内容 content"));
        }
        String system = StringUtils.hasText(promptOverride) ? promptOverride
                : "你是一名 SEO 专家，请针对内容给出可执行的 SEO 优化建议。";
        String user = "请针对以下内容给出 SEO 优化建议，包括标题优化、关键词布局、摘要与标签建议，使用 Markdown 分点输出：\n\n"
                + content;
        return streamChat(system, user);
    }

    /**
     * 知识库 RAG 问答：检索 + 引用出处 + 流式回答（满足 P2-4）
     */
    private Flux<String> ragQa(Map<String, Object> params, List<String> fileIds) {
        String question = str(params.get("question"));
        if (!StringUtils.hasText(question)) {
            return Flux.just(wrapError("请提供问题 question"));
        }
        List<Document> docs = (fileIds != null && !fileIds.isEmpty())
                ? ragService.search(question, RAG_TOP_K, RAG_THRESHOLD, fileIds)
                : ragService.search(question, RAG_TOP_K, RAG_THRESHOLD);

        if (docs == null || docs.isEmpty()) {
            // 无检索结果：明确告知，不编造
            String meta = "{\"type\":\"citations\",\"data\":[]}";
            return Flux.just(meta, wrapToken("未在知识库中检索到相关内容，请补充资料或换一种问法。"), "[FINAL]");
        }

        // 组装上下文与引用（统一键 file_id/file_name/chunk_index，兼容旧键并支持 chunk_id 兜底）
        StringBuilder context = new StringBuilder();
        List<Map<String, Object>> citations = new ArrayList<>();
        // 样例日志：记录首条 Document 的实际 metadata keys，便于排查 Unknown file
        if (!docs.isEmpty()) {
            Document sample = docs.get(0);
            Map<String, Object> sampleMeta = sample.getMetadata() != null ? sample.getMetadata() : Map.of();
            log.info("ragQa 命中样例: docId={}, metadataKeys={}, metadata={}", sample.getId(), sampleMeta.keySet(), sampleMeta);
        }
        int idx = 1;
        for (Document doc : docs) {
            String text = doc.getText() != null ? doc.getText() : "";
            String segment = text.length() > RAG_SEGMENT_MAX ? text.substring(0, RAG_SEGMENT_MAX) + "..." : text;
            context.append("【出处").append(idx).append("】").append(segment).append("\n\n");
            Map<String, Object> meta = doc.getMetadata() != null ? doc.getMetadata() : Map.of();
            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("index", idx);
            // fileId：兼容 file_id/fileId/file-id，并支持从 chunk_id 解析
            Object fileId = firstNonNull(meta.get("file_id"), meta.get("fileId"), meta.get("file-id"), meta.get("fileID"));
            if (fileId == null || String.valueOf(fileId).isBlank()) {
                String parsed = extractFileIdFromChunkId(doc.getId());
                if (parsed != null) {
                    fileId = parsed;
                }
            }
            citation.put("fileId", fileId);
            // fileName：兼容 file_name/fileName，缺失时通过 KnowledgeFileMapper 兜底
            Object fileName = firstNonNull(meta.get("file_name"), meta.get("fileName"), meta.get("file-name"), meta.get("sourceFileName"));
            if (fileName == null || String.valueOf(fileName).isBlank()) {
                String fileIdStr = fileId != null ? String.valueOf(fileId) : extractFileIdFromChunkId(doc.getId());
                String resolved = resolveFileNameViaMapper(fileIdStr);
                if (resolved != null) {
                    fileName = resolved;
                    log.info("ragQa file_name 兜底成功: fileId={}, fileName={}", fileIdStr, resolved);
                }
            }
            citation.put("fileName", fileName);
            citation.put("snippet", segment.length() > 200 ? segment.substring(0, 200) + "..." : segment);
            citations.add(citation);
            idx++;
        }

        String system = "你是一名严谨的知识库助手。请仅依据给定的资料回答问题，"
                + "在答案中用【出处N】标注引用来源；若资料不足以回答，请明确说明。";
        String user = "资料如下：\n\n" + context + "\n用户问题：" + question;

        Flux<String> citationChunk = Flux.just(wrapCitations(citations));
        return citationChunk.concatWith(streamChat(system, user));
    }

    /**
     * 自然语言问数：返回 SQL 与结果（非流式，包装为单个结果块）
     */
    private Flux<String> text2sql(Map<String, Object> params) {
        String question = str(params.get("question"));
        if (!StringUtils.hasText(question)) {
            return Flux.just(wrapError("请提供问题 question"));
        }
        Map<String, Object> result = textToSqlService.queryByNaturalLanguage(question);
        return Flux.just(wrapResult(result), "[FINAL]");
    }

    /**
     * 运营报告：先问数，再由 AI 总结成报告（流式）
     */
    private Flux<String> report(Map<String, Object> params) {
        String question = str(params.get("question"));
        if (!StringUtils.hasText(question)) {
            return Flux.just(wrapError("请提供分析目标 question"));
        }
        Map<String, Object> data = textToSqlService.queryByNaturalLanguage(question);
        Flux<String> dataChunk = Flux.just(wrapResult(data));

        String dataJson;
        try {
            dataJson = objectMapper.writeValueAsString(data);
        } catch (JacksonException e) {
            dataJson = String.valueOf(data);
        }
        String system = "你是一名数据分析师，请基于查询结果生成结构化运营报告，包含关键发现、趋势解读与可执行建议。";
        String user = "分析目标：" + question + "\n\n查询结果（JSON）：\n" + dataJson
                + "\n\n请用 Markdown 输出报告。";
        return dataChunk.concatWith(streamChat(system, user));
    }

    /**
     * 通用流式对话：装配 system + user 提示词，逐 token 输出结构化 JSON
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return 流式响应
     */
    private Flux<String> streamChat(String systemPrompt, String userPrompt) {
        ChatModel chatModel = aiModelProvider.getChatModel();
        if (!(chatModel instanceof OpenAiChatModel openAiChatModel)) {
            log.error("当前 ChatModel 非 OpenAiChatModel 实现: {}",
                    chatModel != null ? chatModel.getClass().getName() : "null");
            return Flux.just(wrapError("AI服务配置异常，请稍后重试"));
        }
        OpenAiChatOptions base = openAiChatModel.getOptions();
        // 注入 opencode 网关会话头（缺失 400）；工具报表场景无会话上下文时回退实例级 UUID @author H
        OpenAiChatOptions options = OpenCodeGatewayHeaders.applyTo(OpenAiChatOptions.builder()
                .apiKey(base.getApiKey())
                .baseUrl(base.getBaseUrl())
                .model(base.getModel()), UserContextHolder.getConversationId())
                .build();

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(userPrompt));

        Flux<String> tokens = chatModel.stream(new Prompt(messages, options))
                .mapNotNull(response -> {
                    if (response.getResult() == null || response.getResult().getOutput() == null) {
                        return null;
                    }
                    String text = response.getResult().getOutput().getText();
                    return (text != null && !text.isEmpty()) ? wrapToken(text) : null;
                });
        return tokens.concatWith(Flux.just("[FINAL]"))
                .onErrorResume(e -> {
                    log.error("AI流式输出异常", e);
                    return Flux.just(wrapError("AI服务暂时不可用，请稍后重试"), "[FINAL]");
                });
    }

    // ---------- 工具描述辅助 ----------

    private Map<String, Object> tool(String key, String name, String desc, String category,
                                     List<Map<String, Object>> inputs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        m.put("description", desc);
        m.put("category", category);
        m.put("inputs", inputs);
        return m;
    }

    private Map<String, Object> input(String name, String label, String type, boolean required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("label", label);
        m.put("type", type);
        m.put("required", required);
        return m;
    }

    // ---------- JSON 包装辅助 ----------

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String wrapToken(String token) {
        try {
            return "{\"type\":\"token\",\"content\":" + objectMapper.writeValueAsString(token) + "}";
        } catch (JacksonException e) {
            return "{\"type\":\"token\",\"content\":\"\"}";
        }
    }

    private String wrapError(String message) {
        try {
            return "{\"type\":\"error\",\"message\":" + objectMapper.writeValueAsString(message) + "}";
        } catch (JacksonException e) {
            return "{\"type\":\"error\",\"message\":\"AI服务异常\"}";
        }
    }

    private String wrapResult(Map<String, Object> data) {
        try {
            return "{\"type\":\"result\",\"data\":" + objectMapper.writeValueAsString(data) + "}";
        } catch (JacksonException e) {
            return "{\"type\":\"error\",\"message\":\"结果序列化失败\"}";
        }
    }

    private String wrapCitations(List<Map<String, Object>> citations) {
        try {
            return "{\"type\":\"citations\",\"data\":" + objectMapper.writeValueAsString(citations) + "}";
        } catch (JacksonException e) {
            return "{\"type\":\"citations\",\"data\":[]}";
        }
    }

    /** 取首个非空值（兼容多键名） */
    private static Object firstNonNull(Object... values) {
        for (Object v : values) {
            if (v != null && !String.valueOf(v).isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** 从 chunk_id 前缀解析 file_id，形如 file-2075228410665365508-chunk-71 */
    private static String extractFileIdFromChunkId(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) {
            return null;
        }
        Matcher m = CHUNK_ID_PATTERN.matcher(chunkId);
        if (m.matches()) {
            return m.group(1);
        }
        return null;
    }

    /** 通过 KnowledgeFileMapper 兜底查询 file_name */
    private String resolveFileNameViaMapper(String fileIdStr) {
        if (fileIdStr == null || fileIdStr.isBlank() || knowledgeFileMapper == null) {
            return null;
        }
        try {
            long id = Long.parseLong(fileIdStr);
            KnowledgeFile kf = knowledgeFileMapper.selectById(id);
            return kf != null ? kf.getFileName() : null;
        } catch (Exception e) {
            log.warn("ragQa 兜底查询 file_name 失败: fileId={}", fileIdStr, e);
            return null;
        }
    }
}
