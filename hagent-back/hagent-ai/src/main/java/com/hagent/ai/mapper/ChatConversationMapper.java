package com.hagent.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hagent.ai.model.entity.ChatConversation;
import org.apache.ibatis.annotations.Mapper;

/**
 * Chat Conversation Mapper
 *
 * @author H
 */
@Mapper
public interface ChatConversationMapper extends BaseMapper<ChatConversation> {
}
