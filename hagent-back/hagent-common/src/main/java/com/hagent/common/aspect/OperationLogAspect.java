package com.hagent.common.aspect;

import com.hagent.common.annotation.OperationLog;
import com.hagent.common.context.UserContext;
import com.hagent.common.entity.SystemLog;
import com.hagent.common.service.SystemLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 操作日志切面
 * <p>
 * 拦截 {@link OperationLog} 注解方法，记录请求信息、用户信息、耗时、参数与结果到 system_log 表。
 * 日志入库使用 REQUIRES_NEW 新事务，入库异常仅 warn 不影响主业务。
 *
 * @author H
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperationLogAspect {

    private final SystemLogService systemLogService;
    private final ObjectMapper objectMapper;

    /** 请求参数截断长度 2KB */
    private static final int PARAMS_MAX_LEN = 2048;
    /** 响应结果截断长度 8KB */
    private static final int RESULT_MAX_LEN = 8192;
    /** 错误信息截断长度 500 */
    private static final int ERROR_MSG_MAX_LEN = 500;

    /**
     * 环绕通知：记录操作日志
     *
     * @param joinPoint   切点
     * @param operationLog 注解信息
     * @return 目标方法返回值
     * @throws Throwable 目标方法异常原样抛出
     */
    @Around("@annotation(operationLog)")
    public Object around(ProceedingJoinPoint joinPoint, OperationLog operationLog) throws Throwable {
        long startTime = System.currentTimeMillis();
        SystemLog systemLog = new SystemLog();

        // 预填充注解与请求上下文信息
        fillRequestInfo(systemLog, joinPoint, operationLog);

        Object result = null;
        boolean success = true;
        String errorMsg = null;

        try {
            result = joinPoint.proceed();

            // 成功分支：按需记录响应结果
            if (operationLog.saveResult()) {
                String resultJson = serializeResult(result);
                systemLog.setResponseResult(truncate(resultJson, RESULT_MAX_LEN));
            }

            systemLog.setSuccess(1);
            return result;
        } catch (Throwable ex) {
            success = false;
            errorMsg = ex.getMessage();
            if (errorMsg != null && errorMsg.length() > ERROR_MSG_MAX_LEN) {
                errorMsg = errorMsg.substring(0, ERROR_MSG_MAX_LEN);
            }
            systemLog.setSuccess(0);
            systemLog.setErrorMessage(errorMsg);
            throw ex;
        } finally {
            // 耗时
            long cost = System.currentTimeMillis() - startTime;
            systemLog.setCostTimeMs((int) cost);

            // 若异常分支未设 success（正常返回已设），兜底
            if (systemLog.getSuccess() == null) {
                systemLog.setSuccess(success ? 1 : 0);
                if (!success) {
                    systemLog.setErrorMessage(errorMsg);
                }
            }

            // 异步入库不影响主事务：通过 REQUIRES_NEW 的 Service 方法，异常仅 warn
            try {
                systemLogService.saveLog(systemLog);
            } catch (Exception e) {
                log.warn("操作日志入库失败: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 填充请求与用户信息到日志实体
     */
    private void fillRequestInfo(SystemLog systemLog, ProceedingJoinPoint joinPoint, OperationLog operationLog) {
        // 注解信息
        systemLog.setModule(operationLog.module());
        systemLog.setOperationType(operationLog.operationType());
        systemLog.setActionDesc(operationLog.description());

        // 方法签名：类名#方法名
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String methodName = signature.getDeclaringType().getSimpleName() + "#" + signature.getName();
        systemLog.setMethod(methodName);

        // traceId：优先 MDC，否则 UUID
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = MDC.get("trace_id");
        }
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        systemLog.setTraceId(traceId);

        // 用户信息：UserContext 优先，缺失则反射取 UserContextHolder（避免对 hagent-ai 的编译期依赖）
        Long userId = UserContext.getUserId();
        String username = UserContext.getUsername();
        if (userId == null) {
            Long holderUserId = resolveUserIdFromHolder();
            if (holderUserId != null) {
                userId = holderUserId;
            }
        }
        // holder 无 username 方法，若 UserContext username 为空则保持空
        systemLog.setUserId(userId);
        systemLog.setUsername(username);

        // HTTP 请求信息
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            systemLog.setRequestMethod(request.getMethod());
            systemLog.setRequestUrl(request.getRequestURI());
            systemLog.setIp(getClientIp(request));
            String ua = request.getHeader("User-Agent");
            if (ua != null && ua.length() > 512) {
                ua = ua.substring(0, 512);
            }
            systemLog.setUserAgent(ua);
        } else {
            systemLog.setRequestMethod(null);
            systemLog.setRequestUrl(null);
            systemLog.setIp(null);
            systemLog.setUserAgent(null);
        }

        // 请求参数
        if (operationLog.saveParams()) {
            try {
                String paramsJson = serializeArgs(joinPoint.getArgs());
                systemLog.setRequestParams(truncate(paramsJson, PARAMS_MAX_LEN));
            } catch (Exception e) {
                log.warn("序列化请求参数失败: {}", e.getMessage());
                systemLog.setRequestParams("[]");
            }
        } else {
            systemLog.setRequestParams(null);
        }
    }

    /**
     * 反射获取 UserContextHolder.getUserId()，避免 hagent-common 对 hagent-ai 的硬依赖
     */
    private Long resolveUserIdFromHolder() {
        try {
            Class<?> holderClass = Class.forName("com.hagent.ai.core.UserContextHolder");
            java.lang.reflect.Method method = holderClass.getMethod("getUserId");
            Object val = method.invoke(null);
            if (val instanceof Long) {
                return (Long) val;
            }
            if (val instanceof Number) {
                return ((Number) val).longValue();
            }
        } catch (ClassNotFoundException e) {
            // holder 不存在于 classpath（如纯 common 单测），忽略
            log.debug("UserContextHolder 不存在，跳过回退");
        } catch (Exception e) {
            log.debug("从 UserContextHolder 获取 userId 失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 获取客户端真实 IP
     * 优先级：X-Forwarded-For 首段 > X-Real-IP > remoteAddr
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    /**
     * 序列化方法参数为 JSON，过滤敏感/过大类型
     */
    private String serializeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        List<Object> filtered = new ArrayList<>();
        for (Object arg : args) {
            if (arg == null) {
                filtered.add(null);
                continue;
            }
            if (arg instanceof MultipartFile
                    || arg instanceof HttpServletRequest
                    || arg instanceof HttpServletResponse
                    || arg instanceof java.io.InputStream
                    || arg instanceof java.io.OutputStream) {
                // 过滤大对象/非序列化类型，用类名占位
                filtered.add("[Filtered:" + arg.getClass().getSimpleName() + "]");
            } else {
                filtered.add(arg);
            }
        }
        try {
            return objectMapper.writeValueAsString(filtered);
        } catch (Exception e) {
            log.warn("请求参数 JSON 序列化失败: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 序列化响应结果为 JSON
     */
    private String serializeResult(Object result) {
        if (result == null) {
            return null;
        }
        // 对常见流/请求响应对象不做序列化
        if (result instanceof HttpServletRequest
                || result instanceof HttpServletResponse
                || result instanceof java.io.InputStream
                || result instanceof java.io.OutputStream
                || result instanceof MultipartFile) {
            return "[Filtered:" + result.getClass().getSimpleName() + "]";
        }
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("响应结果 JSON 序列化失败: {}", e.getMessage());
            return String.valueOf(result);
        }
    }

    /**
     * 截断字符串到指定长度
     */
    private String truncate(String str, int maxLen) {
        if (str == null) {
            return null;
        }
        if (str.length() <= maxLen) {
            return str;
        }
        return str.substring(0, maxLen);
    }
}
