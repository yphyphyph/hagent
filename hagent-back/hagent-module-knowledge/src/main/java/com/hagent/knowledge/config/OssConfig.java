package com.hagent.knowledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 阿里云OSS配置属性
 * 从 application.yml 中的 aliyun.oss.* 前缀读取
 *
 * @author H
 */
@Data
@Component
@ConfigurationProperties(prefix = "aliyun.oss")
public class OssConfig {

    /** OSS端点 */
    private String endpoint;

    /** Access Key ID */
    private String accessKeyId;

    /** Access Key Secret */
    private String accessKeySecret;

    /** 存储桶名称 */
    private String bucketName;
}
