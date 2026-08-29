package com.hagent.server.system.controller;

import com.hagent.common.PageResult;
import com.hagent.common.Result;
import com.hagent.common.annotation.RequireAdmin;
import com.hagent.common.entity.SystemLog;
import com.hagent.common.service.SystemLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 系统操作日志控制器
 * 提供分页查询、详情、清理接口，需管理员权限
 *
 * @author H
 */
@Slf4j
@Tag(name = "系统操作日志", description = "系统操作日志的分页查询、详情与清理")
@RestController
@RequestMapping("/admin/system-log")
@RequireAdmin
@RequiredArgsConstructor
public class SystemLogController {

    private final SystemLogService systemLogService;

    /**
     * 分页查询操作日志
     *
     * @param page          页码
     * @param pageSize      每页大小
     * @param module        模块过滤
     * @param operationType 操作类型过滤
     * @param username      用户名过滤
     * @param success       成功标识 0/1
     * @param startTime     开始时间
     * @param endTime       结束时间
     * @return 分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询操作日志", description = "按模块/操作类型/用户名/成功标识/时间范围分页查询，倒序排列")
    public Result<PageResult<SystemLog>> page(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = "10") int pageSize,
            @Parameter(description = "模块") @RequestParam(required = false) String module,
            @Parameter(description = "操作类型") @RequestParam(required = false) String operationType,
            @Parameter(description = "用户名") @RequestParam(required = false) String username,
            @Parameter(description = "成功标识 0失败 1成功") @RequestParam(required = false) Integer success,
            @Parameter(description = "开始时间 yyyy-MM-dd HH:mm:ss 或 ISO_OFFSET_DATE_TIME") @RequestParam(required = false) String startTime,
            @Parameter(description = "结束时间 yyyy-MM-dd HH:mm:ss 或 ISO_OFFSET_DATE_TIME") @RequestParam(required = false) String endTime) {
        OffsetDateTime start = parseDateTime(startTime);
        OffsetDateTime end = parseDateTime(endTime);
        PageResult<SystemLog> result = systemLogService.page(page, pageSize, module, operationType, username, success, start, end);
        return Result.success(result);
    }

    /**
     * 解析时间字符串为 OffsetDateTime，兼容 yyyy-MM-dd HH:mm:ss 与 ISO_OFFSET_DATE_TIME
     * <p>
     * 无时区信息的字符串按系统默认时区解释
     *
     * @param value 时间字符串
     * @return OffsetDateTime 或 null
     */
    private OffsetDateTime parseDateTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        // 1. 优先 ISO_OFFSET_DATE_TIME（如 2026-08-29T10:00:00+08:00）
        try {
            return OffsetDateTime.parse(trimmed, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException ignored) {
        }
        // 2. 兼容 ISO_DATE_TIME 带偏移的情况
        try {
            return OffsetDateTime.parse(trimmed, DateTimeFormatter.ISO_DATE_TIME);
        } catch (DateTimeParseException ignored) {
        }
        // 3. 兼容 yyyy-MM-dd HH:mm:ss（按系统默认时区）
        try {
            LocalDateTime ldt = LocalDateTime.parse(trimmed, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return ldt.atZone(ZoneId.systemDefault()).toOffsetDateTime();
        } catch (DateTimeParseException ignored) {
        }
        // 4. 兼容 yyyy-MM-dd（按当天 00:00:00，系统默认时区）
        try {
            LocalDate ld = LocalDate.parse(trimmed, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            return ld.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
        } catch (DateTimeParseException ignored) {
        }
        log.warn("无法解析时间参数: {}", value);
        return null;
    }

    /**
     * 日志详情
     *
     * @param id 日志ID
     * @return 日志详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "日志详情", description = "按ID查询单条操作日志详情")
    public Result<SystemLog> detail(
            @Parameter(description = "日志ID", required = true) @PathVariable Long id) {
        SystemLog logEntity = systemLogService.getById(id);
        if (logEntity == null) {
            return Result.error(404, "日志不存在");
        }
        return Result.success(logEntity);
    }

    /**
     * 清理日志
     *
     * @param days 保留天数，删除 created_at &lt; NOW() - days 的记录
     * @return 删除条数
     */
    @DeleteMapping("/clean")
    @Operation(summary = "清理日志", description = "删除指定天数前的日志，默认 30 天")
    public Result<Map<String, Object>> clean(
            @Parameter(description = "保留天数") @RequestParam(defaultValue = "30") int days) {
        long count = systemLogService.clean(days);
        log.info("清理操作日志: days={}, count={}", days, count);
        return Result.success(Map.of("deleted", count, "days", days));
    }
}
