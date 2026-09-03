package com.hagent.ai.service;

import java.util.List;

/**
 * 追问推荐服务接口
 * 轻量无状态调用，不涉及 ConversationService / ChatMessageMapper 落库
 *
 * @author H
 */
public interface RecommendService {

    /**
     * 生成追问推荐列表
     * 仅做 ModelRouter.chatWithFallback(prompt) 文本生成并解析为3条追问，失败返回空列表
     *
     * @param prompt 推荐提示词（例如“你是HAgent追问助手...”）
     * @return 3条以内的追问列表，失败返回空列表
     * @author H
     */
    List<String> recommend(String prompt);
}
