package com.hagent.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * 系统操作日志实体，对应表 system_log
 * <p>
 * 字段与 database/system_log.sql 19列一一对应
 *
 * @author H
 */
@Data
@TableName("system_log")
public class SystemLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 链路追踪ID */
    private String traceId;

    /** 操作用户ID */
    private Long userId;

    /** 操作用户名 */
    private String username;

    /** 模块/业务类型 */
    private String module;

    /** 业务主键ID */
    private String businessId;

    /** 操作类型(CREATE/UPDATE/DELETE/QUERY/LOGIN/EXPORT/OTHER) */
    private String operationType;

    /** 操作描述 */
    private String actionDesc;

    /** 请求方法(类名#方法名) */
    private String method;

    /** HTTP请求方法 */
    private String requestMethod;

    /** 请求地址 */
    private String requestUrl;

    /** 请求参数(JSON) */
    private String requestParams;

    /** 响应结果(JSON) */
    private String responseResult;

    /** 是否成功(0失败 1成功) */
    private Integer success;

    /** 错误信息 */
    private String errorMessage;

    /** 操作IP地址 */
    private String ip;

    /** 浏览器标识 */
    private String userAgent;

    /** 耗时(毫秒) */
    private Integer costTimeMs;

    /** 创建时间（PG TIMESTAMPTZ 原生类型，前后端 ISO-8601 可读） */
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
