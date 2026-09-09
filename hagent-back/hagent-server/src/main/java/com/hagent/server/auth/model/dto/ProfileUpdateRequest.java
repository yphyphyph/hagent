package com.hagent.server.auth.model.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新个人资料请求 DTO（个人中心 - 昵称/头像，字段均可选，至少一项生效）
 *
 * @author H
 */
@Data
public class ProfileUpdateRequest {

    /** 昵称（可选，为空或纯空白则不更新） */
    @Size(max = 32, message = "昵称长度不能超过 32 个字符")
    private String nickname;

    /** 头像 URL（可选，为空则不更新） */
    @Size(max = 512, message = "头像地址长度不能超过 512 个字符")
    private String avatar;
}
