package com.hagent.ai.controller;

import com.hagent.ai.model.vo.PendingActionVO;
import com.hagent.ai.service.PendingActionService;
import com.hagent.common.PageResult;
import com.hagent.common.Result;
import com.hagent.common.context.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * AI待确认操作控制器
 * 提供待确认操作的查询、确认、取消接口
 *
 * @author H
 */
@Slf4j
@Tag(name = "AI待确认操作", description = "AI待确认操作的查询、确认执行与取消等接口")
@RestController
@RequestMapping("/admin/ai/pending-actions")
@RequiredArgsConstructor
public class PendingActionController {

    private final PendingActionService pendingActionService;

    /**
     * 分页查询当前用户待确认操作列表
     *
     * @param status   状态筛选（为空时不过滤）
     * @param pageNum  当前页码，默认1
     * @param pageSize 每页大小，默认20
     * @return 分页结果
     */
    @GetMapping
    @Operation(summary = "分页查询待确认操作列表", description = "分页查询当前用户的待确认操作列表，支持按状态筛选")
    @ApiResponse(responseCode = "200", description = "查询成功")
    public Result<PageResult<PendingActionVO>> list(
            @Parameter(description = "状态筛选：1-待确认，2-已确认，3-已取消，4-执行中，5-已执行")
            @RequestParam(required = false) Integer status,
            @Parameter(description = "当前页码，默认1")
            @RequestParam(defaultValue = "1") int pageNum,
            @Parameter(description = "每页大小，默认20")
            @RequestParam(defaultValue = "20") int pageSize) {
        Long userId = UserContext.getUserId();
        return Result.success(pendingActionService.listPendingActions(userId, status, pageNum, pageSize));
    }

    /**
     * 根据ID查询单个待确认操作详情（校验归属权）
     *
     * @param id 待确认操作ID
     * @return 待确认操作VO
     */
    @GetMapping("/{id}")
    @Operation(summary = "根据ID查询待确认操作", description = "根据ID查询单个待确认操作详情，并校验归属权")
    @ApiResponse(responseCode = "200", description = "查询成功")
    public Result<PendingActionVO> getById(
            @Parameter(description = "待确认操作ID", required = true) @PathVariable Long id) {
        Long userId = UserContext.getUserId();
        return Result.success(pendingActionService.getById(id, userId));
    }

    /**
     * 确认并执行待确认操作
     *
     * @param id 待确认操作ID
     * @return 执行后的待确认操作VO
     */
    @PostMapping("/{id}/confirm")
    @Operation(summary = "确认并执行待确认操作", description = "确认并执行指定待确认操作，并校验归属权")
    @ApiResponse(responseCode = "200", description = "执行成功")
    public Result<PendingActionVO> confirm(
            @Parameter(description = "待确认操作ID", required = true) @PathVariable Long id) {
        Long userId = UserContext.getUserId();
        return Result.success(pendingActionService.confirmAction(id, userId));
    }

    /**
     * 取消待确认操作
     *
     * @param id 待确认操作ID
     * @return void结果
     */
    @PostMapping("/{id}/cancel")
    @Operation(summary = "取消待确认操作", description = "取消指定待确认操作，并校验归属权")
    @ApiResponse(responseCode = "200", description = "取消成功")
    public Result<Void> cancel(
            @Parameter(description = "待确认操作ID", required = true) @PathVariable Long id) {
        Long userId = UserContext.getUserId();
        pendingActionService.cancelAction(id, userId);
        return Result.success();
    }
}
