package com.hagent.server.auth.service;

import com.hagent.server.auth.model.dto.LoginRequest;
import com.hagent.server.auth.model.vo.CurrentUserVO;
import com.hagent.server.auth.model.vo.LoginVO;

/**
 * 认证服务接口
 *
 * @author H
 */
public interface AuthService {

    /**
     * 登录鉴权：校验用户名密码，成功后签发 Sa-Token
     *
     * @param request 登录请求参数（用户名、密码）
     * @return 登录结果（含 Token、用户名、角色）
     * @author H
     */
    LoginVO login(LoginRequest request);

    /**
     * 退出登录：注销当前用户会话
     *
     * @author H
     */
    void logout();

    /**
     * 获取当前登录用户信息
     *
     * @return 当前登录用户信息（不含密码）
     * @author H
     */
    CurrentUserVO getCurrentUser();
}
