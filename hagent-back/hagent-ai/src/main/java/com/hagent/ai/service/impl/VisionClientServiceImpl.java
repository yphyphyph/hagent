package com.hagent.ai.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hagent.common.exception.BusinessException;
import com.hagent.ai.model.ImageMcpConfig;
import com.hagent.ai.service.VisionClientService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 视觉 API 调用服务实现
 * <p>
 * 对接 OpenAI 兼容多模态 Chat Completions API，URL 自动规范化，双认证头，60s 超时，结构化错误映射。
 *
 * @author H
 */
@Slf4j
@Service
public class VisionClientServiceImpl implements VisionClientService {

    /** 最大生成 token 数 */
    private static final int MAX_TOKENS = 1024;
    /** 测试连接最大 token 数 */
    private static final int TEST_MAX_TOKENS = 16;
    /** 请求超时（秒） */
    private static final long REQUEST_TIMEOUT = 60L;

    /** 日期格式（英文星期/月份，与原 ImageMCP 实现保持一致） */
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy", Locale.ENGLISH);

    /** HTTP 客户端 */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(REQUEST_TIMEOUT))
            .build();

    /**
     * 发送图片+问题到多模态 API，返回纯文本回答
     *
     * @param dataUri  图片 data URI
     * @param question 关于图片的问题
     * @param config   当前配置
     * @return 多模态模型的纯文本回答
     */
    @Override
    public String chatWithImage(String dataUri, String question, ImageMcpConfig config) {
        JSONObject payload = new JSONObject();
        payload.set("model", config.getModel());
        payload.set("max_completion_tokens", MAX_TOKENS);

        JSONArray messages = new JSONArray();
        messages.add(new JSONObject().set("role", "system").set("content", buildSystemMessage()));

        JSONArray userContent = new JSONArray();
        userContent.add(new JSONObject().set("type", "text").set("text", question));
        userContent.add(new JSONObject()
                .set("type", "image_url")
                .set("image_url", new JSONObject().set("url", dataUri)));
        messages.add(new JSONObject().set("role", "user").set("content", userContent));
        payload.set("messages", messages);

        JSONObject data = post(payload, config);
        return extractContent(data);
    }

    /**
     * 发送最小文本请求验证 地址/密钥/模型
     *
     * @param config 待验证配置
     * @return "连接成功"
     */
    @Override
    public String testConnection(ImageMcpConfig config) {
        JSONObject payload = new JSONObject();
        payload.set("model", config.getModel());
        payload.set("max_completion_tokens", TEST_MAX_TOKENS);
        JSONArray messages = new JSONArray();
        messages.add(new JSONObject().set("role", "user").set("content", "hi"));
        payload.set("messages", messages);
        post(payload, config);
        return "连接成功";
    }

    /**
     * 规范化 apiBase 为 .../v1/chat/completions
     *
     * @param apiBase 原始 apiBase
     * @return 规范化后的完整请求 URL
     */
    private String normalizeApiBase(String apiBase) {
        String base = apiBase.trim().replaceAll("/+$", "");
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        if (!base.endsWith("/v1")) {
            base = base + "/v1";
        }
        return base + "/chat/completions";
    }

    /**
     * 构造 system 消息（含今日日期与知识截止日期）
     *
     * @return system 消息内容
     */
    private String buildSystemMessage() {
        String today = LocalDate.now().format(DATE_FORMATTER);
        return "You are MiMo, an AI assistant developed by Xiaomi. "
                + "Today is date: " + today + ". Your knowledge cutoff date is December 2024.";
    }

    /**
     * 发送 POST 请求并返回 JSON 响应
     *
     * @param payload 请求体
     * @param config  当前配置
     * @return JSON 响应
     */
    private JSONObject post(JSONObject payload, ImageMcpConfig config) {
        String apiBase = config.getApiBase();
        if (!StrUtil.startWith(apiBase, "http://") && !StrUtil.startWith(apiBase, "https://")) {
            throw new BusinessException("api_base 必须以 http:// 或 https:// 开头");
        }
        String url = normalizeApiBase(apiBase);
        String apiKey = config.getApiKey();
        String body = payload.toString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String respBody = response.body();
            if (status == 401) {
                throw new BusinessException("API 密钥无效（HTTP 401），请在配置页面检查 API Key");
            }
            if (status == 429) {
                throw new BusinessException("请求频率超限（HTTP 429），请稍后重试");
            }
            if (status >= 400) {
                String detail = StrUtil.sub(respBody, 0, 300);
                throw new BusinessException("多模态 API 请求失败（HTTP " + status + "）：" + detail);
            }
            try {
                return JSONUtil.parseObj(respBody);
            } catch (Exception e) {
                throw new BusinessException("多模态 API 返回格式异常（非 JSON）：" + StrUtil.sub(respBody, 0, 300));
            }
        } catch (BusinessException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new BusinessException("无法连接多模态 API（请求超时），请检查网络或 api_base 地址：" + apiBase);
        } catch (Exception e) {
            throw new BusinessException("无法连接多模态 API：" + e.getClass().getSimpleName()
                    + "，请检查网络或 api_base 地址：" + apiBase);
        }
    }

    /**
     * 从响应中提取模型回答内容
     *
     * @param data JSON 响应
     * @return 纯文本回答
     */
    private String extractContent(JSONObject data) {
        try {
            JSONArray choices = data.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new BusinessException("多模态 API 返回格式异常，请检查 model 名称是否正确");
            }
            Object content = choices.getJSONObject(0)
                    .getJSONObject("message")
                    .get("content");
            if (content == null || StrUtil.isBlank(content.toString())) {
                throw new BusinessException("多模态 API 返回内容为空，请重试");
            }
            return content.toString().trim();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("多模态 API 返回格式异常，请检查 model 名称是否正确");
        }
    }
}
