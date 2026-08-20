package com.hagent.common.interceptor;

import tools.jackson.databind.ObjectMapper;
import com.hagent.common.annotation.RequireAdmin;
import com.hagent.common.constant.RoleConstants;
import com.hagent.common.context.UserContext;
import com.hagent.common.service.TokenBlacklistService;
import com.hagent.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Date;
import java.util.Map;

/**
 * JWT 认证拦截器
 * 校验请求头中的 Authorization Token，有效则写入 UserContext
 * 同时校验 Token 黑名单（支持登出吊销）
 *
 * @author H
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final TokenBlacklistService tokenBlacklistService;
    private final ObjectMapper objectMapper;

    /**
     * 前置处理：校验 JWT Token 并设置 UserContext
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param handler 处理器
     * @return true 放行，false 拦截
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = resolveToken(request);
        if (!StringUtils.hasText(token)) {
            writeUnauthorized(response, "未登录或会话已过期");
            return false;
        }

        try {
            Claims claims = jwtUtil.parseToken(token);

            // 检查是否过期
            if (claims.getExpiration().before(new Date())) {
                writeUnauthorized(response, "会话已过期，请重新登录");
                return false;
            }

            // 检查 Token 是否已被吊销（登出、改密后加入黑名单）
            String jti = claims.getId();
            if (tokenBlacklistService.isBlacklisted(jti)) {
                writeUnauthorized(response, "登录已失效，请重新登录");
                return false;
            }

            // jjwt 0.12.x 解析数字 claims 时，小整数会被解析为 Integer，直接用 Long.class 会抛类型转换异常
            Object userIdObj = claims.get("userId");
            Long userId = userIdObj instanceof Number ? ((Number) userIdObj).longValue() : null;
            String username = claims.getSubject();
            String role = claims.get("role", String.class);
            UserContext.set(userId, username, role);

            // RBAC：/admin 路径或 @RequireAdmin 标注的接口，要求管理员角色
            if (requiresAdmin(request, handler) && !RoleConstants.isAdmin(role)) {
                log.warn("越权访问被拦截: uri={}, userId={}, role={}", request.getServletPath(), userId, role);
                writeForbidden(response, "无权限访问，需要管理员权限");
                return false;
            }
            return true;
        } catch (ExpiredJwtException e) {
            writeUnauthorized(response, "会话已过期，请重新登录");
            return false;
        } catch (Exception e) {
            log.warn("Token 解析失败: {}", e.getMessage());
            writeUnauthorized(response, "登录凭证无效");
            return false;
        }
    }

    /**
     * 完成后清理 UserContext，防止内存泄漏
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.remove();
    }

    /**
     * 判断当前请求是否需要管理员权限
     * 规则：请求路径以 /admin/ 开头，或处理器方法/类标注了 @RequireAdmin
     *
     * @param request HTTP 请求
     * @param handler 处理器
     * @return 需要管理员权限返回 true
     */
    private boolean requiresAdmin(HttpServletRequest request, Object handler) {
        String path = request.getServletPath();
        if (path != null && path.startsWith("/admin/")) {
            return true;
        }
        if (handler instanceof HandlerMethod handlerMethod) {
            return handlerMethod.hasMethodAnnotation(RequireAdmin.class)
                    || handlerMethod.getBeanType().isAnnotationPresent(RequireAdmin.class);
        }
        return false;
    }

    /**
     * 从请求中提取 Token
     * 优先：Authorization 头，备选：查询参数（SSE/EventSource）
     * @param request HTTP 请求
     * @return Token 字符串，未找到返回 null
     */
    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        // SSE/EventSource 备选：查询参数（不推荐，存在日志泄露风险）
        String queryToken = request.getParameter("token");
        if (StringUtils.hasText(queryToken)) {
            return queryToken;
        }
        return null;
    }

    /**
     * 写入 401 响应（使用 ObjectMapper 序列化，防止 JSON 注入）
     * @param response HTTP 响应
     * @param msg 错误消息
     */
    private void writeUnauthorized(HttpServletResponse response, String msg) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Map.of("code", 401, "msg", msg)));
    }

    /**
     * 写入 403 响应（权限不足）
     * @param response HTTP 响应
     * @param msg 错误消息
     */
    private void writeForbidden(HttpServletResponse response, String msg) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Map.of("code", 403, "msg", msg)));
    }
}
