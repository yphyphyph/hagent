package com.hagent.ai.controller;

import com.hagent.ai.mcp.McpToolService;
import com.hagent.common.Result;
import com.hagent.common.annotation.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具台控制器
 * 工具总览 + 在线调试 + 接入配置生成
 *
 * @author H
 */
@Slf4j
@Tag(name = "MCP工具台", description = "MCP工具总览、在线调试与接入配置生成接口")
@RestController
@RequestMapping("/admin/ai/mcp")
@RequiredArgsConstructor
public class McpToolController {

    private final McpToolService mcpToolService;

    /**
     * 工具总览（反射列出所有 MCP 工具）
     */
    @GetMapping("/tools")
    @Operation(summary = "MCP工具总览", description = "反射列出当前注册的所有 MCP 工具及其元信息")
    @ApiResponse(responseCode = "200", description = "查询成功")
    public Result<List<Map<String, Object>>> listTools() {
        return Result.success(mcpToolService.listTools());
    }

    /**
     * 在线调试：调用指定工具
     * 请求体：{ "toolName": "searchBlog", "params": { "keyword": "AI" } }
     */
    @PostMapping("/invoke")
    @Operation(summary = "在线调试调用工具", description = "根据 toolName 与 params 在线调用指定 MCP 工具并返回执行结果")
    @ApiResponse(responseCode = "200", description = "调用成功")
    @RateLimit(window = 60, limit = 30, message = "调试请求过于频繁，请稍后重试")
    @SuppressWarnings("unchecked")
    public Result<Object> invoke(@RequestBody Map<String, Object> body) {
        String toolName = body.get("toolName") != null ? String.valueOf(body.get("toolName")) : null;
        if (!StringUtils.hasText(toolName)) {
            return Result.error("缺少 toolName");
        }
        Object paramsObj = body.get("params");
        Map<String, Object> params = paramsObj instanceof Map ? (Map<String, Object>) paramsObj : Map.of();
        return Result.success(mcpToolService.invoke(toolName, params));
    }

    /**
     * MCP 接入配置生成
     */
    @GetMapping("/config")
    @Operation(summary = "MCP接入配置", description = "生成 MCP 客户端接入所需的连接配置信息")
    @ApiResponse(responseCode = "200", description = "查询成功")
    public Result<Map<String, Object>> config() {
        return Result.success(mcpToolService.connectionConfig());
    }
}
