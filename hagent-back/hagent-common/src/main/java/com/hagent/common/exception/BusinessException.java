package com.hagent.common.exception;

import lombok.Getter;

/**
 * 业务异常类
 *
 * @author H
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 错误码 */
    private final Integer code;

    /**
     * 构造业务异常
     *
     * @param message 错误信息
     */
    public BusinessException(String message) {
        super(message);
        this.code = 500;
    }

    /**
     * 构造业务异常
     *
     * @param code    错误码
     * @param message 错误信息
     */
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
