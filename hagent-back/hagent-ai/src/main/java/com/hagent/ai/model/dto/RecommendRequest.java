package com.hagent.ai.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 追问推荐请求 DTO
 * 仅承载提示词，不涉及会话持久化
 *
 * @author H
 */
@Data
@Schema(description = "追问推荐请求")
public class RecommendRequest {

    /**
     * 推荐提示词（由前端基于回答前200字与命中标题拼接）
     */
    @NotBlank(message = "提示词不能为空")
    @Size(max = 2000, message = "提示词长度不能超过2000个字符")
    @Schema(description = "推荐提示词")
    private String prompt;
}
