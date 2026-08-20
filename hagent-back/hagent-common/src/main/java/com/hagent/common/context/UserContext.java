package com.hagent.common.context;

/**
 * 用户上下文，使用ThreadLocal存储当前用户信息
 *
 * @author H
 */
public final class UserContext {

    private UserContext() {
    }

    /** 用户ID */
    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    /** 用户名 */
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();

    /** 角色 */
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

    /**
     * 设置用户上下文
     *
     * @param userId   用户ID
     * @param username 用户名
     * @param role     角色
     */
    public static void set(Long userId, String username, String role) {
        USER_ID.set(userId);
        USERNAME.set(username);
        ROLE.set(role);
    }

    /**
     * 获取当前用户ID
     *
     * @return 用户ID
     */
    public static Long getUserId() {
        return USER_ID.get();
    }

    /**
     * 获取当前用户名
     *
     * @return 用户名
     */
    public static String getUsername() {
        return USERNAME.get();
    }

    /**
     * 获取当前用户角色
     *
     * @return 角色
     */
    public static String getRole() {
        return ROLE.get();
    }

    /**
     * 清除用户上下文
     */
    public static void remove() {
        USER_ID.remove();
        USERNAME.remove();
        ROLE.remove();
    }
}
