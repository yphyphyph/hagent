package com.hagent.ai.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 精排服务（本地直通）
 * 纯本地直通实现，不依赖阿里百炼 Rerank，直接返回原序
 *
 * @author H
 */
@Slf4j
@Service
public class RerankingService {

    /**
     * 本地直通精排
     * 不调用外部服务，直接返回原文档列表
     *
     * @param query 查询文本
     * @param docs  待重排文档
     * @return 原序文档
     */
    public List<Document> rerank(String query, List<Document> docs) {
        return docs;
    }
}
