package com.hagent.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hagent.ai.mapper.ChatPendingActionMapper;
import com.hagent.ai.model.entity.ChatPendingAction;
import com.hagent.ai.model.enums.PendingActionStatusEnum;
import com.hagent.ai.model.vo.PendingActionVO;
import com.hagent.ai.service.PendingActionService;
import com.hagent.ai.service.TextToSqlService;
import com.hagent.common.PageResult;
import com.hagent.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI待确认操作服务实现
 * 处理待确认操作的查询、确认、取消与执行流程
 * <p>
 * 支持的目标类型：SQL_QUERY（Text-to-SQL 先确认后执行）。
 *
 * @author H
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PendingActionServiceImpl extends ServiceImpl<ChatPendingActionMapper, ChatPendingAction>
        implements PendingActionService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public PageResult<PendingActionVO> listPendingActions(Long userId, Integer status, int pageNum, int pageSize) {
        LambdaQueryWrapper<ChatPendingAction> wrapper = new LambdaQueryWrapper<>();
        if (userId != null) {
            wrapper.eq(ChatPendingAction::getUserId, userId);
        }
        if (status != null) {
            wrapper.eq(ChatPendingAction::getStatus, status);
        }
        wrapper.orderByDesc(ChatPendingAction::getCreatedAt);

        Page<ChatPendingAction> page = new Page<>(pageNum, pageSize);
        Page<ChatPendingAction> result = super.page(page, wrapper);

        List<PendingActionVO> voList = result.getRecords().stream()
                .map(this::toVO)
                .collect(Collectors.toList());

        return PageResult.of(voList, result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    @Override
    @Transactional(readOnly = true)
    public PendingActionVO getById(Long id, Long userId) {
        ChatPendingAction action = super.getById(id);
        if (action == null) {
            throw new BusinessException("待确认操作不存在: id=" + id);
        }
        checkOwnership(action, userId);
        return toVO(action);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PendingActionVO confirmAction(Long id, Long userId) {
        ChatPendingAction action = super.getById(id);
        if (action == null) {
            throw new BusinessException("待确认操作不存在: id=" + id);
        }
        checkOwnership(action, userId);

        // 状态校验：仅待确认状态可执行确认
        if (!PendingActionStatusEnum.PENDING.getCode().equals(action.getStatus())) {
            throw new BusinessException("当前状态不允许确认，仅待确认操作可确认");
        }
        // 过期校验
        if (action.getExpireTime() != null && action.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException("待确认操作已过期，无法确认");
        }

        // 置为已确认并记录确认时间
        action.setStatus(PendingActionStatusEnum.CONFIRMED.getCode());
        action.setConfirmedAt(LocalDateTime.now());
        super.updateById(action);
        log.info("待确认操作已确认: id={}, userId={}", id, userId);

        // 执行实际操作
        try {
            executeAction(action);
            action.setStatus(PendingActionStatusEnum.EXECUTED.getCode());
            action.setExecutedAt(LocalDateTime.now());
            super.updateById(action);
            log.info("待确认操作执行成功: id={}", id);
        } catch (Exception e) {
            // 执行失败，状态回滚为待确认
            log.error("待确认操作执行失败，状态回滚: id={}", id, e);
            action.setStatus(PendingActionStatusEnum.PENDING.getCode());
            action.setConfirmedAt(null);
            super.updateById(action);
            throw new BusinessException("操作执行失败: " + e.getMessage());
        }
        return toVO(action);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelAction(Long id, Long userId) {
        ChatPendingAction action = super.getById(id);
        if (action == null) {
            throw new BusinessException("待确认操作不存在: id=" + id);
        }
        checkOwnership(action, userId);

        // 状态校验：仅待确认状态可取消
        if (!PendingActionStatusEnum.PENDING.getCode().equals(action.getStatus())) {
            throw new BusinessException("当前状态不允许取消，仅待确认操作可取消");
        }

        action.setStatus(PendingActionStatusEnum.CANCELLED.getCode());
        super.updateById(action);
        log.info("待确认操作已取消: id={}, userId={}", id, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatPendingAction createAction(ChatPendingAction action, int expireHours) {
        if (expireHours > 0) {
            action.setExpireTime(LocalDateTime.now().plusHours(expireHours));
        }
        action.setStatus(PendingActionStatusEnum.PENDING.getCode());
        super.save(action);
        log.info("待确认操作已创建: id={}, targetType={}, conversationId={}",
                action.getId(), action.getTargetType(), action.getConversationId());
        return action;
    }

    /**
     * 执行实际操作，根据目标类型和载荷分发到对应处理器
     * <p>
     * 当前支持：SQL_QUERY（Text-to-SQL 确认后执行，结果写回载荷供前端展示）。
     * 后续新增其他业务操作时在此扩展分发逻辑。
     *
     * @param action 待确认操作实体
     * @throws BusinessException 当目标类型不支持时抛出
     */
    private void executeAction(ChatPendingAction action) {
        if (TextToSqlService.TARGET_TYPE_SQL_QUERY.equals(action.getTargetType())) {
            executeSqlQuery(action);
            return;
        }
        throw new BusinessException("不支持的目标类型: " + action.getTargetType());
    }

    /**
     * 执行待确认的 SQL 查询，并把行数与结果数据写回载荷 JSON
     *
     * @param action 待确认操作实体（payloadJson 含 sql/question 字段）
     */
    private void executeSqlQuery(ChatPendingAction action) {
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    action.getPayloadJson(),
                    new TypeReference<Map<String, Object>>() { });
            String sql = String.valueOf(payload.get("sql"));
            log.info("执行待确认SQL: pendingActionId={}", action.getId());

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

            payload.put("rowCount", rows.size());
            // 限制回传数据量，防止超大结果撑爆消息体（最多100行）
            payload.put("data", rows.size() > 100 ? rows.subList(0, 100) : rows);
            payload.put("truncated", rows.size() > 100);
            action.setPayloadJson(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("待确认SQL执行失败: pendingActionId={}", action.getId(), e);
            throw new BusinessException("SQL执行失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ChatPendingAction findLatestPendingByConversationId(Long conversationId) {
        // 查询会话下最新一条待确认状态的记录
        if (conversationId == null) {
            return null;
        }
        LambdaQueryWrapper<ChatPendingAction> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatPendingAction::getConversationId, conversationId)
                .eq(ChatPendingAction::getStatus, PendingActionStatusEnum.PENDING.getCode())
                .orderByDesc(ChatPendingAction::getCreatedAt)
                .last("LIMIT 1");
        return super.getOne(wrapper, false);
    }

    /**
     * 归属权校验，防止越权访问他人待确认操作
     *
     * @param action 待确认操作实体
     * @param userId 当前用户ID（为空时跳过校验）
     * @throws BusinessException 当归属不匹配时抛出
     */
    private void checkOwnership(ChatPendingAction action, Long userId) {
        if (userId != null && !userId.equals(action.getUserId())) {
            throw new BusinessException("无权访问该待确认操作");
        }
    }

    /**
     * 实体转VO
     *
     * @param entity 待确认操作实体
     * @return 待确认操作VO
     */
    private PendingActionVO toVO(ChatPendingAction entity) {
        PendingActionVO vo = new PendingActionVO();
        vo.setId(entity.getId());
        vo.setConversationId(entity.getConversationId());
        vo.setUserId(entity.getUserId());
        vo.setActionType(entity.getActionType());
        vo.setTargetType(entity.getTargetType());
        vo.setTargetId(entity.getTargetId());
        vo.setPayloadJson(entity.getPayloadJson());
        vo.setPreviewText(entity.getPreviewText());
        vo.setStatus(entity.getStatus());
        PendingActionStatusEnum statusEnum = PendingActionStatusEnum.fromCode(entity.getStatus());
        vo.setStatusDesc(statusEnum != null ? statusEnum.getDesc() : "");
        vo.setExpireTime(entity.getExpireTime());
        vo.setConfirmedAt(entity.getConfirmedAt());
        vo.setExecutedAt(entity.getExecutedAt());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
