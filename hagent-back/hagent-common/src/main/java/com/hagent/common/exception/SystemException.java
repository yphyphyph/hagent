package com.hagent.common.exception;

import lombok.Getter;

/**
 * 系统异常类
 *
 * @author H
 */
@Getter
public class SystemException extends RuntimeException {

    /** 错误码 */
    private final Integer code;

    /**
     * 构造系统异常
     *
     * @param message 错误信息
     */
    public SystemException(String message) {
        super(message);
        this.code = 500;
    }

    /**
     * 构造系统异常
     *
     * @param message 错误信息
     * @param cause   原因
     */
    public SystemException(String message, Throwable cause) {
        super(message, cause);
        this.code = 500;
    }
}
