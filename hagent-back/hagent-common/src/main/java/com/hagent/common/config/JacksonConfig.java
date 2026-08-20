package com.hagent.common.config;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson配置 - 将Long类型序列化为字符串，避免JavaScript精度丢失
 * 适配 Spring Boot 4.x 的 Jackson 3.x（包名 tools.jackson）
 *
 * @author H
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper jacksonObjectMapper() {
        SimpleModule simpleModule = new SimpleModule();
        // 将Long类型序列化为字符串
        simpleModule.addSerializer(Long.class, ToStringSerializer.instance);
        simpleModule.addSerializer(Long.TYPE, ToStringSerializer.instance);
        // Jackson 3.x 中 ObjectMapper 不可变，需通过 JsonMapper.builder() 注册模块
        return JsonMapper.builder()
                .addModule(simpleModule)
                .build();
    }
}
