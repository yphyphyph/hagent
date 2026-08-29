package com.hagent.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志注解
 * <p>
 * 标注在方法上，由 {@code OperationLogAspect} 切面拦截并记录操作日志到 system_log 表。
 * 切面异常不影响主业务事务。
 *
 * @author H
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperationLog {

    /**
     * 模块/业务类型
     */
    String module() default "";

    /**
     * 操作类型，可选值：CREATE / UPDATE / DELETE / QUERY / LOGIN / EXPORT / OTHER
     */
    String operationType() default "OTHER";

    /**
     * 操作描述
     */
    String description() default "";

    /**
     * 是否保存请求参数
     */
    boolean saveParams() default true;

    /**
     * 是否保存响应结果
     */
    boolean saveResult() default false;
}
