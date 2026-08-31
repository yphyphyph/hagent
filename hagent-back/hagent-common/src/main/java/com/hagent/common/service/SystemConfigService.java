package com.hagent.common.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hagent.common.entity.SystemConfig;
import com.hagent.common.mapper.SystemConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统配置服务 - 从system_config表读取配置
 * 白名单已追加 ai.fallback.chat.enabled 与 ai.fallback.chat.models（T3）
 * AiConfig.refresh() 后由调用方触发 AiModelProvider.rebuild() 热刷新（Controller 已处理，如已有监听则保持）
 *
 * @author H
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemConfigService {

    /** 允许写入的配置键前缀白名单（已追加 ai.fallback.chat.*，兼容 ai.chat.* / ai.embedding.*） */
    private static final String[] ALLOWED_PREFIXES = {"ai.", "site."};

    /** 备用模型白名单键（显式列出，便于校验与审计） */
    private static final java.util.Set<String> FALLBACK_WHITE_LIST = java.util.Set.of(
            "ai.fallback.chat.enabled", "ai.fallback.chat.models");

    private final SystemConfigMapper systemConfigMapper;

    /**
     * 根据key获取配置值
     *
     * @param key 配置键
     * @return 配置值，不存在时返回null
     */
    public String getValueByKey(String key) {
        LambdaQueryWrapper<SystemConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SystemConfig::getConfigKey, key);
        SystemConfig config = systemConfigMapper.selectOne(wrapper);
        if (config != null) {
            log.debug("读取配置: {} = {}", key, maskSensitive(key, config.getConfigValue()));
            return config.getConfigValue();
        }
        log.debug("配置不存在: {}", key);
        return null;
    }

    /**
     * 根据key获取配置值，带默认值
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 配置值，不存在时返回默认值
     */
    public String getValueByKey(String key, String defaultValue) {
        String value = getValueByKey(key);
        return value != null ? value : defaultValue;
    }

    /**
     * 查询全部配置（非逻辑删除），以 config_key → config_value 返回
     *
     * @return 全部配置映射
     * @author H
     */
    public Map<String, String> getAll() {
        List<SystemConfig> list = systemConfigMapper.selectList(null);
        Map<String, String> map = new LinkedHashMap<>();
        for (SystemConfig c : list) {
            if (c.getConfigKey() != null) {
                map.put(c.getConfigKey(), c.getConfigValue());
            }
        }
        return map;
    }

    /**
     * 保存配置值（存在则更新，不存在则插入）
     * 按配置键前缀推断配置类型（ai.*→ai，site.*→site，默认→system），并发安全由唯一索引 uk_config_key 保证
     *
     * @param key   配置键
     * @param value 配置值
     * @author H
     */
    public void saveValue(String key, String value) {
        if (key == null || key.isBlank()) {
            return;
        }
        String type = inferType(key);
        LambdaQueryWrapper<SystemConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SystemConfig::getConfigKey, key);
        SystemConfig config = systemConfigMapper.selectOne(wrapper);
        if (config != null) {
            config.setConfigValue(value);
            config.setConfigType(type);
            systemConfigMapper.updateById(config);
            log.debug("更新配置: {} = {}", key, maskSensitive(key, value));
        } else {
            SystemConfig newConfig = new SystemConfig();
            newConfig.setConfigKey(key);
            newConfig.setConfigValue(value);
            newConfig.setConfigType(type);
            newConfig.setIsPublic(0);
            newConfig.setDescription(type + " 配置");
            systemConfigMapper.insert(newConfig);
            log.debug("新增配置: {} = {}", key, maskSensitive(key, value));
        }
    }

    /**
     * 批量保存（白名单校验，已追加 ai.fallback.chat.enabled / ai.fallback.chat.models）
     *
     * @param kv 配置映射
     * @author H
     */
    public void batchSave(Map<String, String> kv) {
        if (kv == null || kv.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> e : kv.entrySet()) {
            String k = e.getKey();
            if (!isAllowed(k)) {
                log.warn("忽略不允许的配置键: {}", k);
                continue;
            }
            saveValue(k, e.getValue());
        }
    }

    /**
     * 白名单校验（兼容 ai.chat.* / ai.embedding.*，已追加 ai.fallback.chat.*）
     *
     * @param key 配置键
     * @return 是否允许写入
     * @author H
     */
    public boolean isAllowed(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        if (FALLBACK_WHITE_LIST.contains(key)) {
            return true;
        }
        for (String prefix : ALLOWED_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按配置键前缀推断配置类型
     *
     * @param key 配置键
     * @return 配置类型
     * @author H
     */
    private String inferType(String key) {
        if (key.startsWith("ai.")) {
            return "ai";
        }
        if (key.startsWith("site.")) {
            return "site";
        }
        return "system";
    }

    /**
     * 对敏感配置值进行脱敏
     */
    private String maskSensitive(String key, String value) {
        if (value == null) return null;
        if (key.contains("api-key") || key.contains("password") || key.contains("secret")) {
            if (value.length() > 8) {
                return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
            }
            return "****";
        }
        return value;
    }
}
