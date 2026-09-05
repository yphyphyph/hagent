package com.hagent.ai.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hagent.common.service.SystemConfigService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotBlank;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private SystemConfigService configService;

    private Chat chat = new Chat();
    private Embedding embedding = new Embedding();
    private Rerank rerank = new Rerank();
    private VectorStore vectorStore = new VectorStore();
    private Memory memory = new Memory();
    /** 备用对话模型配置（主 + 多级备用 N 级） */
    private FallbackChat fallbackChat = new FallbackChat();
    /** 工具调用配置（轻量 Trace 看板关联，MAX_TOOL_ITERATIONS 配置化） */
    private Tools tools = new Tools();
    /** 会话摘要压缩配置（对应 memory.summary.* 8 键） */
    private MemorySummary memorySummary = new MemorySummary();

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
        // 模型上下文窗口，可在设置页改（非法/越界回退 128000）
        try {
            int contextWindow = Integer.parseInt(getConfigValue("ai.chat.context-window", "128000").trim());
            if (contextWindow < 4096 || contextWindow > 1000000) {
                log.warn("ai.chat.context-window 越界，回退 128000: {}", contextWindow);
                chat.setContextWindow(128000);
            } else {
                chat.setContextWindow(contextWindow);
            }
        } catch (NumberFormatException e) {
            log.warn("ai.chat.context-window 非法，回退 128000: {}", e.getMessage());
            chat.setContextWindow(128000);
        }

        // Embedding config
        embedding.setApiKey(getConfigValue("ai.embedding.api-key", ""));
        embedding.setBaseUrl(getConfigValue("ai.embedding.base-url", ""));
        embedding.setModel(getConfigValue("ai.embedding.model", "text-embedding-v4"));

        // Rerank config（重排模型，Key 为空时调用方复用 embedding Key）
        rerank.setBaseUrl(getConfigValue("ai.rerank.base-url", "https://dashscope.aliyuncs.com/api/v1"));
        rerank.setApiKey(getConfigValue("ai.rerank.api-key", ""));
        rerank.setModel(getConfigValue("ai.rerank.model", "qwen3.7-text-rerank"));

        // VectorStore config（维度非法时回退 1024，避免 NumberFormatException 导致整个 refresh 失败）
        try {
            vectorStore.setDimension(Integer.parseInt(getConfigValue("ai.vector-dimension", "1024")));
        } catch (NumberFormatException e) {
            log.warn("ai.vector-dimension 非法，回退 1024: {}", e.getMessage());
            vectorStore.setDimension(1024);
        }

        // Memory config
        memory.setMemoriesRootDirectory("/data/hagent/memories");
        memory.setEnabled(true);
        memory.setConsolidationProbability(0.05);

        // FallbackChat config：主 + 多级备用 N 级，空数组回退仅主模型
        refreshFallbackChat();

        // Tools config：ai.tools.max-iterations 配置化（system_config 可配置，默认 8）
        refreshTools();

        // MemorySummary config：memory.summary.* 8 键配置化（非法/越界回退默认值）
        refreshMemorySummary();
    }

    /**
     * 刷新备用对话模型配置
     * 从 system_config 读取 ai.fallback.chat.enabled 与 ai.fallback.chat.models（JSON 数组）
     * 失败回退空列表并按 order 排序；order 缺省时按数组下标自动赋值 1..N，主模型为 0
     */
    private void refreshFallbackChat() {
        try {
            String enabledStr = getConfigValue("ai.fallback.chat.enabled", "false");
            fallbackChat.setEnabled(Boolean.parseBoolean(enabledStr));

            String modelsJson = getConfigValue("ai.fallback.chat.models", "[]");
            List<FallbackModel> parsed = parseFallbackModels(modelsJson);
            // order 归一：未设置或非法时按数组下标自动赋值 1..N
            for (int i = 0; i < parsed.size(); i++) {
                FallbackModel m = parsed.get(i);
                if (m.getOrder() <= 0) {
                    m.setOrder(i + 1);
                }
            }
            parsed.sort(Comparator.comparingInt(FallbackModel::getOrder));
            fallbackChat.setModels(parsed);
            log.info("FallbackChat refreshed: enabled={}, models={}", fallbackChat.isEnabled(), parsed.size());
        } catch (Exception e) {
            log.warn("刷新备用模型配置失败，回退为空列表: {}", e.getMessage());
            fallbackChat.setEnabled(false);
            fallbackChat.setModels(new ArrayList<>());
        }
    }

    /**
     * 解析备用模型 JSON 数组，失败回退空列表
     */
    private List<FallbackModel> parseFallbackModels(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        String trimmed = json.trim();
        if ("[]".equals(trimmed) || "{}".equals(trimmed)) {
            return new ArrayList<>();
        }
        try {
            List<FallbackModel> list = OBJECT_MAPPER.readValue(trimmed,
                    new TypeReference<List<FallbackModel>>() {});
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            log.warn("解析 ai.fallback.chat.models 失败，JSON 非法，回退空列表: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 刷新工具调用配置
     * 从 system_config 读取 ai.tools.max-iterations，非法值回退 8
     *
     * @author H
     */
    private void refreshTools() {
        try {
            String raw = getConfigValue("ai.tools.max-iterations", "8");
            int v = Integer.parseInt(raw.trim());
            // 边界收敛：1..20，超出截断
            if (v < 1) {
                v = 1;
            } else if (v > 20) {
                v = 20;
            }
            tools.setMaxIterations(v);
            log.info("Tools refreshed: maxIterations={}", v);
        } catch (NumberFormatException e) {
            log.warn("ai.tools.max-iterations 非法，回退 8: {}", e.getMessage());
            tools.setMaxIterations(8);
        } catch (Exception e) {
            log.warn("刷新工具配置失败，回退 8: {}", e.getMessage());
            tools.setMaxIterations(8);
        }
    }

    /**
     * 刷新会话摘要压缩配置
     * 从 system_config 读取 memory.summary.* 8 键，非法/越界回退默认值
     *
     * @author H
     */
    private void refreshMemorySummary() {
        memorySummary.setEnabled(Boolean.parseBoolean(getConfigValue("memory.summary.enabled", "true").trim()));
        memorySummary.setTriggerTurns(parseMemorySummaryInt("memory.summary.trigger-turns", 20, 1, 100));
        memorySummary.setKeepTurns(parseMemorySummaryInt("memory.summary.keep-turns", 10, 1, 50));
        memorySummary.setOverlapTurns(parseMemorySummaryInt("memory.summary.overlap-turns", 1, 0, 5));
        memorySummary.setMaxCharsSingle(parseMemorySummaryInt("memory.summary.max-chars-single", 4000, 500, 20000));
        memorySummary.setMaxCharsTotal(parseMemorySummaryInt("memory.summary.max-chars-total", 12000, 2000, 100000));
        memorySummary.setTargetTokens(parseMemorySummaryInt("memory.summary.target-tokens", 1024, 128, 4096));
        memorySummary.setIntervalTurns(parseMemorySummaryInt("memory.summary.interval-turns", 10, 1, 50));
        log.info("MemorySummary refreshed: enabled={}, triggerTurns={}, keepTurns={}, overlapTurns={},"
                + " maxCharsSingle={}, maxCharsTotal={}, targetTokens={}, intervalTurns={}",
                memorySummary.isEnabled(), memorySummary.getTriggerTurns(), memorySummary.getKeepTurns(),
                memorySummary.getOverlapTurns(), memorySummary.getMaxCharsSingle(), memorySummary.getMaxCharsTotal(),
                memorySummary.getTargetTokens(), memorySummary.getIntervalTurns());
    }

    /**
     * 解析 memory.summary.* 整数配置，非法/越界回退默认值
     *
     * @param key 配置键
     * @param defaultValue 默认值
     * @param min 最小值（含）
     * @param max 最大值（含）
     * @return 合法值或默认值
     * @author H
     */
    private int parseMemorySummaryInt(String key, int defaultValue, int min, int max) {
        try {
            int v = Integer.parseInt(getConfigValue(key, String.valueOf(defaultValue)).trim());
            if (v < min || v > max) {
                log.warn("{} 越界，回退 {}: {}", key, defaultValue, v);
                return defaultValue;
            }
            return v;
        } catch (NumberFormatException e) {
            log.warn("{} 非法，回退 {}: {}", key, defaultValue, e.getMessage());
            return defaultValue;
        }
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
        /** 模型上下文窗口，可在设置页改（默认 128000，范围 4096..1000000） */
        private Integer contextWindow = 128000;
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

    /**
     * 重排模型配置
     * 对应 system_config 键：ai.rerank.base-url / ai.rerank.api-key / ai.rerank.model
     * apiKey 为空时调用方复用 ai.embedding.api-key
     *
     * @author H
     */
    @Data
    public static class Rerank {
        /** 重排接口基址（默认百炼） */
        private String baseUrl = "https://dashscope.aliyuncs.com/api/v1";
        /** API Key（允许空，空时复用 embedding Key） */
        private String apiKey = "";
        /** 重排模型名称 */
        private String model = "qwen3.7-text-rerank";
    }

    @Data
    public static class VectorStore {
        /** 向量维度（需与 embedding 模型输出一致；text-embedding-v3/v4 默认 1024） */
        private int dimension = 1024;
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

    /**
     * 会话摘要压缩配置
     * 对应 system_config 键：memory.summary.* 共 8 键
     * 拼装顺序 system（永不裁）→ running summary 五段 → 近 keepTurns 轮原文 → 最新 user 必留
     *
     * @author H
     */
    @Data
    public static class MemorySummary {
        /** 是否启用会话摘要压缩 */
        private boolean enabled = true;
        /** 触发压缩的消息条数阈值（默认 20，约 10 轮，范围 1..100） */
        private int triggerTurns = 20;
        /** 压缩后保留的最近轮数（整轮保留，范围 1..50） */
        private int keepTurns = 10;
        /** 滑动窗口重叠轮数（范围 0..5） */
        private int overlapTurns = 1;
        /** 单条消息最大字符数（范围 500..20000） */
        private int maxCharsSingle = 4000;
        /** 摘要输入总量最大字符数（范围 2000..100000） */
        private int maxCharsTotal = 12000;
        /** 摘要目标 token 数（范围 128..4096） */
        private int targetTokens = 1024;
        /** 首次压缩后每新增轮数再次压缩（范围 1..50） */
        private int intervalTurns = 10;
    }

    /**
     * 备用对话模型配置（主 + 多级备用 N 级）
     * 对应 system_config 键：ai.fallback.chat.enabled / ai.fallback.chat.models
     * models 为 JSON 数组，空数组表示仅主模型
     *
     * @author H
     */
    @Data
    public static class FallbackChat {
        /** 是否启用备用模型降级 */
        private boolean enabled = false;
        /** 备用模型列表，按 order 升序排序，主模型 order=0，备用 1..N */
        private List<FallbackModel> models = new ArrayList<>();
    }

    /**
     * 工具调用配置
     * 对应 system_config 键：ai.tools.max-iterations
     * 同时暴露为配置属性 ai.tools.max-iterations 供 @Value 注入兼容
     *
     * @author H
     */
    @Data
    public static class Tools {
        /** 工具调用最大迭代次数（默认 8，范围 1..20） */
        private int maxIterations = 8;
    }

    /**
     * 单个备用模型配置
     * 仅替换三字段：name / baseUrl / apiKey；其余参数复用主模型
     * apiKey 允许空时后端复用主 Key（AiModelProvider 已处理），无需前端传
     *
     * @author H
     */
    @Data
    public static class FallbackModel {
        /** 模型名称 */
        @NotBlank(message = "备用模型名称不能为空")
        private String name = "";
        /** API 基础 URL */
        @NotBlank(message = "备用模型 baseUrl 不能为空")
        private String baseUrl = "";
        /** API Key（允许空，空时复用主 Key） */
        private String apiKey = "";
        /** 排序序号，主=0，备用按数组下标 1..N 自动赋值 */
        private int order = 0;
    }
}
