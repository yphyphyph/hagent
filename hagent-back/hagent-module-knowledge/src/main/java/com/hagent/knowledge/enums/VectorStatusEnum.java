package com.hagent.knowledge.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 向量处理状态枚举
 * 1=待处理，2=处理中，3=已完成，4=失败
 *
 * @author H
 */
@Getter
@AllArgsConstructor
public enum VectorStatusEnum {

    PENDING(1, "待处理"),
    PROCESSING(2, "处理中"),
    COMPLETED(3, "已完成"),
    FAILED(4, "失败");

    private final Integer code;
    private final String desc;

    /**
     * 根据code值获取枚举
     *
     * @param code 状态码
     * @return 匹配的枚举，或默认PENDING
     */
    public static VectorStatusEnum getByCode(Integer code) {
        if (code == null) {
            return PENDING;
        }
        for (VectorStatusEnum e : values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        return PENDING;
    }
}
