package com.hagent.ai.retrieval;

import com.hagent.ai.core.AiModelProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量检索通道（薄包装现有 PgVector + filterExpression 权限过滤）
 * <p>
 * 注意：VectorStore 必须经 AiModelProvider 动态获取（数据库 ai.* 配置构建，含真实 embedding 凭据），
 * 不能直接构造注入——容器内并无自定义 VectorStore bean，直接注入会落到 Spring AI 自动配置的
 * 占位实例（yml 中的 placeholder api-key），导致检索时 embedding 调用必然 404。
 *
 * @author H
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorSearchChannel implements SearchChannel {

    private final AiModelProvider aiModelProvider;

    @Override
    public String getName() {
        return "vector";
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.VECTOR;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return true;
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long start = System.currentTimeMillis();
        try {
            // 动态获取：与 RagService 同口径，走数据库配置构建的真实 PgVectorStore + embedding 模型
            VectorStore vectorStore = aiModelProvider.getVectorStore();
            if (vectorStore == null) {
                log.warn("向量通道跳过：VectorStore 不可用（模型未构建或构建失败）: query={}", context.getQuery());
                return emptyResult(System.currentTimeMillis() - start);
            }
            String filter = buildFilter(context);
            SearchRequest.Builder builder = SearchRequest.builder()
                    .query(context.getQuery())
                    .topK(context.getTopK())
                    .similarityThreshold(context.getThreshold());
            if (filter != null && !filter.isBlank()) {
                builder.filterExpression(filter);
            }
            List<Document> docs = vectorStore.similaritySearch(builder.build());
            List<RetrievedChunk> chunks = new ArrayList<>(docs.size());
            for (Document doc : docs) {
                chunks.add(RetrievedChunk.fromDocument(doc));
            }
            long latency = System.currentTimeMillis() - start;
            log.info("向量通道召回: topK={}, threshold={}, filter={}, chunks={}, latencyMs={}",
                    context.getTopK(), context.getThreshold(), filter, chunks.size(), latency);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.VECTOR)
                    .channelName(getName())
                    .chunks(chunks)
                    .latencyMs(latency)
                    .build();
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("向量通道检索失败: query={}, error={}", context.getQuery(), e.getMessage());
            return emptyResult(latency);
        }
    }

    /**
     * 权限过滤 + fileIds 叠加（与 RagService.searchAccessible/search(fileIds) 同口径）
     */
    private String buildFilter(SearchContext context) {
        List<String> parts = new ArrayList<>();
        // 权限：visibility == 1 || user_id == <id>
        if (context.getUserId() != null) {
            parts.add("(visibility == 1 || user_id == " + context.getUserId() + ")");
        } else {
            parts.add("visibility == 1");
        }
        // 限定文件：file_id == '...' || ...
        if (context.getFileIds() != null && !context.getFileIds().isEmpty()) {
            String fileFilter = context.getFileIds().stream()
                    .filter(id -> id != null && id.matches("^[a-zA-Z0-9_-]+$"))
                    .map(id -> "file_id == '" + id + "'")
                    .reduce((a, b) -> a + " || " + b)
                    .orElse("");
            if (!fileFilter.isBlank()) {
                parts.add("(" + fileFilter + ")");
            }
        }
        // 精确模式：锁定单一知识库文件（元数据 file_id 为字符串故带引号，与 fileIds 过滤同机制；
        // fileId 为 Long 数字串，无过滤表达式注入风险） @author H
        if (context.getFileId() != null) {
            parts.add("file_id == '" + context.getFileId() + "'");
        }
        return String.join(" && ", parts);
    }
}
