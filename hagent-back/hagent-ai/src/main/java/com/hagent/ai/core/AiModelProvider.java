package com.hagent.ai.core;

import com.hagent.ai.config.AiConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import org.springframework.context.annotation.Lazy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AI模型提供者
 * 提供延迟初始化且支持配置刷新的ChatModel、EmbeddingModel和VectorStore
 * <p>
 * VectorStore 已切换为 PgVectorStore（PostgreSQL + pgvector，权限过滤走原生 filterExpression 第一档），
 * 权限入向量库（metadata: visibility/user_id/file_id）+ 检索时原生过滤 + 同库 jsonb_set 同步管道。
 *
 * @author H
 * @see PgVectorStore
 */
@Slf4j
@Component
public class AiModelProvider {

    private final AiConfig aiConfig;
    private final JdbcTemplate jdbcTemplate;

    private volatile ChatModel chatModel;
    private volatile EmbeddingModel embeddingModel;
    private volatile VectorStore vectorStore;

    /** 有序对话模型列表（主 + 多级备用按 order 排序，缓存于 volatile 列表） */
    private volatile List<ChatModel> chatModels = new ArrayList<>();

    /** 上次配置哈希值 */
    private volatile int configHash = 0;

    /** 上次重建时间 */
    private volatile long lastRebuildTime = 0;

    /** 冷却周期（毫秒） */
    private static final long REBUILD_COOLDOWN_MS = 5000;

    public AiModelProvider(@Lazy AiConfig aiConfig, JdbcTemplate jdbcTemplate) {
        this.aiConfig = aiConfig;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 初始化方法，由 Spring 在依赖注入完成后调用
     * 使用 @Lazy 延迟注入 AiConfig，确保 AiConfig 已完成初始化
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        // 强制刷新 AiConfig 配置，确保从数据库读取最新值（AiConfig@PostConstruct 可能晚于本类，需显式刷新）
        aiConfig.refresh();
        log.info("AiConfig配置: vectorTable={}, dimension={}",
                "vector_store", aiConfig.getVectorStore().getDimension());

        // 启动时立即重建，确保 VectorStore 可用
        rebuild();
        log.info("AiModelProvider已初始化并完成首次构建");
    }

    /**
     * 获取ChatModel，配置变更时自动重建
     */
    public ChatModel getChatModel() {
        synchronized (this) { if (chatModel == null || isConfigChanged()) rebuild(); }
        return chatModel;
    }

    /**
     * 获取主对话模型（与 getChatModel 等价，保留供降级语义清晰调用）
     *
     * @return 主 ChatModel
     * @author H
     */
    public ChatModel getPrimaryChatModel() {
        return getChatModel();
    }

    /**
     * 获取有序对话模型列表（主 + 多级备用按 order 排序）
     * 空回退时仅含主模型
     *
     * @return 不可变有序列表
     * @author H
     */
    public List<ChatModel> getChatModels() {
        synchronized (this) { if (chatModels == null || chatModels.isEmpty() || isConfigChanged()) rebuild(); }
        return chatModels;
    }

    /**
     * 获取有序对话模型列表（别名，供 ModelRouter 调用）
     *
     * @return 不可变有序列表
     * @author H
     */
    public List<ChatModel> getOrderedModels() {
        return getChatModels();
    }

    /**
     * 获取EmbeddingModel，配置变更时自动重建
     */
    public EmbeddingModel getEmbeddingModel() {
        synchronized (this) { if (embeddingModel == null || isConfigChanged()) rebuild(); }
        return embeddingModel;
    }

    /**
     * 获取VectorStore，配置变更时自动重建
     */
    public VectorStore getVectorStore() {
        synchronized (this) { if (vectorStore == null || isConfigChanged()) rebuild(); }
        return vectorStore;
    }

    /**
     * 检查配置是否变更
     */
    private boolean isConfigChanged() {
        return configHash != computeConfigHash();
    }

    /**
     * 基于影响模型构建的关键字段计算配置指纹，避免依赖 toString() 格式不稳定
     * 仅包含影响 ChatModel/EmbeddingModel/VectorStore 重建的字段，含备用模型配置
     */
    private int computeConfigHash() {
        AiConfig.Chat chat = aiConfig.getChat();
        AiConfig.Embedding emb = aiConfig.getEmbedding();
        AiConfig.VectorStore vec = aiConfig.getVectorStore();
        AiConfig.FallbackChat fallback = aiConfig.getFallbackChat();
        int fallbackHash = 0;
        if (fallback != null) {
            fallbackHash = java.util.Objects.hash(fallback.isEnabled(), fallback.getModels().size());
            for (AiConfig.FallbackModel m : fallback.getModels()) {
                fallbackHash = 31 * fallbackHash + java.util.Objects.hash(
                        m.getName(), m.getBaseUrl(), m.getApiKey(), m.getOrder());
            }
        }
        return java.util.Objects.hash(
                chat.getApiKey(), chat.getBaseUrl(), chat.getModel(),
                emb.getApiKey(), emb.getBaseUrl(), emb.getModel(),
                vec.getDimension(),
                fallbackHash
        );
    }

