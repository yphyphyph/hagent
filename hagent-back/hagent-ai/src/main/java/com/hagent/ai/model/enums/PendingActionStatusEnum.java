package com.hagent.ai.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 待确认操作状态枚举
 *
 * @author H
 */
@Getter
@AllArgsConstructor
public enum PendingActionStatusEnum {

    /** 待确认 */
    PENDING(1, "待确认"),

    /** 已确认 */
    CONFIRMED(2, "已确认"),

    /** 已取消 */
    CANCELLED(3, "已取消"),

    /** 执行中 */
    EXECUTING(4, "执行中"),

    /** 已执行 */
    EXECUTED(5, "已执行");

    /** 状态码 */
    private final Integer code;

    /** 描述 */
    private final String desc;

    public static PendingActionStatusEnum fromCode(Integer code) {
        for (PendingActionStatusEnum status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}
