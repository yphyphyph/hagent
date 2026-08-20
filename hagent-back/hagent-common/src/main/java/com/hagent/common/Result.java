package com.hagent.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一API响应 - 封装code、msg、data、timestamp
 *
 * @param <T> 数据类型
 */
@Data
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 响应码 */
    private Integer code;

    /** 响应消息 */
    private String msg;

    /** 响应数据 */
    private T data;

    /** 时间戳 */
    private Long timestamp;

    /**
     * 成功（无数据）
     * @return Result
     */
    public static <T> Result<T> success() {
        return buildResult(200, "success", null);
    }

    /**
     * 成功（带数据）
     * @param data 响应数据
     * @return Result
     */
    public static <T> Result<T> success(T data) {
        return buildResult(200, "success", data);
    }

    /**
     * 成功（带数据和消息）
     * @param data 响应数据
     * @param msg 响应消息
     * @return Result
     */
    public static <T> Result<T> success(T data, String msg) {
        return buildResult(200, msg, data);
    }

    /**
     * 错误（带消息）
     * @param msg 错误消息
     * @return Result
     */
    public static <T> Result<T> error(String msg) {
        return buildResult(500, msg, null);
    }

    /**
     * 错误（带错误码和消息）
     * @param code 错误码
     * @param msg 错误消息
     * @return Result
     */
    public static <T> Result<T> error(Integer code, String msg) {
        return buildResult(code, msg, null);
    }

    /**
     * 构建Result对象
     * @param code 响应码
     * @param msg 响应消息
     * @param data 响应数据
     * @return Result
     */
    private static <T> Result<T> buildResult(Integer code, String msg, T data) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMsg(msg);
        result.setData(data);
        result.setTimestamp(System.currentTimeMillis());
        return result;
    }
}
