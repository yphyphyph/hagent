package com.hagent.ai.retrieval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 关键词检索通道（PG tsvector + GIN）
 * 单条 SQL：search_vector @@ plainto_tsquery('simple', :query) + ts_rank 排序 + 权限过滤
 *
 * @author H
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeywordSearchChannel implements SearchChannel {

    private final JdbcTemplate jdbcTemplate;
    private final HybridSearchProperties properties;

    @Override
    public String getName() {
        return "keyword";
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.KEYWORD;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        if (!properties.isEnabled()) {
            return false;
        }
        String query = context.getQuery();
        if (query == null || query.trim().length() < properties.getKeyword().getMinKeywordLength()) {
            return false;
        }
        return true;
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long start = System.currentTimeMillis();
        if (!isEnabled(context)) {
            return emptyResult(0);
        }

        try {
            List<RetrievedChunk> chunks = doKeywordSearch(context);
            long latency = System.currentTimeMillis() - start;
            log.info("关键词通道召回: query={}, topK={}, chunks={}, latencyMs={}",
                    context.getQuery(), context.getTopK(), chunks.size(), latency);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.KEYWORD)
                    .channelName(getName())
                    .chunks(chunks)
                    .latencyMs(latency)
                    .build();
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("关键词通道检索失败: query={}, error={}", context.getQuery(), e.getMessage());
            return emptyResult(latency);
        }
    }

    private List<RetrievedChunk> doKeywordSearch(SearchContext context) {
        int topK = Math.max(properties.getKeyword().getTopK(), context.getTopK());
        Long userId = context.getUserId();
        List<String> fileIds = context.getFileIds();

        // 构建权限条件（与向量通道同口径，在关键词 SQL 层各做一次）
        StringBuilder sql = new StringBuilder(
                "SELECT id, content, metadata, ts_rank(search_vector, plainto_tsquery('simple', ?)) AS score "
                        + "FROM vector_store "
                        + "WHERE search_vector @@ plainto_tsquery('simple', ?) "
        );
        List<Object> params = new ArrayList<>();
        params.add(context.getQuery());
        params.add(context.getQuery());

        // 权限过滤：visibility == 1 或 user_id == 当前用户
        if (userId != null) {
            sql.append("AND (metadata->>'visibility' = '1' OR metadata->>'user_id' = ?) ");
            params.add(String.valueOf(userId));
        } else {
            sql.append("AND metadata->>'visibility' = '1' ");
        }

        // 限定文件
        if (fileIds != null && !fileIds.isEmpty()) {
            List<String> sanitized = fileIds.stream()
                    .filter(id -> id != null && id.matches("^[a-zA-Z0-9_-]+$"))
                    .toList();
            if (!sanitized.isEmpty()) {
                sql.append("AND metadata->>'file_id' IN (");
                sql.append(String.join(",", sanitized.stream().map(s -> "?").toList()));
                sql.append(") ");
                params.addAll(sanitized);
            }
        }

        sql.append("ORDER BY score DESC LIMIT ?");
        params.add(topK);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());

        List<RetrievedChunk> chunks = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object idObj = row.get("id");
            Object contentObj = row.get("content");
            Object metadataObj = row.get("metadata");
            Object scoreObj = row.get("score");

            String id = idObj != null ? String.valueOf(idObj) : "";
            String content = contentObj != null ? String.valueOf(contentObj) : "";
            double score = 0.0;
            if (scoreObj instanceof Number n) {
                score = n.doubleValue();
            }

            Map<String, Object> metadata = new HashMap<>();
            if (metadataObj instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    metadata.put(String.valueOf(e.getKey()), e.getValue());
                }
            } else if (metadataObj != null) {
                // metadata 为 JSONB 字符串时，JdbcTemplate 可能返回 String，需解析
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(String.valueOf(metadataObj), Map.class);
                    metadata = parsed;
                } catch (Exception ex) {
                    log.debug("metadata JSON 解析失败，跳过: {}", ex.getMessage());
                }
            }

            org.springframework.ai.document.Document doc = new org.springframework.ai.document.Document(
                    id, content, metadata);

            chunks.add(RetrievedChunk.builder()
                    .id(id)
                    .content(content)
                    .metadata(metadata)
                    .score(score)
                    .document(doc)
                    .build());
        }

        return chunks;
    }
}
