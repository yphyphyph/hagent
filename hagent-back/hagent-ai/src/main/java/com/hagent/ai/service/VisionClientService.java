package com.hagent.ai.service;

import com.hagent.ai.model.ImageMcpConfig;

/**
 * 视觉 API 调用服务接口
 * <p>
 * 对接 OpenAI 兼容多模态 Chat Completions API，提供图片识别与连接测试能力。
 *
 * @author H
 */
public interface VisionClientService {

    /**
     * 发送图片+问题到多模态 API，返回纯文本回答
     *
     * @param dataUri  图片 data URI（data:{mime};base64,...）
     * @param question 关于图片的问题或指令
     * @param config   当前生效配置
     * @return 多模态模型的纯文本回答
     */
    String chatWithImage(String dataUri, String question, ImageMcpConfig config);

    /**
     * 发送最小文本请求验证 地址/密钥/模型 是否可用
     *
     * @param config 待验证配置
     * @return "连接成功"
     */
    String testConnection(ImageMcpConfig config);
}
