package com.hagent.ai.service;

import java.util.Map;

/**
 * Text to SQL 服务接口
 * 将自然语言查询转换为 SQL 并执行
 *
 * @author H
 */
public interface TextToSqlService {

    /**
     * 执行自然语言查询
     *
     * @param question 用户的自然语言问题
     * @return 查询结果，包含 sql、columns、data、rowCount 等信息
     */
    Map<String, Object> queryByNaturalLanguage(String question);
}