    /**
     * 从配置重建AI模型实例
     * 使用OpenAiChatOptions/OpenAiEmbeddingOptions直接设置apiKey和baseUrl，
     * 让Spring AI内部自动创建OpenAI客户端（与旧项目模式一致）
     */
    public synchronized void rebuild() {
        long now = System.currentTimeMillis();
        // 仅当模型已构建成功时才应用冷却，避免首次/构建失败后因冷却而留下 null 导致 NPE
        boolean modelsReady = chatModel != null && embeddingModel != null && vectorStore != null;
        if (modelsReady && now - lastRebuildTime < REBUILD_COOLDOWN_MS && lastRebuildTime > 0) {
            log.debug("重建冷却中，跳过本次重建");
            return;
        }

        try {
            AiConfig.Chat chatCfg = aiConfig.getChat();
            AiConfig.Embedding embCfg = aiConfig.getEmbedding();
            AiConfig.VectorStore vecCfg = aiConfig.getVectorStore();

            // 构建主 ChatModel - 通过Options设置apiKey和baseUrl
            OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                    .apiKey(chatCfg.getApiKey())
                    .baseUrl(chatCfg.getBaseUrl())
                    .model(chatCfg.getModel())
                    .build();
            ChatModel primaryChatModel = OpenAiChatModel.builder()
                    .options(chatOptions)
                    .build();
            this.chatModel = primaryChatModel;

            // 构建有序对话模型列表（主 + 多级备用按 order 排序，仅用备用 API 模型，不引入 Ollama）
            List<ChatModel> ordered = new ArrayList<>();
            ordered.add(primaryChatModel);
            AiConfig.FallbackChat fallbackChat = aiConfig.getFallbackChat();
            if (fallbackChat != null && fallbackChat.isEnabled()
                    && fallbackChat.getModels() != null && !fallbackChat.getModels().isEmpty()) {
                for (AiConfig.FallbackModel fm : fallbackChat.getModels()) {
                    String modelName = StringUtils.hasText(fm.getName()) ? fm.getName() : chatCfg.getModel();
                    String baseUrl = StringUtils.hasText(fm.getBaseUrl()) ? fm.getBaseUrl() : chatCfg.getBaseUrl();
                    String apiKey = StringUtils.hasText(fm.getApiKey()) ? fm.getApiKey() : chatCfg.getApiKey();
                    OpenAiChatOptions fallbackOptions = OpenAiChatOptions.builder()
                            .apiKey(apiKey)
                            .baseUrl(baseUrl)
                            .model(modelName)
                            .build();
                    ChatModel fallbackModel = OpenAiChatModel.builder()
                            .options(fallbackOptions)
                            .build();
                    ordered.add(fallbackModel);
                }
                log.info("已构建备用对话模型: 主模型={}, 备用数量={}, 总数={}", chatCfg.getModel(), fallbackChat.getModels().size(), ordered.size());
            }
            this.chatModels = Collections.unmodifiableList(ordered);

            // 构建EmbeddingModel - 通过Options设置apiKey和baseUrl
            OpenAiEmbeddingOptions embeddingOptions = OpenAiEmbeddingOptions.builder()
                    .apiKey(embCfg.getApiKey())
                    .baseUrl(embCfg.getBaseUrl())
                    .model(embCfg.getModel())
                    .build();
            this.embeddingModel = OpenAiEmbeddingModel.builder()
                    .options(embeddingOptions)
                    .build();

            int dimension = vecCfg.getDimension();
            if (dimension <= 0) {
                log.warn("vector dimension 非法 ({}), 强制回退为 1024", dimension);
                dimension = 1024;
            }
            log.info("PgVectorStore dimension from ai.vector-dimension={}, table=vector_store", dimension);

            try {
                VectorStore pgStore = PgVectorStore.builder(jdbcTemplate, embeddingModel)
                        .vectorTableName("vector_store")
                        .dimensions(dimension)
                        .indexType(PgVectorStore.PgIndexType.HNSW)
                        .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                        .initializeSchema(true)
                        .build();

                this.vectorStore = pgStore;
                log.info("PgVectorStore创建成功, AI模型重建成功: vectorTable=vector_store, dimension={}, chatModel={}, embeddingModel={}",
                        dimension, chatCfg.getModel(), embCfg.getModel());

                // VectorStore构建成功后才刷新配置哈希与重建时间，确保失败时下次访问可重试
                this.configHash = computeConfigHash();
                this.lastRebuildTime = now;
            } catch (Exception e) {
                log.error("PgVectorStore创建失败: dimension={}, error={}", dimension, e.getMessage(), e);
            }
        } catch (Exception e) {
            log.error("AI模型重建失败", e);
        }
    }
}
