package com.hagent.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解
 * 基于 IP + 接口路径的滑动窗口限流，防止暴力破解与滥用
 * 默认：60 秒内最多 10 次请求
 *
 * @author H
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 时间窗口大小（秒），默认 60 秒 */
    int window() default 60;

    /** 时间窗口内最大请求数，默认 10 次 */
    int limit() default 10;

    /** 限流提示消息 */
    String message() default "请求过于频繁，请稍后再试";
}
