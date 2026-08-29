package com.hagent.server.auth.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import com.hagent.common.annotation.RequireAdmin;
import com.hagent.common.constant.RoleConstants;
import com.hagent.common.context.UserContext;
import com.hagent.server.auth.mapper.UserMapper;
import com.hagent.server.auth.model.entity.UserDO;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 用户上下文拦截器：登录状态下将当前用户写入 UserContext，请求结束后清理
 * 同时校验 @RequireAdmin 注解的管理员权限
 *
 * @author H
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserContextInterceptor implements HandlerInterceptor {

    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    /**
     * 请求前：若已登录，则按登录用户ID查询用户并填充 UserContext
     *
     * @param request  请求
     * @param response 响应
     * @param handler  处理器
     * @return true 放行
     * @author H
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // SSE 异步完成后的 ASYNC 再分发运行在无 Sa-Token 上下文的线程上，
        // 且主请求阶段已填充过 UserContext，这里直接放行避免 StpUtil.isLogin() 抛异常
        if (DispatcherType.ASYNC.equals(request.getDispatcherType())) {
            return true;
        }
        if (StpUtil.isLogin()) {
            Long userId = Long.valueOf(StpUtil.getLoginId().toString());
            UserDO user = userMapper.selectById(userId);
            if (user != null) {
                UserContext.set(userId, user.getUsername(), user.getRole());
            }
        }
        // 校验 @RequireAdmin 注解
        if (requiresAdmin(handler) && !RoleConstants.isAdmin(UserContext.getRole())) {
            log.warn("越权访问被拦截: uri={}, userId={}, role={}", request.getServletPath(), UserContext.getUserId(), UserContext.getRole());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(Map.of("code", 403, "msg", "无权限访问，需要管理员权限")));
            return false;
        }
        return true;
    }

    /**
     * 判断处理器是否需要管理员权限
     */
    private boolean requiresAdmin(Object handler) {
        if (handler instanceof HandlerMethod handlerMethod) {
            return handlerMethod.hasMethodAnnotation(RequireAdmin.class)
                    || handlerMethod.getBeanType().isAnnotationPresent(RequireAdmin.class);
        }
        return false;
    }

    /**
     * 请求完成后清理用户上下文，防止 ThreadLocal 内存泄漏
     *
     * @param request  请求
     * @param response 响应
     * @param handler  处理器
     * @param ex       异常
     * @author H
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.remove();
    }
}