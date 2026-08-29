package com.hagent.server.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hagent.server.auth.model.entity.UserDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper 接口
 *
 * @author H
 */
@Mapper
public interface UserMapper extends BaseMapper<UserDO> {
}