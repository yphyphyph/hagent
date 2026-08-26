package com.hagent.ai.service.impl;

import com.hagent.ai.core.RagService;
import com.hagent.common.service.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Override
    public void addDocuments(String fileId, String fileName, List<String> chunks) {
        // 统一元数据键：file_id、file_name、chunk_index（与 RagService.retainHitMetadata 保持一致）
        if (fileName == null || fileName.isBlank()) {
            log.warn("向量化时 fileName 为空: fileId={}, 已保留 file_id 兜底查询路径", fileId);
        }
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> metadata = new HashMap<>();
            // 关键键：保持与检索端一致，避免 Unknown file
            metadata.put("file_id", fileId);
            metadata.put("file_name", fileName);
            metadata.put("chunk_index", i);
            // 保留 source 仅作来源标识，不用于 file_name 解析主路径
            metadata.put("source", "knowledge");

            Document doc = new Document(
                    "file-" + fileId + "-chunk-" + i,
                    chunks.get(i),
                    metadata
            );
            documents.add(doc);
        }

        ragService.addDocuments(documents);
        log.info("已添加文档到向量存储: fileId={}, fileName={}, chunks={}, metadataKeys=[file_id, file_name, chunk_index]",
                fileId, fileName, chunks.size());
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
}
