package com.hagent.ai.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 会话查询请求DTO
 */
@Data
@Schema(description = "会话查询请求")
public class ConversationQueryRequest {

    /** 页码 */
    @Schema(description = "页码", defaultValue = "1")
    @Min(value = 1, message = "页码不能小于1")
    private Integer pageNum = 1;

    /** 每页大小 */
    @Schema(description = "每页大小", defaultValue = "20")
    @Min(value = 1, message = "每页条数不能小于1")
    @Max(value = 100, message = "每页条数不能超过100")
    private Integer pageSize = 20;

    /** 按状态筛选 */
    @Schema(description = "按状态筛选")
    private Integer status;

    /** 按场景筛选 */
    @Schema(description = "按聊天场景筛选")
    private Integer scene;

    /** 搜索关键词 */
    @Schema(description = "标题搜索关键词")
    private String keyword;
}
