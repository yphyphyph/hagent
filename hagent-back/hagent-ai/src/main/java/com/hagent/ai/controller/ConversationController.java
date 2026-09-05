package com.hagent.ai.controller;

import com.hagent.ai.model.dto.ConversationQueryRequest;
import com.hagent.ai.model.vo.ChatMessageVO;
import com.hagent.ai.model.vo.ContextUsageSnapshot;
import com.hagent.ai.model.vo.ConversationVO;
import com.hagent.ai.service.ChatService;
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
    private final ChatService chatService;

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
     * 获取会话上下文用量（历史打开首值，后端现算，只读不落库）
     */
    @GetMapping("/{id}/context-usage")
    @Operation(summary = "获取会话上下文用量", description = "以后端现算返回历史打开首值四段，与聊天 meta 首值同一口径，不落库")
    @ApiResponse(responseCode = "200", description = "查询成功")
    public Result<ContextUsageSnapshot> contextUsage(
            @Parameter(description = "会话ID", required = true) @PathVariable Long id) {
        Long userId = UserContext.getUserId();
        // 先经归属权校验，再现算用量（复用 listMessages 的 userId 校验逻辑，异常走全局异常）
        conversationService.getConversationById(id, userId);
        return Result.success(chatService.estimateInitial(id));
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

    /**
     * 重命名会话（校验归属权）
     */
    @PutMapping("/{id}/title")
    @Operation(summary = "重命名会话", description = "更新指定会话的标题，并校验会话归属权")
    @ApiResponse(responseCode = "200", description = "重命名成功")
    public Result<Void> rename(
            @Parameter(description = "会话ID", required = true) @PathVariable Long id,
            @RequestBody RenameRequest body) {
        Long userId = UserContext.getUserId();
        // 先经归属权校验，再更新标题（复用 getConversationById 的校验逻辑）
        conversationService.getConversationById(id, userId);
        String title = body.title() == null ? "" : body.title().trim();
        if (title.isEmpty()) {
            return Result.error("标题不能为空");
        }
        conversationService.updateTitle(id, title);
        return Result.success();
    }

    /**
     * 重命名请求体
     */
    public record RenameRequest(String title) {
    }
}
