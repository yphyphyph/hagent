package com.hagent.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hagent.ai.model.entity.ChatPendingAction;
import org.apache.ibatis.annotations.Mapper;

/**
 * Chat Pending Action Mapper
 *
 * @author H
 */
@Mapper
public interface ChatPendingActionMapper extends BaseMapper<ChatPendingAction> {
}
