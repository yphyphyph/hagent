package com.hagent.ai.controller;

import com.hagent.common.Result;
import com.hagent.common.annotation.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
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
 *
 * @author H
 */
@Slf4j
@Tag(name = "AI连接测试", description = "使用前端传入的临时配置真实调用一次模型以验证连接可用性接口")
@RestController
@RequestMapping("/admin/ai")
public class AiTestController {

    /**
     * 测试 AI 连接
     *
     * @param request 测试配置（baseUrl/apiKey/model）
     * @return 测试结果
     */
    @PostMapping("/test")
    @Operation(summary = "测试AI连接", description = "使用传入的 baseUrl/apiKey/model 真实调用一次模型，验证配置是否可用")
    @ApiResponse(responseCode = "200", description = "测试完成")
    @RateLimit(window = 60, limit = 10, message = "测试请求过于频繁，请稍后重试")
    public Result<Map<String, Object>> test(@RequestBody AiTestRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (request == null || !StringUtils.hasText(request.getApiKey())
                || !StringUtils.hasText(request.getBaseUrl()) || !StringUtils.hasText(request.getModel())) {
            data.put("status", "error");
            data.put("message", "缺少必要参数：apiKey / baseUrl / model");
            return Result.error(400, "缺少必要参数");
        }
        try {
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .apiKey(request.getApiKey())
                    .baseUrl(request.getBaseUrl())
                    .model(request.getModel())
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
    @Operation(summary = "测试向量模型连接", description = "使用传入的 baseUrl/apiKey/model 真实调用一次向量模型，验证配置与维度")
    @ApiResponse(responseCode = "200", description = "测试完成")
    @RateLimit(window = 60, limit = 10, message = "测试请求过于频繁，请稍后重试")
    public Result<Map<String, Object>> testEmbedding(@RequestBody AiTestRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (request == null || !StringUtils.hasText(request.getApiKey())
                || !StringUtils.hasText(request.getBaseUrl()) || !StringUtils.hasText(request.getModel())) {
            data.put("status", "error");
            data.put("message", "缺少必要参数：apiKey / baseUrl / model");
            return Result.error(400, "缺少必要参数");
        }
        try {
            OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                    .apiKey(request.getApiKey())
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
     * AI 连接测试请求体
     */
    @Data
    public static class AiTestRequest {
        /** API 地址 */
        private String baseUrl;
        /** API 密钥 */
        private String apiKey;
        /** 模型名称 */
        private String model;
    }
}
