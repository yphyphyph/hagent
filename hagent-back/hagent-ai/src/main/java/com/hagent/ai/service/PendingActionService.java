package com.hagent.ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hagent.ai.model.entity.ChatPendingAction;
import com.hagent.ai.model.vo.PendingActionVO;
import com.hagent.common.PageResult;

/**
 * AI待确认操作服务接口
 *
 * @author H
 */
public interface PendingActionService extends IService<ChatPendingAction> {

    /**
     * 分页查询用户待确认操作列表，按创建时间降序
     *
     * @param userId   用户ID（用于归属过滤，为空时不过滤）
     * @param status   状态筛选（为空时不过滤）
     * @param pageNum  当前页码
     * @param pageSize 每页大小
     * @return 分页结果
     */
    PageResult<PendingActionVO> listPendingActions(Long userId, Integer status, int pageNum, int pageSize);

    /**
     * 根据ID查询单个待确认操作（校验归属权，防止越权访问）
     *
     * @param id     待确认操作ID
     * @param userId 当前用户ID（用于归属校验，为空时跳过校验）
     * @return 待确认操作VO
     * @throws BusinessException 当记录不存在或无权访问时抛出
     */
    PendingActionVO getById(Long id, Long userId);

    /**
     * 确认操作：校验归属权、状态为待确认且未过期，置为已确认并立即执行
     * 执行成功置为已执行；执行失败回滚状态为待确认并抛出业务异常
     *
     * @param id     待确认操作ID
     * @param userId 当前用户ID（用于归属校验，为空时跳过校验）
     * @return 执行后的待确认操作VO
     * @throws BusinessException 当状态非法、已过期或执行失败时抛出
     */
    PendingActionVO confirmAction(Long id, Long userId);

    /**
     * 取消操作：校验归属权、状态为待确认，置为已取消
     *
     * @param id     待确认操作ID
     * @param userId 当前用户ID（用于归属校验，为空时跳过校验）
     * @throws BusinessException 当状态非法或无权操作时抛出
     */
    void cancelAction(Long id, Long userId);

    /**
     * 创建待确认操作（供工具链路写入，如 Text-to-SQL 的"先确认后执行"）
     *
     * @param action      待确认操作实体（需含 targetType/payloadJson/previewText/status 等）
     * @param expireHours 过期小时数（小于等于0则不设置过期时间）
     * @return 已持久化的实体（含ID）
     */
    ChatPendingAction createAction(ChatPendingAction action, int expireHours);
}
