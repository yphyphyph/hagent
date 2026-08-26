package com.hagent.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hagent.ai.mapper.ChatConversationMapper;
import com.hagent.ai.mapper.ChatMessageMapper;
import com.hagent.ai.model.dto.ConversationQueryRequest;
import com.hagent.ai.model.entity.ChatConversation;
import com.hagent.ai.model.entity.ChatMessage;
import com.hagent.ai.model.enums.ChatSceneEnum;
import com.hagent.ai.model.enums.MessageRoleEnum;
import com.hagent.ai.model.vo.ChatMessageVO;
import com.hagent.ai.model.vo.ConversationVO;
import com.hagent.ai.service.ConversationService;
import com.hagent.common.PageResult;
import com.hagent.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 会话服务实现
 *
 * @author H
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationServiceImpl extends ServiceImpl<ChatConversationMapper, ChatConversation>
        implements ConversationService {

    private final ChatMessageMapper chatMessageMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConversationVO createConversation(Long userId, Integer scene) {
        ChatConversation conversation = new ChatConversation();
        conversation.setUserId(userId);
        conversation.setTitle("新会话");
        conversation.setScene(scene != null ? scene : ChatSceneEnum.GENERAL.getCode());
        conversation.setMessageCount(0);
        conversation.setTotalTokens(0L);
        conversation.setLastMessageAt(LocalDateTime.now());
        conversation.setStatus(1);
        // 审计字段由MyBatis-Plus自动填充（MetaObjectHandler），无需手动设置
        super.save(conversation);

        log.info("已创建会话: id={}, userId={}, scene={}", conversation.getId(), userId, scene);
        return toVO(conversation);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<ConversationVO> listConversations(Long userId, ConversationQueryRequest request) {
        LambdaQueryWrapper<ChatConversation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatConversation::getUserId, userId);

        if (request.getScene() != null) {
            wrapper.eq(ChatConversation::getScene, request.getScene());
        }
        if (request.getStatus() != null) {
            wrapper.eq(ChatConversation::getStatus, request.getStatus());
        }
        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.like(ChatConversation::getTitle, request.getKeyword());
        }

        wrapper.orderByDesc(ChatConversation::getLastMessageAt);

        Page<ChatConversation> page = new Page<>(request.getPageNum(), request.getPageSize());
        Page<ChatConversation> result = super.page(page, wrapper);

        List<ConversationVO> voList = result.getRecords().stream()
                .map(this::toVO)
                .collect(Collectors.toList());

        return PageResult.of(voList, result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    @Override
    @Transactional(readOnly = true)
    public ConversationVO getConversationById(Long id, Long userId) {
        ChatConversation conversation = super.getById(id);
        if (conversation == null) {
            throw new BusinessException("会话不存在: id=" + id);
        }
        // 归属权校验，防止越权访问他人会话
        if (userId != null && !userId.equals(conversation.getUserId())) {
            throw new BusinessException("无权访问该会话");
        }
        return toVO(conversation);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageVO> listMessages(Long conversationId, Long userId) {
        // 先校验会话归属权
        ChatConversation conversation = super.getById(conversationId);
        if (conversation == null) {
            throw new BusinessException("会话不存在: id=" + conversationId);
        }
        if (userId != null && !userId.equals(conversation.getUserId())) {
            throw new BusinessException("无权访问该会话");
        }
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getConversationId, conversationId);
        wrapper.orderByAsc(ChatMessage::getCreatedAt);

        return chatMessageMapper.selectList(wrapper).stream()
                .map(this::toMessageVO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteConversation(Long id, Long userId) {
        ChatConversation conversation = super.getById(id);
        if (conversation == null) {
            throw new BusinessException("会话不存在: id=" + id);
        }
        // 归属权校验
        if (userId != null && !userId.equals(conversation.getUserId())) {
            throw new BusinessException("无权删除该会话");
        }
        super.removeById(id);
        // 同时删除关联的消息（ChatMessage无逻辑删除字段，物理删除）
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getConversationId, id);
        chatMessageMapper.delete(wrapper);
        log.info("已删除会话及消息: id={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTitle(Long id, String title) {
        ChatConversation conversation = new ChatConversation();
        conversation.setId(id);
        conversation.setTitle(title);
        super.updateById(conversation);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStats(Long id, Integer tokenCount) {
        // 使用SQL原子自增，避免read-then-write并发竞态导致统计数据丢失
        int safeTokenCount = tokenCount != null ? tokenCount : 0;
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ChatConversation> updateWrapper =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
        updateWrapper.eq(ChatConversation::getId, id)
                .setSql("message_count = message_count + 1")
                .setSql("total_tokens = total_tokens + " + safeTokenCount)
                .set(ChatConversation::getLastMessageAt, LocalDateTime.now());
        super.update(updateWrapper);
    }

    /**
     * 实体转VO
     */
    private ConversationVO toVO(ChatConversation entity) {
        ConversationVO vo = new ConversationVO();
        vo.setId(entity.getId());
        vo.setUserId(entity.getUserId());
        vo.setTitle(entity.getTitle());
        vo.setScene(entity.getScene());
        ChatSceneEnum sceneEnum = ChatSceneEnum.fromCode(entity.getScene());
        vo.setSceneDesc(sceneEnum != null ? sceneEnum.getDesc() : "");
        vo.setSystemPrompt(entity.getSystemPrompt());
        vo.setRagFileIds(entity.getRagFileIds());
        vo.setModelName(entity.getModelName());
        vo.setTemperature(entity.getTemperature());
        vo.setMaxTokens(entity.getMaxTokens());
        vo.setTotalTokens(entity.getTotalTokens());
        vo.setMessageCount(entity.getMessageCount());
        vo.setLastMessageAt(entity.getLastMessageAt());
        vo.setStatus(entity.getStatus());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }

    /**
     * 实体转消息VO
     */
    private ChatMessageVO toMessageVO(ChatMessage entity) {
        ChatMessageVO vo = new ChatMessageVO();
        vo.setId(entity.getId());
        vo.setConversationId(entity.getConversationId());
        vo.setRole(entity.getRole());
        MessageRoleEnum roleEnum = MessageRoleEnum.fromCode(entity.getRole());
        vo.setRoleDesc(roleEnum != null ? roleEnum.getDesc() : "未知");
        vo.setContent(entity.getContent());
        vo.setMessageType(entity.getMessageType());
        vo.setToolName(entity.getToolName());
        vo.setToolPayload(entity.getToolPayload());
        // 历史思考内容回显
        vo.setThinkingContent(entity.getThinkingContent());
        // 来源引用回显(JSON数组)
        vo.setSources(entity.getSources());
        vo.setTokenCount(entity.getTokenCount());
        vo.setStatus(entity.getStatus());
        vo.setErrorMessage(entity.getErrorMessage());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
