package com.hagent.ai.service.impl;

import com.hagent.ai.core.RagService;
import org.springframework.jdbc.core.JdbcTemplate;
import com.hagent.common.service.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 向量存储服务实现
 * 使用RagService进行文档向量化存储和检索
 *
 * @author H
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class VectorStoreServiceImpl implements VectorStoreService {

    private final RagService ragService;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void addDocuments(String fileId, String fileName, List<String> chunks) {
        // 旧签名兜底：按公有处理
        addDocuments(fileId, fileName, 1, null, chunks);
    }

    @Override
    public void addDocuments(String fileId, String fileName, Integer visibility, Long userId, List<String> chunks) {
        // 权限入向量库（第一档）：visibility/user_id 写入 metadata JSONB，检索侧原生 filterExpression
        if (fileName == null || fileName.isBlank()) {
            log.warn("向量化时 fileName 为空: fileId={}, 已保留 file_id 兜底查询路径", fileId);
        }
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("file_id", fileId);
            metadata.put("file_name", fileName);
            metadata.put("chunk_index", i);
            // 权限字段入向量库（PG 第一档：原生过滤、同库 jsonb_set 同步）
            metadata.put("visibility", visibility == null ? 1 : visibility);
            if (userId != null) {
                metadata.put("user_id", userId);
            }
            metadata.put("source", "knowledge");

            // PG vector_store.id 为 uuid 类型，旧字符串 id 会触发 PgVectorStore.convertIdToPgType 报 Invalid UUID
            // 使用确定性 UUID（同 file 同 chunk 生成同 id），便于幂等覆盖与 deleteByFileId 后的可重现性
            String rawId = "file-" + fileId + "-chunk-" + i;
            String docId = UUID.nameUUIDFromBytes(rawId.getBytes(StandardCharsets.UTF_8)).toString();
            Document doc = new Document(
                    docId,
                    chunks.get(i),
                    metadata
            );
            documents.add(doc);
        }

        ragService.addDocuments(documents);
        log.info("已添加文档到向量存储: fileId={}, fileName={}, chunks={}, visibility={}, userId={}",
                fileId, fileName, chunks.size(), visibility, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> search(String query, int topK) {
        List<Document> documents = ragService.search(query, topK, 0.0);

        List<Map<String, Object>> results = new ArrayList<>();
        for (Document doc : documents) {
            Map<String, Object> item = new HashMap<>();
            item.put("content", doc.getText());
            item.put("fileId", doc.getMetadata().get("file_id"));
            item.put("score", doc.getMetadata().get("distance"));
            results.add(item);
        }

        return results;
    }

    @Override
    public void deleteByFileId(String fileId) {
        ragService.deleteByFileId(fileId);
        log.info("已从向量存储删除文件: fileId={}", fileId);
    }

    @Override
    public void updateMetadataByFileId(String fileId, Map<String, Object> patch) {
        if (fileId == null || fileId.isEmpty() || patch == null || patch.isEmpty()) {
            return;
        }
        try {
            // 同库 jsonb_set 同步（切换公私即时生效，无需重嵌入）
            for (Map.Entry<String, Object> entry : patch.entrySet()) {
                String key = entry.getKey();
                Object val = entry.getValue();
                if (!"visibility".equals(key) && !"user_id".equals(key)) {
                    continue;
                }
                int updated;
                if ("visibility".equals(key)) {
                    String sql = "UPDATE vector_store SET metadata = jsonb_set(metadata, '{visibility}', to_jsonb(?::int)::jsonb) WHERE metadata->>'file_id' = ?";
                    updated = jdbcTemplate.update(sql, String.valueOf(val), fileId);
                } else {
                    String sql = "UPDATE vector_store SET metadata = jsonb_set(metadata, '{user_id}', to_jsonb(?::bigint)::jsonb) WHERE metadata->>'file_id' = ?";
                    updated = jdbcTemplate.update(sql, String.valueOf(val), fileId);
                }
                log.info("vector_store 元数据已同步: fileId={}, key={}, value={}, updatedRows={}", fileId, key, val, updated);
            }
        } catch (Exception e) {
            log.error("vector_store 元数据同步失败: fileId={}, patch={}", fileId, patch, e);
            throw e;
        }
    }
}
