package com.hagent.ai.service;

import java.util.Map;

/**
 * Text to SQL 服务接口
 * 将自然语言查询转换为 SQL 并执行
 *
 * @author H
 */
public interface TextToSqlService {

    /** 待确认操作的目标类型：SQL 查询（PendingAction.executeAction 分发依据） */
    String TARGET_TYPE_SQL_QUERY = "SQL_QUERY";

    /**
     * 执行自然语言查询（先确认后执行：生成SQL并通过安全校验后，
     * 创建待确认操作返回 pendingActionId，由用户在确认卡片上确认后才真正执行）
     *
     * @param question 用户的自然语言问题
     * @return 查询结果；成功时包含 pendingConfirmation=true、pendingActionId、sql、question
     */
    Map<String, Object> queryByNaturalLanguage(String question);
}
