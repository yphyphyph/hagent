package com.hagent.server.auth.config;

import cn.dev33.satoken.stp.StpInterface;
import com.hagent.server.auth.mapper.UserMapper;
import com.hagent.server.auth.model.entity.UserDO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Sa-Token 权限数据源实现：从数据库加载用户角色与权限
 *
 * @author H
 */
@Component
@RequiredArgsConstructor
public class SaTokenStpInterfaceImpl implements StpInterface {

    private final UserMapper userMapper;

    /**
     * 获取用户角色列表，按登录用户ID从数据库查询
     *
     * @param loginId   登录用户ID
     * @param loginType 登录类型
     * @return 角色列表
     * @author H
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        UserDO user = userMapper.selectById(loginId.toString());
        if (user == null) {
            return new ArrayList<>();
        }
        return List.of(user.getRole());
    }

    /**
     * 获取用户权限列表（本次实现不细分权限，返回空列表）
     *
     * @param loginId   登录用户ID
     * @param loginType 登录类型
     * @return 权限列表
     * @author H
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return new ArrayList<>();
    }
}