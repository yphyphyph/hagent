package com.hagent.knowledge.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hagent.common.PageResult;
import com.hagent.knowledge.dto.KnowledgeQueryRequest;
import com.hagent.knowledge.entity.KnowledgeFile;
import com.hagent.knowledge.vo.KnowledgeFileVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 知识库服务接口，用于文档管理
 *
 * @author H
 */
public interface KnowledgeService extends IService<KnowledgeFile> {

    /**
     * 上传文档到知识库
     *
     * @param file 上传的文件
     * @return 文件VO
     */
    KnowledgeFileVO uploadDocument(MultipartFile file);

    /**
     * 上传文档到知识库（指定可见性）
     *
     * @param file       上传的文件
     * @param visibility 可见性(0=私有 1=公有)，null 按公有处理
     * @return 文件VO
     */
    KnowledgeFileVO uploadDocument(MultipartFile file, Integer visibility);

    /**
     * 切换文件可见性
     * 公私属性变更后自动重新向量化，使向量元数据与库表保持一致
     *
     * @param id         文件ID
     * @param visibility 可见性(0=私有 1=公有)
     * @return 更新后的文件VO
     */
    KnowledgeFileVO updateVisibility(Long id, Integer visibility);

    /**
     * 分页查询知识库文件（带筛选）
     *
     * @param request 查询参数
     * @return 分页结果
     */
    PageResult<KnowledgeFileVO> listFiles(KnowledgeQueryRequest request);

    /**
     * 删除知识库文件及其OSS对象
     *
     * @param id 文件ID
     */
    void deleteFile(Long id);

    /**
     * 获取文件的向量处理状态
     *
     * @param id 文件ID
     * @return 包含状态的文件VO
     */
    KnowledgeFileVO getVectorStatus(Long id);

    /**
     * RAG向量搜索知识库
     *
     * @param query 搜索文本
     * @return 搜索结果
     */
    List<Map<String, Object>> search(String query);

    /**
     * 触发文件向量化处理
     *
     * @param id 文件ID
     * @return 更新后的文件VO
     */
    KnowledgeFileVO vectorizeFile(Long id);

    /**
     * 替换知识库文档文件（保留原记录ID）
     * 替换OSS对象、重置向量化状态、清空统计信息，并自动触发重新向量化
     *
     * @param id   文件ID
     * @param file 新文件
     * @return 更新后的文件VO
     */
    KnowledgeFileVO replaceFile(Long id, MultipartFile file);

    /**
     * 知识库统计信息（文件总数/各向量状态数量/分块与token汇总/存储占用）
     *
     * @return 统计信息 Map
     */
    Map<String, Object> statistics();

    /**
     * 知识库健康检查（向量存储可用性、待处理/失败数量等）
     *
     * @return 健康信息 Map
     */
    Map<String, Object> health();
}
