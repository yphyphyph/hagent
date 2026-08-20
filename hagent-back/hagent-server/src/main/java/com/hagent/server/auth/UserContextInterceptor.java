package com.hagent.server.auth;

import cn.dev33.satoken.stp.StpUtil;
import com.hagent.common.context.UserContext;
import com.hagent.server.auth.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 用户上下文拦截器：登录状态下将当前用户写入 UserContext，请求结束后清理
 *
 * @author H
 */
@Component
@RequiredArgsConstructor
public class UserContextInterceptor implements HandlerInterceptor {

    private final UserMapper userMapper;

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
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (StpUtil.isLogin()) {
            Long userId = Long.valueOf(StpUtil.getLoginId().toString());
            UserDO user = userMapper.selectById(userId);
            if (user != null) {
                UserContext.set(userId, user.getUsername(), user.getRole());
            }
        }
        return true;
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