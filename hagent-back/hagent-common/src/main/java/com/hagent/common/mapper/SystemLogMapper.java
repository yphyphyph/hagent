package com.hagent.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hagent.common.entity.SystemLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统操作日志 Mapper
 *
 * @author H
 */
@Mapper
public interface SystemLogMapper extends BaseMapper<SystemLog> {
}
