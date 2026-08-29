package com.hagent.server.auth.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hagent.common.exception.BusinessException;
import com.hagent.server.auth.mapper.UserMapper;
import com.hagent.server.auth.model.dto.LoginRequest;
import com.hagent.server.auth.model.entity.UserDO;
import com.hagent.server.auth.model.vo.CurrentUserVO;
import com.hagent.server.auth.model.vo.LoginVO;
import com.hagent.server.auth.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 认证服务实现
 *
 * @author H
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;

    /**
     * 登录鉴权：按用户名查询用户，使用 BCrypt 校验密码，成功后进行 Sa-Token 登录
     *
     * @param request 登录请求参数
     * @return 登录结果（含 Token、用户名、角色）
     * @author H
     */
    @Override
    public LoginVO login(LoginRequest request) {
        // 按用户名查询用户
        UserDO user = userMapper.selectOne(
                new LambdaQueryWrapper<UserDO>().eq(UserDO::getUsername, request.getUsername()));
        // 用户不存在或密码校验失败，统一返回友好提示，避免枚举出用户是否存在
        if (user == null || !BCrypt.checkpw(request.getPassword(), user.getPassword())) {
            log.warn("登录失败，用户名: {}", request.getUsername());
            throw new BusinessException("用户名或密码错误");
        }
        // Sa-Token 登录
        StpUtil.login(user.getId().toString());
        log.info("用户登录成功，用户名: {}", user.getUsername());
        // 组装登录结果
        LoginVO vo = new LoginVO();
        vo.setToken(StpUtil.getTokenValue());
        vo.setUsername(user.getUsername());
        vo.setRole(user.getRole());
        return vo;
    }

    /**
     * 退出登录：注销当前 Sa-Token 会话
     *
     * @author H
     */
    @Override
    public void logout() {
        StpUtil.logout();
    }

    /**
     * 获取当前登录用户信息
     *
     * @return 当前登录用户信息（不含密码）
     * @author H
     */
    @Override
    public CurrentUserVO getCurrentUser() {
        // 从 Sa-Token 会话中获取当前登录用户ID
        String loginId = StpUtil.getLoginIdAsString();
        // 按用户ID查询最新用户信息
        UserDO user = userMapper.selectById(loginId);
        if (user == null) {
            log.warn("当前登录用户不存在，loginId: {}", loginId);
            throw new BusinessException("用户不存在");
        }
        // 组装当前用户信息，不暴露密码
        CurrentUserVO vo = new CurrentUserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setRole(user.getRole());
        return vo;
    }
}
