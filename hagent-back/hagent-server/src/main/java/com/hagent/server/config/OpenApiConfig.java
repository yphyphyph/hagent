package com.hagent.server.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 接口文档配置
 *
 * @author H
 */
@Configuration
public class OpenApiConfig {

    /**
     * 配置 OpenAPI 文档元信息
     *
     * @return OpenAPI 文档对象
     */
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("HAgent")
                        .version("1.0")
                        .description("HAgent 后端服务接口文档"));
    }
}