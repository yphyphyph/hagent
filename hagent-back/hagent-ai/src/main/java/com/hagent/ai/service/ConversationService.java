package com.hagent.ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hagent.ai.model.dto.ConversationQueryRequest;
import com.hagent.ai.model.entity.ChatConversation;
import com.hagent.ai.model.vo.ChatMessageVO;
import com.hagent.ai.model.vo.ConversationVO;
import com.hagent.common.PageResult;

import java.util.List;

/**
 * 会话服务接口
 *
 * @author H
 */
public interface ConversationService extends IService<ChatConversation> {

    /**
     * 创建新会话
     *
     * @param userId 用户ID
     * @param scene  聊天场景
     * @return 会话VO
     */
    ConversationVO createConversation(Long userId, Integer scene);

    /**
     * 分页查询会话列表
     *
     * @param userId  用户ID
     * @param request 查询请求
     * @return 分页结果
     */
    PageResult<ConversationVO> listConversations(Long userId, ConversationQueryRequest request);

    /**
     * 根据ID获取会话（校验归属权，防止越权访问）
     *
     * @param id     会话ID
     * @param userId 当前用户ID（用于归属校验）
     * @return 会话VO
     */
    ConversationVO getConversationById(Long id, Long userId);

    /**
     * 获取会话中的消息列表（校验归属权，防止越权访问）
     *
     * @param conversationId 会话ID
     * @param userId         当前用户ID（用于归属校验）
     * @return 消息VO列表
     */
    List<ChatMessageVO> listMessages(Long conversationId, Long userId);

    /**
     * 删除会话（校验归属权，防止越权访问）
     *
     * @param id     会话ID
     * @param userId 当前用户ID（用于归属校验）
     */
    void deleteConversation(Long id, Long userId);

    /**
     * 更新会话标题
     *
     * @param id    会话ID
     * @param title 新标题
     */
    void updateTitle(Long id, String title);

    /**
     * 更新会话统计（消息数量和Token用量）
     *
     * @param id         会话ID
     * @param tokenCount 消耗的Token数量
     */
    void updateStats(Long id, Integer tokenCount);
}
