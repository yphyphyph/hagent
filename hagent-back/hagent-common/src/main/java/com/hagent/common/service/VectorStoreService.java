package com.hagent.common.service;

import java.util.List;
import java.util.Map;

/**
 * 向量存储服务接口
 * 用于知识库文档的向量化存储和检索
 */
public interface VectorStoreService {

    /**
     * 添加文档到向量存储
     *
     * @param fileId    文件ID
     * @param fileName  文件名
     * @param chunks    文本块列表
     */
    void addDocuments(String fileId, String fileName, List<String> chunks);

    /**
     * 搜索相似文档
     *
     * @param query 搜索文本
     * @param topK  返回数量
     * @return 搜索结果列表，每个结果包含 content、fileId、score
     */
    List<Map<String, Object>> search(String query, int topK);

    /**
     * 按文件ID删除向量
     *
     * @param fileId 文件ID
     */
    void deleteByFileId(String fileId);
}
