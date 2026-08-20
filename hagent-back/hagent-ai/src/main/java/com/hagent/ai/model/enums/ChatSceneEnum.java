package com.hagent.ai.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 聊天场景枚举
 *
 * @author H
 */
@Getter
@AllArgsConstructor
public enum ChatSceneEnum {

    /** 通用聊天 */
    GENERAL(1, "通用"),

    /** 订单相关 */
    ORDER(2, "订单"),

    /** 知识库相关 */
    KNOWLEDGE(3, "知识库");

    /** 状态码 */
    private final Integer code;

    /** 描述 */
    private final String desc;

    public static ChatSceneEnum fromCode(Integer code) {
        for (ChatSceneEnum scene : values()) {
            if (scene.getCode().equals(code)) {
                return scene;
            }
        }
        return null;
    }
}
