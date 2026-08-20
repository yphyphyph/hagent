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
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("file_id", fileId);
            metadata.put("file_name", fileName);
            metadata.put("chunk_index", i);
            metadata.put("source", "knowledge");

            Document doc = new Document(
                    "file-" + fileId + "-chunk-" + i,
                    chunks.get(i),
                    metadata
            );
            documents.add(doc);
        }

        ragService.addDocuments(documents);
        log.info("已添加文档到向量存储: fileId={}, chunks={}", fileId, chunks.size());
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
