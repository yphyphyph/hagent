package com.hagent.ai.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 消息角色枚举
 *
 * @author H
 */
@Getter
@AllArgsConstructor
public enum MessageRoleEnum {

    /** 用户消息 */
    USER(1, "user"),

    /** 助手消息 */
    ASSISTANT(2, "assistant"),

    /** 系统消息 */
    SYSTEM(3, "system"),

    /** 工具消息 */
    TOOL(4, "tool");

    /** 状态码 */
    private final Integer code;

    /** 描述 */
    private final String desc;

    public static MessageRoleEnum fromCode(Integer code) {
        for (MessageRoleEnum role : values()) {
            if (role.getCode().equals(code)) {
                return role;
            }
        }
        return null;
    }
}
