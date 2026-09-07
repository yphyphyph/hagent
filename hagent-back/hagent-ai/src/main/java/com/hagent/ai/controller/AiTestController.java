package com.hagent.ai.controller;

import com.hagent.ai.core.OpenCodeGatewayHeaders;
import com.hagent.common.Result;
import com.hagent.common.annotation.RateLimit;
import com.hagent.common.service.SystemConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 连接测试控制器
 * 使用前端传入的临时配置真实调用一次模型，验证 API Key / 地址 / 模型是否可用
 * 前端回显的 apiKey 为脱敏占位符（***）时，自动回退 system_config 已保存的真实密钥
 *
 * @author H
 */
@Slf4j
@Tag(name = "AI连接测试", description = "使用前端传入的临时配置真实调用一次模型以验证连接可用性接口；apiKey 为空或 *** 时回退已保存配置")
@RestController
@RequestMapping("/admin/ai")
@RequiredArgsConstructor
public class AiTestController {

    /** 系统配置服务，用于 apiKey 为脱敏占位符时回退读取已保存的真实密钥 */
    private final SystemConfigService systemConfigService;

    /**
     * 测试 AI 连接
     *
     * @param request 测试配置（baseUrl/apiKey/model）
     * @return 测试结果
     */
    @PostMapping("/test")
    @Operation(summary = "测试AI连接", description = "使用传入的 baseUrl/apiKey/model 真实调用一次模型；apiKey 为空或脱敏占位符 *** 时自动回退 system_config 的 ai.chat.api-key")
    @ApiResponse(responseCode = "200", description = "测试完成")
    @RateLimit(window = 60, limit = 10, message = "测试请求过于频繁，请稍后重试")
    public Result<Map<String, Object>> test(@RequestBody AiTestRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (request == null || !StringUtils.hasText(request.getBaseUrl()) || !StringUtils.hasText(request.getModel())) {
            data.put("status", "error");
            data.put("message", "缺少必要参数：baseUrl / model");
            return Result.error(400, "缺少必要参数");
        }
        // apiKey 为空或前端回显的脱敏占位符（***）时，回退已保存的真实密钥，保证测试针对生效配置 @author H
        String apiKey = resolveApiKey(request.getApiKey(), "ai.chat.api-key");
        if (!StringUtils.hasText(apiKey)) {
            data.put("status", "error");
            data.put("message", "API Key 未填写且系统配置中未保存 ai.chat.api-key，请填写真实 Key 或先保存配置");
            return Result.error(400, "缺少有效 API Key");
        }
        try {
            // 连接测试同样注入网关会话头（测试目标为 opencode 网关时缺失会 400；其他供应商忽略未知头无副作用） @author H
            OpenAiChatOptions options = OpenCodeGatewayHeaders.applyTo(OpenAiChatOptions.builder()
                    .apiKey(apiKey)
                    .baseUrl(request.getBaseUrl())
                    .model(request.getModel()), null)
                    .build();
            OpenAiChatModel model = OpenAiChatModel.builder().options(options).build();
            ChatResponse response = model.call(new Prompt("你好"));
            String reply = response != null && response.getResult() != null && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getText() : "";
            log.info("AI连接测试成功: model={}, replyLen={}", request.getModel(),
                    reply != null ? reply.length() : 0);
            data.put("status", "success");
            data.put("message", "连接测试成功！AI 模型可正常使用");
            data.put("reply", reply);
            return Result.success(data);
        } catch (Exception e) {
            log.warn("AI连接测试失败: model={}, error={}", request.getModel(), e.getMessage());
            data.put("status", "error");
            data.put("message", "连接测试失败: " + e.getMessage());
            return Result.error(500, "连接测试失败: " + e.getMessage());
        }
    }

