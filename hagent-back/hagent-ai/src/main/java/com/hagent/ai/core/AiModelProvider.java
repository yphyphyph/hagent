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
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.RedisClient;

import org.springframework.context.annotation.Lazy;

/**
 * AI模型提供者
 * 提供延迟初始化且支持配置刷新的ChatModel、EmbeddingModel和VectorStore
 * <p>
 * 设计：
 * 1. 启动时：从AiConfig构建ChatModel、EmbeddingModel、VectorStore
 * 2. 配置变更时：下次访问自动重建
 * 3. 冷却机制防止频繁重建
 *
 * <p><b>重要说明 - Redis Vector Store 索引创建问题：</b></p>
 * <p>Spring AI 2.0.0 的 {@code RedisVectorStore} 实现了 {@code InitializingBean} 接口，
 * 其 {@code afterPropertiesSet()} 方法负责在 {@code initializeSchema=true} 时创建 FT 索引。</p>
 * <p>但是 {@code RedisVectorStore.builder().build()} 方法只是创建普通 Java 对象，
 * 不会触发 Spring 的生命周期回调，因此 {@code afterPropertiesSet()} 不会被自动调用。</p>
 * <p><b>解决方案：</b>在 {@code rebuild()} 方法中，创建 {@code RedisVectorStore} 后，
 * 必须手动调用 {@code vectorStore.afterPropertiesSet()} 来触发 FT 索引创建。</p>
 *
 * @author H
 * @see RedisVectorStore
 * @see org.springframework.beans.factory.InitializingBean
 */
@Slf4j
@Component
public class AiModelProvider {

    private final AiConfig aiConfig;

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    private volatile ChatModel chatModel;
    private volatile EmbeddingModel embeddingModel;
    private volatile VectorStore vectorStore;
    private volatile RedisClient jedisClient;

    /** 上次配置哈希值 */
    private volatile int configHash = 0;

    /** 上次重建时间 */
    private volatile long lastRebuildTime = 0;

    /** 冷却周期（毫秒） */
    private static final long REBUILD_COOLDOWN_MS = 5000;

    public AiModelProvider(@Lazy AiConfig aiConfig) {
        this.aiConfig = aiConfig;
    }

    /**
     * 初始化方法，由 Spring 在依赖注入完成后调用
     * 使用 @Lazy 延迟注入 AiConfig，确保 AiConfig 已完成初始化
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        // 强制刷新 AiConfig 配置，确保从数据库读取最新值
        aiConfig.refresh();
        log.info("AiConfig配置: indexName={}, prefix={}", aiConfig.getVectorStore().getIndexName(), aiConfig.getVectorStore().getPrefix());

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
     * 仅包含影响 ChatModel/EmbeddingModel/VectorStore 重建的字段
     */
    private int computeConfigHash() {
        AiConfig.Chat chat = aiConfig.getChat();
        AiConfig.Embedding emb = aiConfig.getEmbedding();
        AiConfig.VectorStore vec = aiConfig.getVectorStore();
        return java.util.Objects.hash(
                chat.getApiKey(), chat.getBaseUrl(), chat.getModel(),
                emb.getApiKey(), emb.getBaseUrl(), emb.getModel(),
                vec.getIndexName(), vec.getPrefix()
        );
    }

    /**
     * 从配置重建AI模型实例
     * 使用OpenAiChatOptions/OpenAiEmbeddingOptions直接设置apiKey和baseUrl，
     * 让Spring AI内部自动创建OpenAI客户端（与旧项目模式一致）
     *
     * <p><b>关于 Redis FT 索引创建的重要说明：</b></p>
     * <p>Spring AI 2.0.0 的 {@code RedisVectorStore.Builder.build()} 方法只是创建普通 Java 对象，
     * 不会触发 Spring 的 {@code InitializingBean.afterPropertiesSet()} 回调。</p>
     * <p>因此，即使设置了 {@code initializeSchema(true)}，FT 索引也不会被自动创建。</p>
     * <p><b>解决方案：</b>在 {@code build()} 之后手动调用 {@code vectorStore.afterPropertiesSet()}
     * 来触发索引创建逻辑。</p>
     *
     * @see RedisVectorStore#afterPropertiesSet()
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

            // 构建ChatModel - 通过Options设置apiKey和baseUrl
            OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                    .apiKey(chatCfg.getApiKey())
                    .baseUrl(chatCfg.getBaseUrl())
                    .model(chatCfg.getModel())
                    .build();
            this.chatModel = OpenAiChatModel.builder()
                    .options(chatOptions)
                    .build();

            // 构建EmbeddingModel - 通过Options设置apiKey和baseUrl
            OpenAiEmbeddingOptions embeddingOptions = OpenAiEmbeddingOptions.builder()
                    .apiKey(embCfg.getApiKey())
                    .baseUrl(embCfg.getBaseUrl())
                    .model(embCfg.getModel())
                    .build();
            this.embeddingModel = OpenAiEmbeddingModel.builder()
                    .options(embeddingOptions)
                    .build();

            // 关闭旧的Redis连接，防止连接泄漏
            if (this.jedisClient != null) {
                try { this.jedisClient.close(); } catch (Exception e) { log.warn("Redis Client 关闭异常", e); }
            }

            // 构建Redis客户端
            RedisClient client = RedisClient.create(redisHost, redisPort, null, redisPassword);
            this.jedisClient = client;

            // 构建VectorStore
            String indexName = vecCfg.getIndexName();
            String prefix = vecCfg.getPrefix();
            log.info("构建VectorStore: indexName={}, prefix={}", indexName, prefix);

            try {
                // 检查现有索引
                var existingIndexes = client.ftList();
                log.info("现有FT索引列表: {}", existingIndexes);

                RedisVectorStore vectorStore = RedisVectorStore.builder(client, embeddingModel)
                        .indexName(indexName)
                        .prefix(prefix)
                        .initializeSchema(true)
                        .build();

                // 手动触发 afterPropertiesSet 来创建 FT 索引
                // 因为 build() 返回的是普通 Java 对象，不会触发 Spring 的 InitializingBean 回调
                log.info("调用 afterPropertiesSet 创建索引...");
                vectorStore.afterPropertiesSet();

                // 验证索引是否创建成功
                var updatedIndexes = client.ftList();
                log.info("创建后FT索引列表: {}", updatedIndexes);

                if (updatedIndexes.contains(indexName)) {
                    log.info("✓ FT索引 '{}' 创建成功!", indexName);
                    try {
                        var indexInfo = client.ftInfo(indexName);
                        log.info("索引信息: {}", indexInfo);
                    } catch (Exception e) {
                        log.warn("获取索引信息失败: {}", e.getMessage());
                    }
                } else {
                    log.error("✗ FT索引 '{}' 创建失败!", indexName);
                }

                this.vectorStore = vectorStore;

                log.info("VectorStore创建成功: indexName={}, prefix={}", indexName, prefix);

                // VectorStore构建成功后才刷新配置哈希与重建时间，确保失败时下次访问可重试
                this.configHash = computeConfigHash();
                this.lastRebuildTime = now;
            } catch (Exception e) {
                log.error("VectorStore创建失败: indexName={}, prefix={}, error={}", indexName, prefix, e.getMessage(), e);
            }

            log.info("AI模型重建成功: chatModel={}, embeddingModel={}, indexName={}, prefix={}",
                    chatCfg.getModel(), embCfg.getModel(), indexName, prefix);
        } catch (Exception e) {
            log.error("AI模型重建失败", e);
        }
    }
}