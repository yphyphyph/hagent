package com.hagent.knowledge.service;

import com.hagent.common.context.UserContext;
import com.hagent.knowledge.config.KnowledgeAsyncConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 知识库向量化异步执行器
 * <p>
 * 将耗时的向量化任务放入独立线程池执行，避免阻塞上传 HTTP 线程。
 * 由于 {@link UserContext} 基于 ThreadLocal，异步线程无法继承主线程上下文，
 * 因此在异步方法内显式重建用户上下文，执行完成后清理。
 *
 * @author H
 */
@Slf4j
@Component
public class KnowledgeVectorizeRunner {

    private final KnowledgeService knowledgeService;

    public KnowledgeVectorizeRunner(@Lazy @Autowired KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /**
     * 异步执行文件向量化。
     *
     * @param fileId   文件ID
     * @param username 触发用户名（用于审计字段，异步线程内重建上下文）
     */
    @Async(KnowledgeAsyncConfig.VECTORIZE_EXECUTOR)
    public void runAsync(Long fileId, String username) {
        try {
            UserContext.set(null, username, null);
            knowledgeService.vectorizeFile(fileId);
            log.info("异步向量化完成: id={}", fileId);
        } catch (Exception e) {
            log.error("异步向量化失败: id={}, error={}", fileId, e.getMessage());
        } finally {
            UserContext.remove();
        }
    }
}
