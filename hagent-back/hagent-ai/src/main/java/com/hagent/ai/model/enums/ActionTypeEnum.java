package com.hagent.ai.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 操作类型枚举
 *
 * @author H
 */
@Getter
@AllArgsConstructor
public enum ActionTypeEnum {

    /** 博客操作 */
    BLOG(1, "博客"),

    /** 订单操作 */
    ORDER(2, "订单"),

    /** 知识库操作 */
    KNOWLEDGE(3, "知识库");

    /** 状态码 */
    private final Integer code;

    /** 描述 */
    private final String desc;

    public static ActionTypeEnum fromCode(Integer code) {
        for (ActionTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
