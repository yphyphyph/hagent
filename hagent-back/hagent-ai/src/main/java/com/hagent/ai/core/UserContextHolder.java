package com.hagent.ai.core;

/**
 * 用户上下文持有者
 * 使用ThreadLocal存储userId/conversationId，供@Tool访问
 *
 * @author H
 */
public final class UserContextHolder {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<Long> CONVERSATION_ID = new ThreadLocal<>();

    private UserContextHolder() {}

    /**
     * 设置当前线程的用户ID
     *
     * @param userId 用户ID
     */
    public static void setUserId(Long userId) { USER_ID.set(userId); }

    /**
     * 获取当前线程的用户ID
     *
     * @return 用户ID，未设置时返回null
     */
    public static Long getUserId() { return USER_ID.get(); }

    /**
     * 设置当前线程的会话ID
     *
     * @param conversationId 会话ID
     */
    public static void setConversationId(Long conversationId) { CONVERSATION_ID.set(conversationId); }

    /**
     * 获取当前线程的会话ID
     *
     * @return 会话ID，未设置时返回null
     */
    public static Long getConversationId() { return CONVERSATION_ID.get(); }

    /**
     * 清除当前线程的用户ID与会话ID，防止内存泄漏
     */
    public static void clear() {
        USER_ID.remove();
        CONVERSATION_ID.remove();
    }
}
