package com.hagent.server.auth.controller;

import com.hagent.common.Result;
import com.hagent.common.annotation.OperationLog;
import com.hagent.common.annotation.RateLimit;
import com.hagent.server.auth.model.dto.LoginRequest;
import com.hagent.server.auth.model.vo.CurrentUserVO;
import com.hagent.server.auth.model.vo.LoginVO;
import com.hagent.server.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器 - 登录与退出
 *
 * @author H
 */
@Slf4j
@Tag(name = "认证", description = "登录、退出等认证接口")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 用户登录
     * 阈值依据：单 admin 账号、无注册口，正常人输错重试远低于 10 次/分钟；
     * 取宽松值 60 秒 10 次/IP 防暴力破解，不锁正常用户（单机 RateLimit 实现，够用）。
     * 操作日志关闭参数入库，避免 LoginRequest.password 明文落库 system_log。
     *
     * @param request 登录参数（用户名、密码）
     * @return 登录结果（含 Token、用户名、角色）
     * @author H
     */
    @PostMapping("/login")
    @Operation(summary = "登录", description = "使用用户名密码登录，成功返回 Sa-Token")
    @RateLimit(window = 60, limit = 10, message = "登录尝试过于频繁，请稍后再试")
    @OperationLog(module = "auth", operationType = "LOGIN", description = "用户登录", saveParams = false, saveResult = false)
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(authService.login(request));
    }

    /**
     * 获取当前登录用户信息
     *
     * @return 当前登录用户信息（含用户名、角色、头像、昵称等，不返回密码）
     * @author H
     */
    @GetMapping("/me")
    @Operation(summary = "获取当前登录用户信息", description = "从 Sa-Token 会话中获取当前登录用户并返回其信息")
    public Result<CurrentUserVO> me() {
        return Result.success(authService.getCurrentUser());
    }

    /**
     * 用户退出登录
     *
     * @return 退出结果
     * @author H
     */
    @PostMapping("/logout")
    @Operation(summary = "退出登录", description = "注销当前用户登录会话")
    public Result<Void> logout() {
        authService.logout();
        return Result.success();
    }
}