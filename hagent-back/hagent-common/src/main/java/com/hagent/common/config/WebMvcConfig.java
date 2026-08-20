package com.hagent.common.config;

import com.hagent.common.interceptor.JwtAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC配置 - JWT拦截器和CORS设置
 *
 * @author H
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtAuthInterceptor jwtAuthInterceptor;

    /** 允许的跨域源（从配置读取，逗号分隔转为数组），支持端口通配符如 http://localhost:* */
    @Value("${hui.cors.allowed-origins:http://localhost:*}")
    private String[] allowedOrigins;

    /**
     * 已迁移至 Sa-Token 鉴权（由 hagent-server 的 SaInterceptor 统一处理登录校验），
     * 此处不再注册 JwtAuthInterceptor，保持空实现
     *
     * @param registry 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 空实现：鉴权交予 Sa-Token 处理
    }

    /**
     * CORS跨域配置 - 白名单模式
     * 通过 hui.cors.allowed-origins 配置允许的源，禁止使用 "*" + allowCredentials 组合
     * 使用 allowedOriginPatterns 而非 allowedOrigins：既兼容 allowCredentials(true)，
     * 又支持端口通配（如 http://localhost:*），无通配符时等价于精确匹配，不影响生产安全
     *
     * @param registry CORS注册表
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "X-Requested-With", "Accept", "Origin")
                .exposedHeaders("Authorization")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
