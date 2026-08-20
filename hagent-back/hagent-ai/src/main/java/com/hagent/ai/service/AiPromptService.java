package com.hagent.ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hagent.ai.model.entity.AiPrompt;

import java.util.List;

/**
 * AI 提示词库服务接口
 *
 * @author H
 */
public interface AiPromptService extends IService<AiPrompt> {

    /**
     * 查询提示词列表（可按分类/关键词过滤，按 sortOrder、id 排序）
     *
     * @param category 分类，可为空
     * @param keyword  标题关键词，可为空
     * @return 提示词列表
     */
    List<AiPrompt> listPrompts(String category, String keyword);

    /**
     * 新增提示词
     *
     * @param prompt 提示词
     * @return 新增后的提示词
     */
    AiPrompt createPrompt(AiPrompt prompt);

    /**
     * 更新提示词（内置提示词仅允许改内容/描述，不允许改分类与标题结构）
     *
     * @param prompt 提示词
     * @return 更新后的提示词
     */
    AiPrompt updatePrompt(AiPrompt prompt);

    /**
     * 删除提示词（内置提示词不可删除）
     *
     * @param id 提示词ID
     */
    void deletePrompt(Long id);

    /**
     * 使用次数自增（原子）
     *
     * @param id 提示词ID
     */
    void increaseUseCount(Long id);
}