    /**
     * 测试向量模型连接
     * 用临时配置真实调一次 embedding，返回维度与前 3 维预览
     *
     * @param request 测试配置（baseUrl/apiKey/model）
     * @return 测试结果
     */
    @PostMapping("/embedding/test")
    @Operation(summary = "测试向量模型连接", description = "使用传入的 baseUrl/apiKey/model 真实调用一次向量模型；apiKey 为空或脱敏占位符 *** 时自动回退 system_config 的 ai.embedding.api-key")
    @ApiResponse(responseCode = "200", description = "测试完成")
    @RateLimit(window = 60, limit = 10, message = "测试请求过于频繁，请稍后重试")
    public Result<Map<String, Object>> testEmbedding(@RequestBody AiTestRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (request == null || !StringUtils.hasText(request.getBaseUrl()) || !StringUtils.hasText(request.getModel())) {
            data.put("status", "error");
            data.put("message", "缺少必要参数：baseUrl / model");
            return Result.error(400, "缺少必要参数");
        }
        // 与文本测试一致：占位符/空 Key 回退已保存的向量密钥 @author H
        String apiKey = resolveApiKey(request.getApiKey(), "ai.embedding.api-key");
        if (!StringUtils.hasText(apiKey)) {
            data.put("status", "error");
            data.put("message", "API Key 未填写且系统配置中未保存 ai.embedding.api-key，请填写真实 Key 或先保存配置");
            return Result.error(400, "缺少有效 API Key");
        }
        try {
            OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                    .apiKey(apiKey)
                    .baseUrl(request.getBaseUrl())
                    .model(request.getModel())
                    .build();
            OpenAiEmbeddingModel model = OpenAiEmbeddingModel.builder().options(options).build();
            EmbeddingResponse response = model.call(new org.springframework.ai.embedding.EmbeddingRequest(
                    java.util.List.of("hello"), options));
            if (response == null || response.getResults() == null || response.getResults().isEmpty()
                    || response.getResults().get(0).getOutput() == null) {
                data.put("status", "error");
                data.put("message", "向量模型返回为空");
                return Result.error(500, "向量模型返回为空");
            }
            float[] vec = response.getResults().get(0).getOutput();
            int dim = vec != null ? vec.length : 0;
            String preview = "";
            if (vec != null && vec.length >= 3) {
                preview = String.format("[%.5f, %.5f, %.5f, ...]", vec[0], vec[1], vec[2]);
            } else if (vec != null && vec.length > 0) {
                preview = java.util.Arrays.toString(vec);
            }
            log.info("向量模型连接测试成功: model={}, dimension={}", request.getModel(), dim);
            data.put("status", "success");
            data.put("message", "向量模型连接成功，维度 " + dim);
            data.put("dimension", dim);
            data.put("preview", preview);
            return Result.success(data);
        } catch (Exception e) {
            log.warn("向量模型连接测试失败: model={}, error={}", request.getModel(), e.getMessage());
            data.put("status", "error");
            data.put("message", "向量模型连接失败: " + e.getMessage());
            return Result.error(500, "向量模型连接失败: " + e.getMessage());
        }
    }

    /**
     * 解析测试用 API Key：前端传空或脱敏占位符（如 ***）时回退 system_config 已保存密钥
     * 占位符语义与 PUT /admin/system/settings 的脱敏哨兵一致（*** 表示沿用已保存值，不覆盖）
     *
     * @param passedKey 前端传入的 Key（可能为空或 ***）
     * @param configKey 回退读取的配置键（如 ai.chat.api-key / ai.embedding.api-key）
     * @return 可用于真实调用的 Key，无法解析时返回空串
     * @author H
     */
    private String resolveApiKey(String passedKey, String configKey) {
        String trimmed = passedKey == null ? "" : passedKey.trim();
        if (StringUtils.hasText(trimmed) && !isMaskedPlaceholder(trimmed)) {
            return trimmed;
        }
        String stored = systemConfigService.getValueByKey(configKey);
        if (StringUtils.hasText(stored) && !isMaskedPlaceholder(stored.trim())) {
            log.info("AI连接测试使用已保存密钥回退: configKey={}", configKey);
            return stored.trim();
        }
        return "";
    }

    /**
     * 判断是否为脱敏占位符（全为星号如 *** / ****，或展示脱敏格式如 sk-ab****wxyz）
     *
     * @param value 待判断的值
     * @return 是否为占位符
     * @author H
     */
    private boolean isMaskedPlaceholder(String value) {
        return value.matches("^\\*+$") || value.contains("****");
    }

    /**
     * AI 连接测试请求体
     */
    @Data
    public static class AiTestRequest {
        /** API 地址 */
        private String baseUrl;
        /** API 密钥（可为空或 *** ，回退已保存配置） */
        private String apiKey;
        /** 模型名称 */
        private String model;
    }
}
