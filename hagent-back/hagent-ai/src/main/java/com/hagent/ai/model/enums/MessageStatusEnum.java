package com.hagent.ai.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 消息状态枚举
 *
 * @author H
 */
@Getter
@AllArgsConstructor
public enum MessageStatusEnum {

    /** 待处理 */
    PENDING(1, "待处理"),

    /** 流式传输中 */
    STREAMING(2, "流式传输中"),

    /** 已完成 */
    COMPLETED(3, "已完成"),

    /** 失败 */
    FAILED(4, "失败");

    /** 状态码 */
    private final Integer code;

    /** 描述 */
    private final String desc;

    public static MessageStatusEnum fromCode(Integer code) {
        for (MessageStatusEnum status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}
