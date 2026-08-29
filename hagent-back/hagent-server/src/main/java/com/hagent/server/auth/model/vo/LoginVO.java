package com.hagent.server.auth.model.vo;

import lombok.Data;

/**
 * 登录成功返回 VO
 *
 * @author H
 */
@Data
public class LoginVO {

    /** 登录凭证 Token */
    private String token;

    /** 用户名 */
    private String username;

    /** 角色 */
    private String role;
}