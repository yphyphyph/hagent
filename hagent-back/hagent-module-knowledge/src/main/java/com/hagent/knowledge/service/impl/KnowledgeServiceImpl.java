package com.hagent.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hagent.common.PageResult;
import com.hagent.common.context.UserContext;
import com.hagent.common.exception.BusinessException;
import com.hagent.common.service.VectorStoreService;
import com.hagent.knowledge.config.OssConfig;
import com.hagent.knowledge.dto.KnowledgeQueryRequest;
import com.hagent.knowledge.entity.KnowledgeFile;
import com.hagent.knowledge.enums.VectorStatusEnum;
import com.hagent.knowledge.mapper.KnowledgeFileMapper;
import com.hagent.knowledge.service.KnowledgeService;
import com.hagent.knowledge.service.KnowledgeVectorizeRunner;
import com.hagent.knowledge.util.AliOssUtil;
import com.hagent.knowledge.util.DocumentTextExtractor;
import com.hagent.knowledge.util.SemanticChunker;
import com.hagent.knowledge.vo.KnowledgeFileVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识库服务实现，用于文档管理
 *
 * @author H
 */
@Slf4j
@Service
public class KnowledgeServiceImpl extends ServiceImpl<KnowledgeFileMapper, KnowledgeFile> implements KnowledgeService {

    /** 每个文档块的最大字符数 */
    private static final int CHUNK_SIZE = 500;

    /** 文档块之间的重叠字符数 */
    private static final int CHUNK_OVERLAP = 50;

