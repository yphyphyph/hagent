package com.hagent.ai.service;

import cn.hutool.core.util.StrUtil;
import com.hagent.ai.model.ImageMcpConfig;
import com.hagent.common.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 视觉模型配置服务
 * <p>
 * 从 system_config 表读取多模态视觉模型配置（apiBase/apiKey/model），
 * 替代原 ImageMCP 项目中基于本地文件的 ConfigService。
 * <p>
 * 配置键约定：
 * - ai.vision.base-url：多模态 API 基础地址
 * - ai.vision.api-key：多模态 API 密钥
 * - ai.vision.model：模型 ID
 *
 * @author H
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VisionConfigService {

    /** system_config 配置键 - 视觉 API 基础地址 */
    private static final String CONFIG_KEY_API_BASE = "ai.vision.base-url";

    /** system_config 配置键 - 视觉 API 密钥 */
    private static final String CONFIG_KEY_API_KEY = "ai.vision.api-key";

    /** system_config 配置键 - 视觉模型 ID */
    private static final String CONFIG_KEY_MODEL = "ai.vision.model";

    private final SystemConfigService systemConfigService;

    /**
     * 获取当前生效的视觉模型配置
     * <p>
     * 每次实时从 system_config 表读取，配置变更后立即生效。
     *
     * @return 当前配置（字段可能为 null）
     */
    public ImageMcpConfig getCurrentConfig() {
        String apiBase = systemConfigService.getValueByKey(CONFIG_KEY_API_BASE);
        String apiKey = systemConfigService.getValueByKey(CONFIG_KEY_API_KEY);
        String model = systemConfigService.getValueByKey(CONFIG_KEY_MODEL);
        return new ImageMcpConfig(apiBase, apiKey, model);
    }

    /**
     * 判断当前视觉模型配置是否已正确填写
     * <p>
     * 要求 apiBase 为 http(s) 开头、apiKey 非空、model 非空。
     *
     * @return 是否已配置
     */
    public boolean isConfigured() {
        return isConfigured(getCurrentConfig());
    }

    /**
     * 判断指定配置是否已正确填写
     *
     * @param config 待判断配置
     * @return 是否已配置
     */
    private boolean isConfigured(ImageMcpConfig config) {
        if (config == null) {
            return false;
        }
        return (StrUtil.startWith(config.getApiBase(), "http://")
                || StrUtil.startWith(config.getApiBase(), "https://"))
                && StrUtil.isNotBlank(config.getApiKey())
                && StrUtil.isNotBlank(config.getModel());
    }
}
