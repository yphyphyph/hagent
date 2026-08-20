package com.hagent.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hagent.ai.mapper.AiPromptMapper;
import com.hagent.ai.model.entity.AiPrompt;
import com.hagent.ai.service.AiPromptService;
import com.hagent.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * AI 提示词库服务实现
 *
 * @author H
 */
@Slf4j
@Service
public class AiPromptServiceImpl extends ServiceImpl<AiPromptMapper, AiPrompt>
        implements AiPromptService {

    @Override
    @Transactional(readOnly = true)
    public List<AiPrompt> listPrompts(String category, String keyword) {
        LambdaQueryWrapper<AiPrompt> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(category)) {
            wrapper.eq(AiPrompt::getCategory, category);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.like(AiPrompt::getTitle, keyword);
        }
        wrapper.orderByAsc(AiPrompt::getSortOrder).orderByDesc(AiPrompt::getId);
        return super.list(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiPrompt createPrompt(AiPrompt prompt) {
        if (!StringUtils.hasText(prompt.getTitle())) {
            throw new BusinessException("标题不能为空");
        }
        if (!StringUtils.hasText(prompt.getContent())) {
            throw new BusinessException("提示词内容不能为空");
        }
        // 用户新建的一律为非内置，防止越权标记为内置
        prompt.setId(null);
        prompt.setBuiltin(0);
        prompt.setUseCount(0);
        if (!StringUtils.hasText(prompt.getCategory())) {
            prompt.setCategory("general");
        }
        if (prompt.getSortOrder() == null) {
            prompt.setSortOrder(0);
        }
        if (prompt.getStatus() == null) {
            prompt.setStatus(1);
        }
        super.save(prompt);
        log.info("已新建提示词: id={}, title={}", prompt.getId(), prompt.getTitle());
        return prompt;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiPrompt updatePrompt(AiPrompt prompt) {
        if (prompt.getId() == null) {
            throw new BusinessException("提示词ID不能为空");
        }
        AiPrompt existing = super.getById(prompt.getId());
        if (existing == null) {
            throw new BusinessException("提示词不存在: id=" + prompt.getId());
        }
        // 内置提示词受保护：仅允许更新内容、描述、变量、状态，禁止改标题与分类
        boolean isBuiltin = existing.getBuiltin() != null && existing.getBuiltin() == 1;
        AiPrompt update = new AiPrompt();
        update.setId(existing.getId());
        if (StringUtils.hasText(prompt.getContent())) {
            update.setContent(prompt.getContent());
        }
        update.setDescription(prompt.getDescription());
        update.setVariables(prompt.getVariables());
        if (prompt.getStatus() != null) {
            update.setStatus(prompt.getStatus());
        }
        if (prompt.getSortOrder() != null) {
            update.setSortOrder(prompt.getSortOrder());
        }
        if (!isBuiltin) {
            if (StringUtils.hasText(prompt.getTitle())) {
                update.setTitle(prompt.getTitle());
            }
            if (StringUtils.hasText(prompt.getCategory())) {
                update.setCategory(prompt.getCategory());
            }
        }
        // builtin 字段不允许通过更新变更
        super.updateById(update);
        log.info("已更新提示词: id={}, builtin={}", existing.getId(), isBuiltin);
        return super.getById(existing.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePrompt(Long id) {
        AiPrompt existing = super.getById(id);
        if (existing == null) {
            throw new BusinessException("提示词不存在: id=" + id);
        }
        if (existing.getBuiltin() != null && existing.getBuiltin() == 1) {
            throw new BusinessException("内置提示词不可删除");
        }
        super.removeById(id);
        log.info("已删除提示词: id={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void increaseUseCount(Long id) {
        // SQL 原子自增，避免并发竞态
        LambdaUpdateWrapper<AiPrompt> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AiPrompt::getId, id).setSql("use_count = use_count + 1");
        super.update(wrapper);
    }
}
