package com.hagent.common.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.time.OffsetDateTime;

/**
 * Jackson 配置 - Long 类型统一序列化为字符串，避免 JavaScript 精度丢失
 * <p>
 * 背景：雪花 ID 等 19 位长整型超出 JS Number 安全整数范围（2^53-1），
 * 以 JSON 数字返回时会被前端 JSON.parse 静默舍入末尾几位
 * （如 2075228410665365507 → 2075228410665365500），导致按 ID 查询落空。
 * <p>
 * 双保险：
 * 1. JsonMapperBuilderCustomizer：挂入 Boot 4 自动配置的 JsonMapper 构建器
 *    （HTTP 消息转换器使用的是它，而非下方自定义 ObjectMapper Bean）
 * 2. jacksonObjectMapper Bean：供 @Autowired 注入点使用（同样带模块）
 * <p>
 * 适配 Spring Boot 4.x 的 Jackson 3.x（包名 tools.jackson）
 *
 * @author H
 */
@Configuration
public class JacksonConfig {

    /**
     * Long → String + OffsetDateTime ISO 序列化模块
     * <p>
     * OffsetDateTime 使用 ToStringSerializer 输出 ISO-8601（PG TIMESTAMPTZ 原生）
     */
    private static SimpleModule longToStringModule() {
        SimpleModule module = new SimpleModule("long-to-string");
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        module.addSerializer(OffsetDateTime.class, ToStringSerializer.instance);
        return module;
    }

    /**
     * 挂入 Boot 4 自动配置的 JsonMapper 构建器（HTTP 响应序列化走这里）
     */
    @Bean
    public JsonMapperBuilderCustomizer longToStringSerializerCustomizer() {
        return builder -> builder.addModule(longToStringModule());
    }

    /**
     * 注入点使用的 ObjectMapper（同样带 Long→String 模块）
     * Jackson 3.x 中 ObjectMapper 不可变，需通过 JsonMapper.builder() 注册模块
     */
    @Bean
    public ObjectMapper jacksonObjectMapper() {
        return JsonMapper.builder()
                .addModule(longToStringModule())
                .build();
    }
}
