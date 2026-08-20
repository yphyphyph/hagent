package com.hagent.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 管理员权限注解
 * <p>
 * 标注在 Controller 类或方法上，要求当前用户角色为管理员。
 * 由 {@code JwtAuthInterceptor} 结合路径规则统一校验；此注解用于对非 /admin 路径的接口做显式限定。
 *
 * @author H
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAdmin {

    /** 无权限时的提示消息 */
    String message() default "无权限访问，需要管理员权限";
}
