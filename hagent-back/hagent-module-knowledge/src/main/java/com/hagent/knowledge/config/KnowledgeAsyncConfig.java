package com.hagent.knowledge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 知识库异步任务线程池配置
 * <p>
 * 用于文档向量化等耗时操作的异步执行，避免阻塞上传 HTTP 请求线程。
 *
 * @author H
 */
@Configuration
public class KnowledgeAsyncConfig {

    /** 向量化异步线程池 Bean 名称 */
    public static final String VECTORIZE_EXECUTOR = "vectorizeExecutor";

    /**
     * 向量化任务线程池：核心 2 线程、最大 4 线程、队列 50，满载时由调用线程兜底执行。
     *
     * @return 线程池执行器
     */
    @Bean(name = VECTORIZE_EXECUTOR)
    public Executor vectorizeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("kb-vectorize-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
