package com.hagent.server.auth.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import com.hagent.server.auth.interceptor.UserContextInterceptor;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 鉴权配置：注册登录校验拦截器与用户上下文拦截器
 *
 * @author H
 */
@Configuration
@RequiredArgsConstructor
public class SaTokenConfig implements WebMvcConfigurer {

    private final UserContextInterceptor userContextInterceptor;

    /**
     * 注册拦截器：
     * 1. Sa-Token 登录校验，拦截 /**，排除 /auth/** 与 /error，跳过 OPTIONS 预检与 ASYNC 异步分发
     * 2. 用户上下文拦截器（order=20），登录状态下填充并清理 UserContext
     *
     * @param registry 拦截器注册表
     * @author H
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1. Sa-Token 登录校验拦截器
        registry.addInterceptor(new SaInterceptor(handle -> {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                // 跳过 OPTIONS 预检请求
                if ("OPTIONS".equalsIgnoreCase(attrs.getRequest().getMethod())) {
                    return;
                }
                // 跳过 ASYNC 异步分发（异步完成后再次分发，无完整请求上下文）
                if (DispatcherType.ASYNC == attrs.getRequest().getDispatcherType()) {
                    return;
                }
            }
            // 其余请求统一校验登录状态
            StpUtil.checkLogin();
        })).addPathPatterns("/**")
                /* 门户端 AI 对话对访客开放（匿名可聊，接口自带 @RateLimit 限流保护） */
                .excludePathPatterns("/auth/**", "/error", "/admin/ai/portal/chat")
                .order(1);

        // 2. 用户上下文拦截器
        registry.addInterceptor(userContextInterceptor)
                .addPathPatterns("/**")
                .order(20);
    }
}