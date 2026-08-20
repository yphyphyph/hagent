package com.hagent.server.auth;

import lombok.Data;

/**
 * 当前登录用户信息返回 VO（不含密码）
 *
 * @author H
 */
@Data
public class CurrentUserVO {

    /** 用户ID */
    private Long id;

    /** 用户名（登录账号） */
    private String username;

    /** 昵称 */
    private String nickname;

    /** 头像URL */
    private String avatar;

    /** 角色 */
    private String role;
}