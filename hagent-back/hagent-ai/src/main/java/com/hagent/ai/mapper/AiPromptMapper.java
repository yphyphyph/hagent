package com.hagent.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hagent.ai.model.entity.AiPrompt;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 提示词库 Mapper
 *
 * @author H
 */
@Mapper
public interface AiPromptMapper extends BaseMapper<AiPrompt> {
}
