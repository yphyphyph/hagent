package com.hagent.ai.config;

import com.hagent.common.service.SystemConfigService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * AI配置属性
 * 从数据库system_config表读取配置
 *
 * @author H
 */
@Slf4j
@Data
@Component
public class AiConfig {

    @Autowired
    private SystemConfigService configService;

    private Chat chat = new Chat();
    private Embedding embedding = new Embedding();
    private VectorStore vectorStore = new VectorStore();
    private Memory memory = new Memory();

    @PostConstruct
    public void init() {
        refresh();
        log.info("AiConfig initialized from database");
    }

    /**
     * 从数据库刷新配置
     */
    public void refresh() {
        // Chat config
        chat.setApiKey(getConfigValue("ai.chat.api-key", ""));
        chat.setBaseUrl(getConfigValue("ai.chat.base-url", ""));
        chat.setModel(getConfigValue("ai.chat.model", "mimo-v2.5"));

        // Embedding config
        embedding.setApiKey(getConfigValue("ai.embedding.api-key", ""));
        embedding.setBaseUrl(getConfigValue("ai.embedding.base-url", ""));
        embedding.setModel(getConfigValue("ai.embedding.model", "text-embedding-v4"));

        // VectorStore config
        vectorStore.setIndexName(getConfigValue("ai.vector-index", "hagent_rag"));
        vectorStore.setPrefix("hui:rag:");

        // Memory config
        memory.setMemoriesRootDirectory("/data/hagent/memories");
        memory.setEnabled(true);
        memory.setConsolidationProbability(0.05);
    }

    private String getConfigValue(String key, String defaultValue) {
        try {
            String value = configService.getValueByKey(key);
            return value != null ? value : defaultValue;
        } catch (Exception e) {
            log.warn("Failed to read config key: {}, using default: {}", key, defaultValue);
            return defaultValue;
        }
    }

    @Data
    public static class Chat {
        /** 模型名称 */
        private String model = "qwen-plus";
        /** API Key */
        private String apiKey = "";
        /** API基础URL */
        private String baseUrl = "";
    }

    @Data
    public static class Embedding {
        /** 模型名称 */
        private String model = "text-embedding-v1";
        /** API Key */
        private String apiKey = "";
        /** API基础URL */
        private String baseUrl = "";
    }

    @Data
    public static class VectorStore {
        /** 索引名称 */
        private String indexName = "hagent_rag";
        /** Redis Key前缀 */
        private String prefix = "hui:rag:";
    }

    @Data
    public static class Memory {
        /** 记忆根目录 */
        private String memoriesRootDirectory = "/data/hagent/memories";
        /** 是否启用记忆 */
        private boolean enabled = true;
        /** 合并概率阈值，范围0.0-1.0，默认5% */
        private double consolidationProbability = 0.05;
    }
}
