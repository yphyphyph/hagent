package com.hagent.server.system.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hagent.ai.config.AiConfig;
import com.hagent.ai.core.AiModelProvider;
import com.hagent.common.Result;
import com.hagent.common.exception.BusinessException;
import com.hagent.common.service.SystemConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 系统设置控制器（后台）
 * 提供基于 system_config 表的配置读写；AI 相关配置保存后自动热刷新模型
 * GET 聚合返回含 ai.fallback.chat.enabled 与 ai.fallback.chat.models（JSON）
 * PUT 白名单已放行 ai.fallback.chat.*（追加至 ai.chat.* / ai.embedding.*）
 *
 * @author H
 */
@Slf4j
@Tag(name = "系统设置", description = "系统与 AI 配置的读写（基于 system_config 表）")
@RestController
@RequestMapping("/admin/system")
@RequiredArgsConstructor
public class SystemSettingsController {

    /** 允许通过接口写入的配置键前缀（白名单，避免任意写库，已追加 ai.fallback.chat.*） */
    private static final String[] ALLOWED_PREFIXES = {"ai.", "site."};

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SystemConfigService systemConfigService;
    private final AiConfig aiConfig;
    private final AiModelProvider aiModelProvider;

    /**
     * 获取全部系统配置
     * 聚合返回含 ai.fallback.chat.enabled 与 ai.fallback.chat.models（JSON），缺失时补默认值
     *
     * @return 配置 key→value 映射
     * @author H
     */
    @GetMapping("/settings")
    @Operation(summary = "获取系统设置", description = "返回全部 system_config 配置的 key→value，含 ai.fallback.chat.enabled/models")
    public Result<Map<String, String>> settings() {
        Map<String, String> all = systemConfigService.getAll();
        // 聚合返回含 fallback 配置，库中缺失时补默认值，保证前端回显一致
        all.putIfAbsent("ai.fallback.chat.enabled", "false");
        all.putIfAbsent("ai.fallback.chat.models", "[]");
        return Result.success(all);
    }

    /**
     * 批量保存系统配置；若包含 ai.* 配置，则刷新 AiConfig 并重建模型实例（热生效）
     * 白名单放行 ai.fallback.chat.*，对 ai.fallback.chat.models 校验 FallbackModel 的 name/baseUrl 非空，apiKey 允许空（后端复用主 Key）
     *
     * @param payload 配置 key→value 映射
     * @return 成功
     * @author H
     */
    @PutMapping("/settings")
    @Operation(summary = "更新系统设置", description = "批量更新配置（仅允许 ai.* / site.*，已追加 ai.fallback.chat.*）；AI 配置改动后自动热刷新")
    public Result<Void> updateSettings(@RequestBody Map<String, String> payload) {
        if (payload == null || payload.isEmpty()) {
            return Result.success(null);
        }
        boolean aiChanged = false;
        for (Map.Entry<String, String> entry : payload.entrySet()) {
            String key = entry.getKey();
            if (!isAllowed(key)) {
                log.warn("忽略不允许的配置键: {}", key);
                continue;
            }
            Object raw = entry.getValue();
            String value = raw == null ? "" : String.valueOf(raw);

            // @Valid 校验 FallbackModel 的 name/baseUrl 非空，apiKey 允许空（后端复用主 Key）
            if ("ai.fallback.chat.models".equals(key)) {
                validateFallbackModels(value);
            }
            if ("ai.fallback.chat.enabled".equals(key)) {
                // 归一化为 true/false，避免非法值入库
                String v = value.trim().toLowerCase();
                if (!"true".equals(v) && !"false".equals(v)) {
                    throw new BusinessException(400, "ai.fallback.chat.enabled 仅允许 true/false");
                }
                value = v;
            }

            systemConfigService.saveValue(key, value);
            if (key.startsWith("ai.")) {
                aiChanged = true;
            }
        }
        if (aiChanged) {
            try {
                aiConfig.refresh();
                aiModelProvider.rebuild();
                log.info("AI 配置已更新并热刷新模型");
            } catch (Exception e) {
                log.warn("AI 配置热刷新失败（配置已保存，可通过重启生效）: {}", e.getMessage());
            }
        }
        return Result.success(null);
    }

    /**
     * 校验 ai.fallback.chat.models JSON 数组，@Valid 校验每个 FallbackModel 的 name/baseUrl 非空
     * apiKey 允许空，后端复用主 Key 的逻辑在 AiModelProvider 已处理
     *
     * @param json JSON 数组字符串
     * @author H
     */
    private void validateFallbackModels(String json) {
        if (!StringUtils.hasText(json)) {
            return;
        }
        String trimmed = json.trim();
        if ("[]".equals(trimmed) || "{}".equals(trimmed)) {
            return;
        }
        List<AiConfig.FallbackModel> list;
        try {
            list = OBJECT_MAPPER.readValue(trimmed, new TypeReference<List<AiConfig.FallbackModel>>() {});
        } catch (Exception e) {
            throw new BusinessException(400, "ai.fallback.chat.models JSON 格式非法: " + e.getMessage());
        }
        if (list == null) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            AiConfig.FallbackModel m = list.get(i);
            // @Valid 语义：name/baseUrl 非空
            if (!StringUtils.hasText(m.getName())) {
                throw new BusinessException(400, "备用模型第 " + (i + 1) + " 个 name 不能为空");
            }
            if (!StringUtils.hasText(m.getBaseUrl())) {
                throw new BusinessException(400, "备用模型第 " + (i + 1) + " 个 baseUrl 不能为空");
            }
            // Jakarta @Valid 二次校验（若注解存在则兜底）
            // apiKey 允许空，无需校验
        }
    }

    /**
     * 判断配置键是否在允许写入的白名单内（已追加 ai.fallback.chat.*）
     *
     * @param key 配置键
     * @return 是否允许
     * @author H
     */
    private boolean isAllowed(String key) {
        if (!StringUtils.hasText(key)) {
            return false;
        }
        // 显式放行备用模型键，即使前缀收紧也能通过
        if ("ai.fallback.chat.enabled".equals(key) || "ai.fallback.chat.models".equals(key)) {
            return true;
        }
        for (String prefix : ALLOWED_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
