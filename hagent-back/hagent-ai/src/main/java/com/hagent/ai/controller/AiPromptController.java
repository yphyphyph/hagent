package com.hagent.ai.controller;

import com.hagent.ai.model.entity.AiPrompt;
import com.hagent.ai.service.AiPromptService;
import com.hagent.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI 提示词库控制器
 *
 * @author H
 */
@Slf4j
@Tag(name = "AI提示词库", description = "AI提示词的增删改查与使用次数统计等接口")
@RestController
@RequestMapping("/admin/ai/prompts")
@RequiredArgsConstructor
public class AiPromptController {

    private final AiPromptService aiPromptService;

    /**
     * 查询提示词列表（可按分类/关键词过滤）
     */
    @GetMapping
    @Operation(summary = "查询提示词列表", description = "支持按分类与标题关键词过滤查询提示词列表")
    @ApiResponse(responseCode = "200", description = "查询成功")
    public Result<List<AiPrompt>> list(
            @Parameter(description = "分类") @RequestParam(required = false) String category,
            @Parameter(description = "标题关键词") @RequestParam(required = false) String keyword) {
        return Result.success(aiPromptService.listPrompts(category, keyword));
    }

    /**
     * 根据ID获取提示词
     */
    @GetMapping("/{id}")
    @Operation(summary = "根据ID获取提示词", description = "根据提示词ID获取详情")
    @ApiResponse(responseCode = "200", description = "查询成功")
    public Result<AiPrompt> getById(
            @Parameter(description = "提示词ID", required = true) @PathVariable Long id) {
        return Result.success(aiPromptService.getById(id));
    }

    /**
     * 新建提示词
     */
    @PostMapping
    @Operation(summary = "新建提示词", description = "新建一条自定义提示词")
    @ApiResponse(responseCode = "200", description = "创建成功")
    public Result<AiPrompt> create(@RequestBody AiPrompt prompt) {
        return Result.success(aiPromptService.createPrompt(prompt));
    }

    /**
     * 更新提示词
     */
    @PutMapping("/{id}")
    @Operation(summary = "更新提示词", description = "根据ID更新提示词内容")
    @ApiResponse(responseCode = "200", description = "更新成功")
    public Result<AiPrompt> update(
            @Parameter(description = "提示词ID", required = true) @PathVariable Long id,
            @RequestBody AiPrompt prompt) {
        prompt.setId(id);
        return Result.success(aiPromptService.updatePrompt(prompt));
    }

    /**
     * 删除提示词（内置不可删除）
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除提示词", description = "根据ID删除提示词，内置提示词不可删除")
    @ApiResponse(responseCode = "200", description = "删除成功")
    public Result<Void> delete(
            @Parameter(description = "提示词ID", required = true) @PathVariable Long id) {
        aiPromptService.deletePrompt(id);
        return Result.success();
    }

    /**
     * 使用次数自增（选用某提示词时调用）
     */
    @PostMapping("/{id}/use")
    @Operation(summary = "使用次数自增", description = "选用某提示词时调用，使用次数加一")
    @ApiResponse(responseCode = "200", description = "操作成功")
    public Result<Void> use(
            @Parameter(description = "提示词ID", required = true) @PathVariable Long id) {
        aiPromptService.increaseUseCount(id);
        return Result.success();
    }
}
