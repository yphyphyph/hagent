package com.hagent.server.controller;

import com.hagent.ai.config.AiConfig;
import com.hagent.ai.core.AiModelProvider;
import com.hagent.common.Result;
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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统设置控制器（后台）
 * 提供基于 system_config 表的配置读写；AI 相关配置保存后自动热刷新模型
 *
 * @author H
 */
@Slf4j
@Tag(name = "系统设置", description = "系统与 AI 配置的读写（基于 system_config 表）")
@RestController
@RequestMapping("/admin/system")
@RequiredArgsConstructor
public class SystemSettingsController {

    /** 允许通过接口写入的配置键前缀（白名单，避免任意写库） */
    private static final String[] ALLOWED_PREFIXES = {"ai.", "site."};

    private final SystemConfigService systemConfigService;
    private final AiConfig aiConfig;
    private final AiModelProvider aiModelProvider;

    /**
     * 获取全部系统配置
     *
     * @return 配置 key→value 映射
     * @author H
     */
    @GetMapping("/settings")
    @Operation(summary = "获取系统设置", description = "返回全部 system_config 配置的 key→value")
    public Result<Map<String, String>> settings() {
        return Result.success(systemConfigService.getAll());
    }

    /**
     * 批量保存系统配置；若包含 ai.* 配置，则刷新 AiConfig 并重建模型实例（热生效）
     *
     * @param payload 配置 key→value 映射
     * @return 成功
     * @author H
     */
    @PutMapping("/settings")
    @Operation(summary = "更新系统设置", description = "批量更新配置（仅允许 ai.* / site.*）；AI 配置改动后自动热刷新")
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
     * 判断配置键是否在允许写入的白名单内
     *
     * @param key 配置键
     * @return 是否允许
     * @author H
     */
    private boolean isAllowed(String key) {
        if (!StringUtils.hasText(key)) {
            return false;
        }
        for (String prefix : ALLOWED_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}