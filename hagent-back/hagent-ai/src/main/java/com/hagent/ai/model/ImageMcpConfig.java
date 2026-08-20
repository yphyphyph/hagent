package com.hagent.ai.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * ImageMCP 运行时配置 POJO
 * <p>
 * 包含多模态 API 调用所需的三项核心配置：apiBase、apiKey、model。
 * 配置来源为 system_config 表，由 VisionConfigService 加载。
 *
 * @author H
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageMcpConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 多模态 API 基础地址（OpenAI 兼容） */
    private String apiBase;

    /** 多模态 API 密钥 */
    private String apiKey;

    /** 模型 ID */
    private String model;
}