    /** 允许上传的文件扩展名白名单（Apache Tika 支持的常见文档格式） */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "pdf", "doc", "docx",
            "xls", "xlsx", "ppt", "pptx", "csv", "html", "htm", "rtf");

    /** 允许上传的 MIME 类型白名单 */
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "text/plain", "text/markdown", "text/csv", "text/html", "application/octet-stream",
            "application/pdf", "application/rtf", "text/rtf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    );

    /** 单文件大小上限：20MB */
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;

    /** 知识库检索次数 Redis 计数键（与 RagService 保持一致） */
    private static final String SEARCH_COUNT_REDIS_KEY = "hagent:knowledge:search:count";

    /** 文件名清洗正则：移除路径分隔符与危险字符 */
    private static final java.util.regex.Pattern FILENAME_SANITIZE_PATTERN =
            java.util.regex.Pattern.compile("[/\\\\:*?\"<>|]");

    private final OssConfig ossConfig;
    private final AliOssUtil aliOssUtil;
    private final VectorStoreService vectorStoreService;
    private final DocumentTextExtractor documentTextExtractor;
    private final SemanticChunker semanticChunker;
    private final KnowledgeVectorizeRunner vectorizeRunner;
    private final StringRedisTemplate stringRedisTemplate;

    public KnowledgeServiceImpl(OssConfig ossConfig,
                                @Lazy @Autowired(required = false) AliOssUtil aliOssUtil,
                                @Lazy @Autowired(required = false) VectorStoreService vectorStoreService,
                                @Autowired(required = false) StringRedisTemplate stringRedisTemplate,
                                DocumentTextExtractor documentTextExtractor,
                                SemanticChunker semanticChunker,
                                KnowledgeVectorizeRunner vectorizeRunner) {
        this.ossConfig = ossConfig;
        this.aliOssUtil = aliOssUtil;
        this.vectorStoreService = vectorStoreService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.documentTextExtractor = documentTextExtractor;
        this.semanticChunker = semanticChunker;
        this.vectorizeRunner = vectorizeRunner;
    }

    @Override
    public KnowledgeFileVO uploadDocument(MultipartFile file) {
        // 文件安全校验：扩展名、MIME 类型、文件名清洗（无需事务保护）
        String originalFilename = sanitizeFilename(file.getOriginalFilename());
        String fileExt = getFileExtension(originalFilename);
        validateFile(originalFilename, fileExt, file.getContentType(), file.getSize());

        // OSS 对象键，用于失败时回滚清理
        String objectName = "knowledge/" + System.currentTimeMillis() + "_" + originalFilename;
        // 标记 OSS 是否已上传成功，用于 DB 失败时回滚
        boolean ossUploaded = false;

        try {
            // 上传到OSS（远程调用，必须在事务外执行）
            String fileUrl = aliOssUtil.uploadFile(file.getInputStream(), objectName);
            ossUploaded = true;

            // 构建实体并保存到数据库（单次 DB 写操作，无需事务保护）
            KnowledgeFile knowledgeFile = new KnowledgeFile();
            knowledgeFile.setFileName(originalFilename);
            knowledgeFile.setFileUrl(fileUrl);
            knowledgeFile.setObjectName(objectName);
            knowledgeFile.setFileSize(file.getSize());
            knowledgeFile.setFileType(file.getContentType());
            knowledgeFile.setFileExt(fileExt);
            knowledgeFile.setVectorStatus(VectorStatusEnum.PENDING.getCode());
            knowledgeFile.setVectorVersion(1);
            knowledgeFile.setChunkCount(0);
            knowledgeFile.setTokenCount(0);
            knowledgeFile.setCreatedAt(LocalDateTime.now());
            knowledgeFile.setCreatedBy(UserContext.getUsername());
            knowledgeFile.setUpdatedAt(LocalDateTime.now());
            knowledgeFile.setUpdatedBy(UserContext.getUsername());

            try {
                super.save(knowledgeFile);
            } catch (Exception dbEx) {
                // DB 保存失败，回滚已上传的 OSS 对象，避免产生孤儿对象
                rollbackOssObject(objectName, ossUploaded);
                throw dbEx;
            }

            log.info("已上传知识库文件: id={}, name={}", knowledgeFile.getId(), originalFilename);

            // 异步触发向量化（不阻塞上传 HTTP 线程，状态可通过轮询接口查询）
            try {
                vectorizeRunner.runAsync(knowledgeFile.getId(), UserContext.getUsername());
                log.info("已提交异步向量化任务: id={}", knowledgeFile.getId());
            } catch (Exception e) {
                log.warn("提交异步向量化失败，可稍后手动触发: id={}, error={}", knowledgeFile.getId(), e.getMessage());
            }

            return convertToVO(knowledgeFile);
        } catch (Exception e) {
            log.error("上传文档失败", e);
            throw new BusinessException("上传失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<KnowledgeFileVO> listFiles(KnowledgeQueryRequest request) {
        LambdaQueryWrapper<KnowledgeFile> wrapper = new LambdaQueryWrapper<>();

        // 按文件名筛选
        if (StringUtils.hasText(request.getFileName())) {
            wrapper.like(KnowledgeFile::getFileName, request.getFileName());
        }

        // 按文件扩展名筛选
        if (StringUtils.hasText(request.getFileExt())) {
            wrapper.eq(KnowledgeFile::getFileExt, request.getFileExt());
        }

        // 按向量状态筛选
        if (request.getVectorStatus() != null) {
            wrapper.eq(KnowledgeFile::getVectorStatus, request.getVectorStatus());
        }

        wrapper.orderByDesc(KnowledgeFile::getCreatedAt);

        // 分页查询
        Page<KnowledgeFile> page = new Page<>(request.getPageNum(), request.getPageSize());
        Page<KnowledgeFile> result = super.page(page, wrapper);

        List<KnowledgeFileVO> voList = result.getRecords().stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());

        return PageResult.of(voList, result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    @Override
    public void deleteFile(Long id) {
        // 查询 DB 获取文件信息（单次读操作，无需事务保护）
        KnowledgeFile knowledgeFile = super.getById(id);
        if (knowledgeFile == null) {
            throw new BusinessException("知识库文件不存在");
        }

        // 从向量存储中删除（远程调用，必须在事务外执行）
        if (vectorStoreService != null) {
            try {
                vectorStoreService.deleteByFileId(String.valueOf(id));
                log.info("已从向量存储删除文件: fileId={}", id);
            } catch (Exception e) {
                log.warn("从向量存储删除文件失败: fileId={}", id, e);
            }
        }

        // 从OSS删除（远程调用，必须在事务外执行）
        aliOssUtil.deleteFile(knowledgeFile.getObjectName());

        // 从数据库删除（单次 DB 写操作，无需事务保护）
        super.removeById(id);

        log.info("已删除知识库文件: id={}, name={}", id, knowledgeFile.getFileName());
    }

    @Override
    @Transactional(readOnly = true)
    public KnowledgeFileVO getVectorStatus(Long id) {
        KnowledgeFile knowledgeFile = super.getById(id);
        if (knowledgeFile == null) {
            throw new BusinessException("知识库文件不存在");
        }
        return convertToVO(knowledgeFile);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> search(String query) {
        if (vectorStoreService == null) {
            log.warn("VectorStoreService不可用，返回空结果");
            return new ArrayList<>();
        }

        try {
            // 使用向量存储服务搜索相似文档
            List<Map<String, Object>> results = vectorStoreService.search(query, 5);
            log.info("RAG搜索: query={}, results={}", query, results.size());
            return results;
        } catch (Exception e) {
            log.error("RAG搜索失败: query={}", query, e);
            return new ArrayList<>();
        }
    }

    @Override
    public KnowledgeFileVO vectorizeFile(Long id) {
        // 1. 获取文件信息（单次读操作，无需事务保护）
        KnowledgeFile knowledgeFile = super.getById(id);
        if (knowledgeFile == null) {
            throw new BusinessException("知识库文件不存在");
        }

        // 2. 已向量化的文件支持重新向量化：先删除旧向量数据，再重新生成（远程调用，必须在事务外执行）
        if (VectorStatusEnum.COMPLETED.getCode().equals(knowledgeFile.getVectorStatus())) {
            log.info("文件已向量化，执行重新向量化: id={}", id);
            if (vectorStoreService != null) {
                try {
                    vectorStoreService.deleteByFileId(String.valueOf(id));
                    log.info("已删除旧向量数据: id={}", id);
                } catch (Exception e) {
                    log.warn("删除旧向量数据失败，继续重新向量化: id={}", id, e);
                }
            }
        }

        // 3. 更新状态为处理中（单次 DB 写操作，无需事务保护）
        knowledgeFile.setVectorStatus(VectorStatusEnum.PROCESSING.getCode());
        knowledgeFile.setUpdatedAt(LocalDateTime.now());
        knowledgeFile.setUpdatedBy(UserContext.getUsername());
        super.updateById(knowledgeFile);

        try {
            // 4. 从OSS下载文件内容（远程调用，必须在事务外执行）
            String fileExt = knowledgeFile.getFileExt();
            String content = downloadAndExtractText(knowledgeFile.getObjectName(), fileExt);

            if (content == null || content.trim().isEmpty()) {
                throw new BusinessException("文件内容为空或不支持的文件格式: " + fileExt);
            }

            // 5. 语义分块处理（按段落/句子边界切分，不切断完整语义）
            List<String> chunks = semanticChunker.chunk(content, CHUNK_SIZE, CHUNK_OVERLAP);
            log.info("文件语义分块: id={}, chunks={}", id, chunks.size());

            // 6. 存入向量存储（远程调用，必须在事务外执行）
            if (vectorStoreService != null) {
                vectorStoreService.addDocuments(String.valueOf(id), knowledgeFile.getFileName(), chunks);
                log.info("已存入向量存储: id={}, chunks={}", id, chunks.size());
            } else {
                log.warn("VectorStoreService不可用，跳过向量存储");
            }

            // 7. 更新状态为已完成，向量版本号自增（单次 DB 写操作，无需事务保护）
            knowledgeFile.setVectorStatus(VectorStatusEnum.COMPLETED.getCode());
            knowledgeFile.setChunkCount(chunks.size());
            knowledgeFile.setTokenCount(content.length() / 4); // 粗略估计
            knowledgeFile.setVectorVersion(
                    (knowledgeFile.getVectorVersion() == null ? 0 : knowledgeFile.getVectorVersion()) + 1);
            knowledgeFile.setUpdatedAt(LocalDateTime.now());
            knowledgeFile.setUpdatedBy(UserContext.getUsername());
            super.updateById(knowledgeFile);

            log.info("向量化完成: id={}, chunks={}, tokens={}, version={}",
                    id, chunks.size(), knowledgeFile.getTokenCount(), knowledgeFile.getVectorVersion());
            return convertToVO(knowledgeFile);

        } catch (Exception e) {
            // 更新状态为失败（单次 DB 写操作，无需事务保护）
            knowledgeFile.setVectorStatus(VectorStatusEnum.FAILED.getCode());
            knowledgeFile.setErrorMessage(e.getMessage());
            knowledgeFile.setUpdatedAt(LocalDateTime.now());
            knowledgeFile.setUpdatedBy(UserContext.getUsername());
            super.updateById(knowledgeFile);

            log.error("向量化失败: id={}", id, e);
            throw new BusinessException("向量化失败: " + e.getMessage());
        }
    }

    @Override
    public KnowledgeFileVO replaceFile(Long id, MultipartFile file) {
        // 1. 校验原文件存在（单次读操作，无需事务保护）
        KnowledgeFile knowledgeFile = super.getById(id);
        if (knowledgeFile == null) {
            throw new BusinessException("知识库文件不存在");
        }

        // 标记新 OSS 对象是否上传成功，用于 DB 失败时回滚清理
        String newObjectName = null;
        boolean newOssUploaded = false;

        try {
            // 2. 删除旧OSS对象（远程调用，必须在事务外执行，失败不阻断替换流程）
            String oldObjectName = knowledgeFile.getObjectName();
            if (oldObjectName != null) {
                try {
                    aliOssUtil.deleteFile(oldObjectName);
                } catch (Exception e) {
                    log.warn("删除旧OSS对象失败，继续替换: objectName={}", oldObjectName, e);
                }
            }

            // 3. 删除旧向量数据（远程调用，必须在事务外执行，失败不阻断替换流程）
            if (vectorStoreService != null) {
                try {
                    vectorStoreService.deleteByFileId(String.valueOf(id));
                } catch (Exception e) {
                    log.warn("删除旧向量数据失败，继续替换: id={}", id, e);
                }
            }

            // 4. 上传新文件到OSS（先做安全校验与文件名清洗，远程调用必须在事务外执行）
            String originalFilename = sanitizeFilename(file.getOriginalFilename());
            String fileExt = getFileExtension(originalFilename);
            validateFile(originalFilename, fileExt, file.getContentType(), file.getSize());
            newObjectName = "knowledge/" + System.currentTimeMillis() + "_" + originalFilename;
            String fileUrl = aliOssUtil.uploadFile(file.getInputStream(), newObjectName);
            newOssUploaded = true;

            // 5. 更新文件记录（保留原ID），重置向量化状态与统计信息（单次 DB 写操作，无需事务保护）
            knowledgeFile.setFileName(originalFilename);
            knowledgeFile.setFileUrl(fileUrl);
            knowledgeFile.setObjectName(newObjectName);
            knowledgeFile.setFileSize(file.getSize());
            knowledgeFile.setFileType(file.getContentType());
            knowledgeFile.setFileExt(fileExt);
            knowledgeFile.setVectorStatus(VectorStatusEnum.PENDING.getCode());
            knowledgeFile.setChunkCount(0);
            knowledgeFile.setTokenCount(0);
            knowledgeFile.setErrorMessage(null);
            knowledgeFile.setUpdatedAt(LocalDateTime.now());
            knowledgeFile.setUpdatedBy(UserContext.getUsername());

            try {
                super.updateById(knowledgeFile);
            } catch (Exception dbEx) {
                // DB 更新失败，回滚已上传的新 OSS 对象，避免产生孤儿对象
                rollbackOssObject(newObjectName, newOssUploaded);
                throw dbEx;
            }

            log.info("已替换知识库文件: id={}, newName={}", id, originalFilename);

            // 6. 自动触发异步向量化
            try {
                vectorizeRunner.runAsync(id, UserContext.getUsername());
            } catch (Exception e) {
                log.warn("替换后提交异步向量化失败，可稍后手动触发: id={}, error={}", id, e.getMessage());
            }

            return convertToVO(knowledgeFile);
        } catch (Exception e) {
            log.error("替换文档失败: id={}", id, e);
            throw new BusinessException("替换失败: " + e.getMessage());
        }
    }

    /**
     * 回滚已上传的 OSS 对象，避免 DB 操作失败后产生孤儿对象
     *
     * @param objectName   OSS 对象名
     * @param ossUploaded  OSS 是否已上传成功
     * @author H
     */
    private void rollbackOssObject(String objectName, boolean ossUploaded) {
        if (!ossUploaded || objectName == null) {
            return;
        }
        try {
            aliOssUtil.deleteFile(objectName);
            log.info("已回滚清理 OSS 对象: objectName={}", objectName);
        } catch (Exception ex) {
            log.error("回滚 OSS 对象失败，可能产生孤儿对象: objectName={}", objectName, ex);
        }
    }

    /**
     * 从OSS下载文件并提取文本内容（基于 Apache Tika 支持多格式）
     *
     * @param objectName OSS对象名
     * @param fileExt    文件扩展名
     * @return 文件文本内容
     */
    private String downloadAndExtractText(String objectName, String fileExt) {
        try (InputStream inputStream = aliOssUtil.downloadFile(objectName)) {
            if (inputStream == null) {
                return null;
            }
            String fileName = objectName != null && objectName.contains("/")
                    ? objectName.substring(objectName.lastIndexOf('/') + 1) : objectName;
            return documentTextExtractor.extractText(inputStream, fileExt, fileName);
        } catch (Exception e) {
            log.error("下载或解析文件失败: {}", objectName, e);
            return null;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> statistics() {
        Map<String, Object> stats = new HashMap<>();
        long total = super.count();
        long pending = countByStatus(VectorStatusEnum.PENDING.getCode());
        long processing = countByStatus(VectorStatusEnum.PROCESSING.getCode());
        long completed = countByStatus(VectorStatusEnum.COMPLETED.getCode());
        long failed = countByStatus(VectorStatusEnum.FAILED.getCode());

        long totalChunks = 0;
        long totalTokens = 0;
        long totalSize = 0;
        List<KnowledgeFile> all = super.list();
        for (KnowledgeFile f : all) {
            totalChunks += f.getChunkCount() == null ? 0 : f.getChunkCount();
            totalTokens += f.getTokenCount() == null ? 0 : f.getTokenCount();
            totalSize += f.getFileSize() == null ? 0 : f.getFileSize();
        }

        stats.put("totalFiles", total);
        stats.put("pending", pending);
        stats.put("processing", processing);
        stats.put("completed", completed);
        stats.put("failed", failed);
        stats.put("totalChunks", totalChunks);
        stats.put("totalTokens", totalTokens);
        stats.put("totalSize", totalSize);
        stats.put("searchCount", getSearchCount());
        return stats;
    }

    /**
     * 从 Redis 读取知识库检索次数累计值
     *
     * @return 检索次数，Redis 不可用或未计数时返回 0
     */
    private long getSearchCount() {
        if (stringRedisTemplate == null) {
            return 0L;
        }
        try {
            String value = stringRedisTemplate.opsForValue().get(SEARCH_COUNT_REDIS_KEY);
            return StringUtils.hasText(value) ? Long.parseLong(value) : 0L;
        } catch (Exception e) {
            log.warn("读取检索次数失败", e);
            return 0L;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> health() {
        Map<String, Object> health = new HashMap<>();
        boolean vectorStoreAvailable = vectorStoreService != null;
        long pending = countByStatus(VectorStatusEnum.PENDING.getCode());
        long processing = countByStatus(VectorStatusEnum.PROCESSING.getCode());
        long failed = countByStatus(VectorStatusEnum.FAILED.getCode());
        health.put("vectorStoreAvailable", vectorStoreAvailable);
        health.put("ossAvailable", aliOssUtil != null);
        health.put("pending", pending);
        health.put("processing", processing);
        health.put("failed", failed);
        health.put("status", vectorStoreAvailable && failed == 0 ? "UP" : "DEGRADED");
        return health;
    }

    /**
     * 按向量状态统计数量
     */
    private long countByStatus(Integer status) {
        LambdaQueryWrapper<KnowledgeFile> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeFile::getVectorStatus, status);
        return super.count(wrapper);
    }

    /**
     * 实体转 VO
     */
    private KnowledgeFileVO convertToVO(KnowledgeFile entity) {
        KnowledgeFileVO vo = new KnowledgeFileVO();
        vo.setId(entity.getId());
        vo.setFileName(entity.getFileName());
        vo.setFileUrl(entity.getFileUrl());
        vo.setObjectName(entity.getObjectName());
        vo.setFileSize(entity.getFileSize());
        vo.setFileType(entity.getFileType());
        vo.setFileExt(entity.getFileExt());
        vo.setVectorStatus(entity.getVectorStatus());
        vo.setVectorStatusDesc(VectorStatusEnum.getByCode(entity.getVectorStatus()).getDesc());
        vo.setVectorVersion(entity.getVectorVersion());
        vo.setChunkCount(entity.getChunkCount());
        vo.setTokenCount(entity.getTokenCount());
        vo.setErrorMessage(entity.getErrorMessage());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setCreatedBy(entity.getCreatedBy());
        return vo;
    }

    private String getFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "";
        }
        int lastDot = filename.lastIndexOf('.');
        return lastDot >= 0 ? filename.substring(lastDot + 1) : "";
    }

    /**
     * 清洗文件名：移除路径分隔符与危险字符，防止路径穿越攻击
     *
     * @param filename 原始文件名
     * @return 清洗后的文件名
     */
    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            throw new BusinessException("文件名不能为空");
        }
        // 仅保留文件名部分（去除可能的路径前缀）
        String name = filename;
        int lastSeparator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSeparator >= 0) {
            name = name.substring(lastSeparator + 1);
        }
        // 移除危险字符：/ \ : * ? " < > |
        name = FILENAME_SANITIZE_PATTERN.matcher(name).replaceAll("_");
        if (name.isEmpty()) {
            throw new BusinessException("文件名非法");
        }
        return name;
    }

    /**
     * 文件安全校验：扩展名白名单 + MIME 类型白名单
     *
     * @param filename  文件名
     * @param fileExt   文件扩展名（小写）
     * @param mimeType  MIME 类型
     * @param fileSize  文件大小（字节）
     */
    private void validateFile(String filename, String fileExt, String mimeType, long fileSize) {
        // 扩展名白名单校验
        if (!ALLOWED_EXTENSIONS.contains(fileExt.toLowerCase())) {
            throw new BusinessException("不支持的文件类型: " + fileExt + "，仅允许: " + ALLOWED_EXTENSIONS);
        }
        // MIME 类型白名单校验（部分浏览器上传 txt 时可能为 application/octet-stream，放宽校验）
        if (mimeType != null && !ALLOWED_MIME_TYPES.contains(mimeType.toLowerCase())) {
            throw new BusinessException("不支持的文件 MIME 类型: " + mimeType);
        }
        // 文件大小校验（保护性检查，实际也由 spring.servlet.multipart.max-file-size 拦截）
        if (fileSize <= 0) {
            throw new BusinessException("文件内容为空");
        }
        if (fileSize > MAX_FILE_SIZE) {
            throw new BusinessException("文件过大，上限为 " + (MAX_FILE_SIZE / 1024 / 1024) + "MB");
        }
    }
}
