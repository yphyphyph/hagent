package com.hagent.common.aspect;

import com.hagent.common.annotation.RateLimit;
import com.hagent.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 接口限流切面
 * 基于 IP + 方法签名的内存计数器实现滑动窗口限流
 * 单机版方案，集群部署需替换为 Redis + Lua 实现
 *
 * @author H
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    /** 限流计数器：key = IP + 方法签名，value = 时间窗口内的请求计数信息 */
    private final Map<String, RateWindow> rateLimitMap = new ConcurrentHashMap<>();

    /**
     * 环绕通知：在标注 @RateLimit 的方法执行前进行限流校验
     *
     * @param joinPoint 切入点
     * @return 目标方法返回值
     * @throws Throwable 目标方法抛出的异常
     */
    @Around("@annotation(com.hagent.common.annotation.RateLimit)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        RateLimit rateLimit = method.getAnnotation(RateLimit.class);

        String ip = getClientIp();
        String key = ip + ":" + method.getDeclaringClass().getSimpleName() + "." + method.getName();

        if (isRateLimited(key, rateLimit)) {
            log.warn("接口限流触发: ip={}, method={}, limit={}/{}s",
                    ip, method.getName(), rateLimit.limit(), rateLimit.window());
            throw new BusinessException(rateLimit.message());
        }

        return joinPoint.proceed();
    }

    /**
     * 判断是否超出限流阈值
     * 使用"固定窗口"算法：记录窗口起始时间与计数，过期则重置
     * 使用 computeIfAbsent 获取或创建 RateWindow，synchronized 锁定窗口对象本身保证线程安全
     * 避免使用 String.intern() 导致的元空间内存泄漏
     *
     * @param key        限流键（IP + 方法签名）
     * @param rateLimit  限流配置
     * @return 超出限流返回 true，允许通过返回 false
     */
    private boolean isRateLimited(String key, RateLimit rateLimit) {
        long now = System.currentTimeMillis();
        long windowStart = now - rateLimit.window() * 1000L;

        RateWindow rateWindow = rateLimitMap.computeIfAbsent(key, k -> new RateWindow(now, 0));
        synchronized (rateWindow) {
            if (rateWindow.getTimestamp() < windowStart) {
                // 窗口过期，重置计数
                rateWindow.reset(now, 1);
                return false;
            }
            // 窗口内计数 +1
            rateWindow.increment();
            return rateWindow.getCount() > rateLimit.limit();
        }
    }

    /**
     * 获取客户端真实 IP（穿透代理）
     *
     * @return 客户端 IP
     */
    private String getClientIp() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }
        HttpServletRequest request = attributes.getRequest();
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 多级代理时取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    /**
     * 限流窗口计数器
     */
    private static class RateWindow {
        private long timestamp;
        private int count;

        RateWindow(long timestamp, int count) {
            this.timestamp = timestamp;
            this.count = count;
        }

        long getTimestamp() {
            return timestamp;
        }

        int getCount() {
            return count;
        }

        void increment() {
            this.count++;
        }

        /**
         * 重置窗口时间戳与计数
         *
         * @param timestamp 新窗口起始时间
         * @param count     新计数
         */
        void reset(long timestamp, int count) {
            this.timestamp = timestamp;
            this.count = count;
        }
    }
}
