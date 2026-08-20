package com.hagent.server.auth;

import com.hagent.common.Result;
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
     *
     * @param request 登录参数（用户名、密码）
     * @return 登录结果（含 Token、用户名、角色）
     * @author H
     */
    @PostMapping("/login")
    @Operation(summary = "登录", description = "使用用户名密码登录，成功返回 Sa-Token")
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