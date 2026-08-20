package com.hagent.ai.memory;

import com.hagent.ai.config.AiConfig;
import com.hagent.ai.core.AiModelProvider;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * AI记忆配置
 * 配置AutoMemoryToolsAdvisor并初始化记忆目录结构
 * 仅在hui.ai.memory.enabled=true时激活
 *
 * @author H
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "hui.ai.memory", name = "enabled", havingValue = "true", matchIfMissing = false)
public class MemoryConfig {

    private final AiConfig aiConfig;
    private final AiModelProvider aiModelProvider;

    public MemoryConfig(AiConfig aiConfig, AiModelProvider aiModelProvider) {
        this.aiConfig = aiConfig;
        this.aiModelProvider = aiModelProvider;
    }

    /**
     * 创建带记忆顾问的ChatClient Bean
     * 集成AutoMemoryToolsAdvisor实现持久化记忆
     *
     * @return 支持记忆的ChatClient实例
     */
    @Bean
    @Lazy
    public ChatClient chatClient() {
        AiConfig.Memory memoryConfig = aiConfig.getMemory();
        String memoriesDir = memoryConfig.getMemoriesRootDirectory();

        // 初始化记忆目录结构
        initMemoryDirectory(memoriesDir);

        // 获取ChatModel（AI未配置时可能为null）
        ChatModel chatModel = aiModelProvider.getChatModel();
        if (chatModel == null) {
            log.warn("ChatModel不可用 - AI功能将被禁用");
            return ChatClient.builder(new org.springframework.ai.chat.model.ChatModel() {
                @Override public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
                    throw new UnsupportedOperationException("AI未配置");
                }
                @Override public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(org.springframework.ai.chat.prompt.Prompt prompt) {
                    throw new UnsupportedOperationException("AI未配置");
                }
                @Override public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
                    return org.springframework.ai.chat.prompt.ChatOptions.builder().build();
                }
            }).build();
        }

        // 构建AutoMemoryToolsAdvisor
        AutoMemoryToolsAdvisor memoryAdvisor = AutoMemoryToolsAdvisor.builder()
                .memoriesRootDirectory(memoriesDir)
                .memoryConsolidationTrigger((req, instant) ->
                        Math.random() < memoryConfig.getConsolidationProbability())
                .build();

        // 构建带记忆顾问的ChatClient
        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(memoryAdvisor)
                .build();

        log.info("ChatClient已初始化（含记忆顾问），记忆根目录: {}", memoriesDir);
        return chatClient;
    }

    /**
     * 初始化记忆目录结构
     */
    private void initMemoryDirectory(String memoriesDir) {
        try {
            Path rootPath = Paths.get(memoriesDir);
            if (!Files.exists(rootPath)) {
                Files.createDirectories(rootPath);
                log.info("已创建记忆根目录: {}", memoriesDir);
            }

            // 创建各分类子目录
            String[] subDirs = {"user", "feedback", "project", "reference"};
            for (String subDir : subDirs) {
                Path subPath = rootPath.resolve(subDir);
                if (!Files.exists(subPath)) {
                    Files.createDirectories(subPath);
                    log.info("已创建记忆子目录: {}", subPath);
                }
            }

            // MEMORY.md不存在时创建模板
            Path memoryMd = rootPath.resolve("MEMORY.md");
            if (!Files.exists(memoryMd)) {
                String template = """
                        # AI记忆索引

                        此文件由AutoMemoryToolsAdvisor自动管理。

                        ## 用户记忆 (user/)
                        - 用户偏好和习惯
                        - 沟通风格

                        ## 反馈记忆 (feedback/)
                        - AI回复的反馈
                        - 改进建议

                        ## 项目记忆 (project/)
                        - 项目上下文和目标
                        - 技术决策

                        ## 参考记忆 (reference/)
                        - 重要参考文档
                        - 常用知识

                        ---
                        * 由AutoMemoryToolsAdvisor管理 *
                        """;
                Files.writeString(memoryMd, template);
                log.info("已创建MEMORY.md模板: {}", memoryMd);
            }

        } catch (IOException e) {
            log.error("初始化记忆目录失败: {}", memoriesDir, e);
        }
    }
}
