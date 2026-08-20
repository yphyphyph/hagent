package com.hagent.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hagent.ai.model.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * Chat Message Mapper
 *
 * @author H
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
