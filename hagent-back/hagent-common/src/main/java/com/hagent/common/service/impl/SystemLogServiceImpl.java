package com.hagent.common.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hagent.common.PageResult;
import com.hagent.common.entity.SystemLog;
import com.hagent.common.mapper.SystemLogMapper;
import com.hagent.common.service.SystemLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;

/**
 * 系统操作日志服务实现
 *
 * @author H
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemLogServiceImpl implements SystemLogService {

    private final SystemLogMapper systemLogMapper;

    /**
     * 保存操作日志，使用新事务避免影响主业务
     *
     * @param systemLog 日志实体
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveLog(SystemLog systemLog) {
        try {
            systemLogMapper.insert(systemLog);
        } catch (Exception e) {
            // 仅记录警告，不抛出异常影响主事务
            log.warn("保存操作日志失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 分页查询操作日志，按过滤条件 + 时间范围 + 创建时间倒序
     *
     * @param page          页码
     * @param pageSize      每页大小
     * @param module        模块
     * @param operationType 操作类型
     * @param username      用户名
     * @param success       成功标识
     * @param startTime     开始时间
     * @param endTime       结束时间
     * @return 分页结果
     */
    @Override
    public PageResult<SystemLog> page(int page, int pageSize, String module, String operationType,
                                      String username, Integer success,
                                      OffsetDateTime startTime, OffsetDateTime endTime) {
        LambdaQueryWrapper<SystemLog> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(module)) {
            wrapper.eq(SystemLog::getModule, module);
        }
        if (StringUtils.hasText(operationType)) {
            wrapper.eq(SystemLog::getOperationType, operationType);
        }
        if (StringUtils.hasText(username)) {
            wrapper.eq(SystemLog::getUsername, username);
        }
        if (success != null) {
            wrapper.eq(SystemLog::getSuccess, success);
        }
        if (startTime != null) {
            wrapper.ge(SystemLog::getCreatedAt, startTime);
        }
        if (endTime != null) {
            wrapper.le(SystemLog::getCreatedAt, endTime);
        }
        wrapper.orderByDesc(SystemLog::getCreatedAt);

        Page<SystemLog> mpPage = new Page<>(page, pageSize);
        Page<SystemLog> result = systemLogMapper.selectPage(mpPage, wrapper);
        return PageResult.of(result.getRecords(), result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    /**
     * 按ID查询详情
     *
     * @param id 日志ID
     * @return 日志实体
     */
    @Override
    public SystemLog getById(Long id) {
        return systemLogMapper.selectById(id);
    }

    /**
     * 清理指定天数前的日志
     *
     * @param days 保留天数
     * @return 删除条数
     */
    @Override
    public long clean(int days) {
        OffsetDateTime threshold = OffsetDateTime.now().minusDays(days);
        return systemLogMapper.delete(Wrappers.lambdaQuery(SystemLog.class)
                .lt(SystemLog::getCreatedAt, threshold));
    }
}
