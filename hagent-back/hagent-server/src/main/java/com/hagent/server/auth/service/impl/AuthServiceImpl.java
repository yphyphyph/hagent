package com.hagent.server.auth.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hagent.common.exception.BusinessException;
import com.hagent.server.auth.mapper.UserMapper;
import com.hagent.server.auth.model.dto.ChangePasswordRequest;
import com.hagent.server.auth.model.dto.LoginRequest;
import com.hagent.server.auth.model.dto.ProfileUpdateRequest;
import com.hagent.server.auth.model.entity.UserDO;
import com.hagent.server.auth.model.vo.CurrentUserVO;
import com.hagent.server.auth.model.vo.LoginVO;
import com.hagent.server.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

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
            // 失败日志只记用户名与 IP，不记密码明文
            log.warn("登录失败，用户名: {}，IP: {}", request.getUsername(), getClientIp());
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
     * 获取客户端 IP，用于登录失败日志定位爆破来源
     *
     * @return 客户端 IP（取 X-Forwarded-For 首段，缺失时取远端地址）
     * @author H
     */
    private String getClientIp() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }
        HttpServletRequest request = attributes.getRequest();
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
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

    /**
     * 修改当前登录用户密码：BCrypt 校验旧密码，通过后以 BCrypt 加密新密码落库
     *
     * @param request 修改密码请求（旧密码、新密码）
     * @author H
     */
    @Override
    public void changePassword(ChangePasswordRequest request) {
        // 当前登录用户ID（未登录时 Sa-Token 抛 NotLoginException，由全局处理器转 401）
        String loginId = StpUtil.getLoginIdAsString();
        UserDO user = userMapper.selectById(loginId);
        if (user == null) {
            log.warn("修改密码失败，用户不存在，loginId: {}", loginId);
            throw new BusinessException("用户不存在");
        }
        // 旧密码 BCrypt 比对，失败不泄露具体校验细节
        if (!BCrypt.checkpw(request.getOldPassword(), user.getPassword())) {
            log.warn("修改密码失败，旧密码校验未通过，用户: {}", user.getUsername());
            throw new BusinessException("旧密码不正确");
        }
        // 新密码不得与旧密码相同（BCrypt 每次加盐结果不同，用 checkpw 语义比对）
        if (BCrypt.checkpw(request.getNewPassword(), user.getPassword())) {
            throw new BusinessException("新密码不能与旧密码相同");
        }
        // 仅更新密码与更新时间（MyBatis-Plus updateById 只落非空字段，其余列不动）
        UserDO update = new UserDO();
        update.setId(user.getId());
        update.setPassword(BCrypt.hashpw(request.getNewPassword(), BCrypt.gensalt()));
        update.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(update);
        log.info("用户修改密码成功，用户: {}", user.getUsername());
    }

    /**
     * 更新当前登录用户个人资料（昵称/头像，字段可选，至少一项生效）
     *
     * @param request 个人资料更新请求
     * @return 更新后的当前用户信息（不含密码）
     * @author H
     */
    @Override
    public CurrentUserVO updateProfile(ProfileUpdateRequest request) {
        // 当前登录用户ID（未登录时抛 NotLoginException，由全局处理器转 401）
        String loginId = StpUtil.getLoginIdAsString();
        UserDO user = userMapper.selectById(loginId);
        if (user == null) {
            log.warn("更新个人资料失败，用户不存在，loginId: {}", loginId);
            throw new BusinessException("用户不存在");
        }
        // trim 后判空：纯空白串视为未填写，避免覆盖为空白
        String nickname = request.getNickname() == null ? null : request.getNickname().trim();
        String avatar = request.getAvatar() == null ? null : request.getAvatar().trim();
        if ((nickname == null || nickname.isEmpty()) && (avatar == null || avatar.isEmpty())) {
            throw new BusinessException("请至少填写一项要修改的内容");
        }
        // 仅更新非空字段与更新时间
        UserDO update = new UserDO();
        update.setId(user.getId());
        if (nickname != null && !nickname.isEmpty()) {
            update.setNickname(nickname);
        }
        if (avatar != null && !avatar.isEmpty()) {
            update.setAvatar(avatar);
        }
        update.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(update);
        log.info("用户更新个人资料成功，用户: {}", user.getUsername());
        // 返回更新后的完整用户信息（复用 getCurrentUser，密码不出库）
        return getCurrentUser();
    }
}
