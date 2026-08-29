package com.hagent.ai.controller;

import com.hagent.ai.model.entity.SqlTablePolicy;
import com.hagent.ai.service.SqlTablePolicyService;
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
 * Text-to-SQL 表白名单策略控制器
 * 管理端维护允许被 Text-to-SQL 查询的表及其行级隔离策略
 *
 * @author H
 */
@Slf4j
@Tag(name = "Text-to-SQL表策略", description = "Text-to-SQL 表白名单与行级隔离策略的增删改查")
@RestController
@RequestMapping("/admin/ai/sql-policies")
@RequiredArgsConstructor
public class SqlTablePolicyController {

    private final SqlTablePolicyService sqlTablePolicyService;

    /**
     * 查询策略列表（含停用）
     */
    @GetMapping
    @Operation(summary = "查询策略列表", description = "返回全部 Text-to-SQL 表白名单策略（含停用）")
    @ApiResponse(responseCode = "200", description = "查询成功")
    public Result<List<SqlTablePolicy>> list() {
        return Result.success(sqlTablePolicyService.listPolicies());
    }

    /**
     * 新增策略（表存在性校验）
     */
    @PostMapping
    @Operation(summary = "新增策略", description = "新增表白名单策略，保存前校验表在当前数据库中真实存在")
    @ApiResponse(responseCode = "200", description = "创建成功")
    public Result<SqlTablePolicy> create(@RequestBody SqlTablePolicy policy) {
        return Result.success(sqlTablePolicyService.createPolicy(policy));
    }

    /**
     * 更新策略
     */
    @PutMapping("/{id}")
    @Operation(summary = "更新策略", description = "根据ID更新表白名单策略")
    @ApiResponse(responseCode = "200", description = "更新成功")
    public Result<SqlTablePolicy> update(
            @Parameter(description = "策略ID", required = true) @PathVariable Long id,
            @RequestBody SqlTablePolicy policy) {
        return Result.success(sqlTablePolicyService.updatePolicy(id, policy));
    }

    /**
     * 删除策略
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除策略", description = "根据ID删除表白名单策略，删除后该表立即不可被 Text-to-SQL 查询")
    @ApiResponse(responseCode = "200", description = "删除成功")
    public Result<Void> delete(
            @Parameter(description = "策略ID", required = true) @PathVariable Long id) {
        sqlTablePolicyService.deletePolicy(id);
        return Result.success();
    }
}
