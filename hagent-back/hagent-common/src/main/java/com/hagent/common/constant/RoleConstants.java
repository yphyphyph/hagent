package com.hagent.common.constant;

/**
 * 角色常量
 * <p>
 * 数据库 user.role 为 INT 类型（1=管理员 2=普通用户），经 MyBatis 映射为字符串后为 "1"/"2"；
 * 历史/迁移数据中亦可能存在 "ADMIN"/"USER" 文本值。此处统一提供判定方法，兼容两种形式。
 *
 * @author H
 */
public final class RoleConstants {

    private RoleConstants() {
    }

    /** 管理员角色（数值形式） */
    public static final String ADMIN = "1";

    /** 普通用户角色（数值形式） */
    public static final String USER = "2";

    /** 管理员角色（文本形式，兼容历史数据） */
    public static final String ADMIN_TEXT = "ADMIN";

    /**
     * 判断给定角色是否为管理员，兼容 "1" 与 "ADMIN"（大小写不敏感）
     *
     * @param role 角色值
     * @return 是管理员返回 true
     */
    public static boolean isAdmin(String role) {
        if (role == null) {
            return false;
        }
        return ADMIN.equals(role) || ADMIN_TEXT.equalsIgnoreCase(role.trim());
    }
}
