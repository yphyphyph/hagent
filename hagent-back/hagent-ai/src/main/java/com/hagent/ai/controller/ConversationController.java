package com.hagent.ai.controller;

import com.hagent.ai.model.dto.ConversationQueryRequest;
import com.hagent.ai.model.vo.ChatMessageVO;
import com.hagent.ai.model.vo.ConversationVO;
import com.hagent.ai.service.ConversationService;
import com.hagent.common.PageResult;
import com.hagent.common.Result;
import com.hagent.common.context.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话管理控制器
 *
 * @author H
 */
@Slf4j
@Tag(name = "AI会话管理", description = "AI会话的分页查询、详情、消息列表与删除等接口")
@RestController
@RequestMapping("/admin/ai/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    /**
     * 分页查询会话列表
     */
    @GetMapping
    @Operation(summary = "分页查询会话列表", description = "按条件分页查询当前用户的AI会话列表")
    @ApiResponse(responseCode = "200", description = "查询成功")
    public Result<PageResult<ConversationVO>> list(@Valid ConversationQueryRequest request) {
        Long userId = UserContext.getUserId();
        return Result.success(conversationService.listConversations(userId, request));
    }

    /**
     * 根据ID获取会话（校验归属权）
     */
    @GetMapping("/{id}")
    @Operation(summary = "根据ID获取会话", description = "根据会话ID获取详情，并校验会话归属权")
    @ApiResponse(responseCode = "200", description = "查询成功")
    public Result<ConversationVO> getById(
            @Parameter(description = "会话ID", required = true) @PathVariable Long id) {
        Long userId = UserContext.getUserId();
        return Result.success(conversationService.getConversationById(id, userId));
    }

    /**
     * 获取会话中的消息列表（校验归属权）
     */
    @GetMapping("/{id}/messages")
    @Operation(summary = "获取会话消息列表", description = "获取指定会话的历史消息列表，并校验会话归属权")
    @ApiResponse(responseCode = "200", description = "查询成功")
    public Result<List<ChatMessageVO>> listMessages(
            @Parameter(description = "会话ID", required = true) @PathVariable Long id) {
        Long userId = UserContext.getUserId();
        return Result.success(conversationService.listMessages(id, userId));
    }

    /**
     * 删除会话（校验归属权）
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除会话", description = "删除指定会话及其关联消息，并校验会话归属权")
    @ApiResponse(responseCode = "200", description = "删除成功")
    public Result<Void> delete(
            @Parameter(description = "会话ID", required = true) @PathVariable Long id) {
        Long userId = UserContext.getUserId();
        conversationService.deleteConversation(id, userId);
        return Result.success();
    }
}
