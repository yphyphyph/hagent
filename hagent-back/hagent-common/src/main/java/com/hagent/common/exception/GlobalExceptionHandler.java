package com.hagent.common.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.hagent.common.Result;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * 统一处理业务异常、系统异常、参数校验异常，禁止Controller层try-catch吞异常
 *
 * @author H
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常
     * 业务异常返回友好提示，不打印error日志
     *
     * @param e 业务异常
     * @return Result
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.error(e.getMessage());
    }

    /**
     * 处理系统异常
     * 系统异常打印error堆栈，返回系统繁忙提示
     *
     * @param e 系统异常
     * @return Result
     */
    @ExceptionHandler(SystemException.class)
    public Result<Void> handleSystemException(SystemException e) {
        log.error("系统异常: {}", e.getMessage(), e);
        return Result.error(500, "系统繁忙，请稍后重试");
    }

    /**
     * 处理参数校验异常（@Valid + @RequestBody）
     *
     * @param e 方法参数校验异常
     * @return Result
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return Result.error(400, message);
    }

    /**
     * 处理 Sa-Token 未登录异常
     * 返回 401，前端据此跳转登录
     *
     * @param e 未登录异常
     * @return Result
     */
    @ExceptionHandler(NotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleNotLoginException(NotLoginException e) {
        return Result.error(401, "未登录或会话已过期");
    }

    /**
     * 处理 Sa-Token 无角色/无权限异常
     * 返回 403
     *
     * @param e 无权限异常
     * @return Result
     */
    @ExceptionHandler({NotRoleException.class, NotPermissionException.class})
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleNotPermissionException(RuntimeException e) {
        return Result.error(403, "无权限访问");
    }

    /**
     * 处理约束违反异常（@Validated）
     *
     * @param e 约束违反异常
     * @return Result
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleConstraintViolationException(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining(", "));
        log.warn("参数校验异常: {}", message);
        return Result.error(message);
    }

    /**
     * 兜底处理未知异常
     * 打印error堆栈，返回系统繁忙提示，避免暴露内部细节
     *
     * @param e 未知异常
     * @return Result
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("未知异常: {}", e.getMessage(), e);
        return Result.error(500, "系统繁忙，请稍后重试");
    }
}
