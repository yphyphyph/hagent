package com.hagent.common.service;

import com.hagent.common.PageResult;
import com.hagent.common.entity.SystemLog;

import java.time.OffsetDateTime;

/**
 * 系统操作日志服务接口
 *
 * @author H
 */
public interface SystemLogService {

    /**
     * 保存操作日志
     *
     * @param systemLog 日志实体
     */
    void saveLog(SystemLog systemLog);

    /**
     * 分页查询操作日志
     *
     * @param page          页码（1起始）
     * @param pageSize      每页大小
     * @param module        模块过滤
     * @param operationType 操作类型过滤
     * @param username      用户名过滤
     * @param success       成功标识过滤 0/1
     * @param startTime     开始时间
     * @param endTime       结束时间
     * @return 分页结果
     */
    PageResult<SystemLog> page(int page, int pageSize, String module, String operationType,
                               String username, Integer success,
                               OffsetDateTime startTime, OffsetDateTime endTime);

    /**
     * 按ID查询详情
     *
     * @param id 日志ID
     * @return 日志实体，不存在返回 null
     */
    SystemLog getById(Long id);

    /**
     * 清理指定天数前的日志
     *
     * @param days 保留天数，删除 created_at &lt; NOW() - days 的记录
     * @return 删除条数
     */
    long clean(int days);
}
