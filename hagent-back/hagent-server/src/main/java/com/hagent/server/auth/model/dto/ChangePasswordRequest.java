package com.hagent.server.auth.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码请求 DTO（个人中心 - 修改密码表单）
 *
 * @author H
 */
@Data
public class ChangePasswordRequest {

    /** 旧密码 */
    @NotBlank(message = "旧密码不能为空")
    private String oldPassword;

    /** 新密码（至少 6 位，与前端表单提示一致） */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 64, message = "新密码长度须为 6~64 位")
    private String newPassword;
}
