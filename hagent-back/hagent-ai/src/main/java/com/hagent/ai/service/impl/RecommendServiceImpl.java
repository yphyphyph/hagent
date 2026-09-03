package com.hagent.ai.service.impl;

import com.hagent.ai.core.ModelRouter;
import com.hagent.ai.service.RecommendService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 追问推荐服务实现
 * 仅做轻量 LLM 文本生成，不涉及会话或消息持久化，避免污染 chat_message 历史
 *
 * @author H
 */
@Slf4j
@Service
public class RecommendServiceImpl implements RecommendService {

    private final ModelRouter modelRouter;
    private final ObjectMapper objectMapper;

    /**
     * 引号内内容提取（兼容中英文引号）
     */
    private static final Pattern QUOTED_PATTERN = Pattern.compile("[\"“'『「]([^\"”'』」]{2,30})[\"”'』」]");

    public RecommendServiceImpl(ModelRouter modelRouter, ObjectMapper objectMapper) {
        this.modelRouter = modelRouter;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<String> recommend(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return List.of();
        }
        try {
            String raw = modelRouter.chatWithFallback(prompt);
            List<String> parsed = parseQuestionsFromText(raw);
            if (parsed != null && !parsed.isEmpty()) {
                return parsed.stream().map(String::trim).filter(s -> !s.isEmpty()).limit(3).toList();
            }
            log.warn("追问推荐解析为空，raw前80字符={}", raw == null ? "null" : raw.substring(0, Math.min(80, raw.length())));
            return List.of();
        } catch (Exception e) {
            log.warn("追问推荐生成失败", e);
            return List.of();
        }
    }

    /**
     * 与前端 parseQuestionsFromText 对齐的解析逻辑
     *
     * @param raw 模型返回原文
     * @return 追问列表
     * @author H
     */
    private List<String> parseQuestionsFromText(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        // 优先按 JSON 数组解析
        try {
            List<String> parsed = objectMapper.readValue(trimmed, new TypeReference<List<String>>() {});
            if (parsed != null) {
                List<String> list = parsed.stream().map(s -> s == null ? "" : s.trim()).filter(s -> !s.isEmpty()).limit(3).toList();
                if (!list.isEmpty()) return list;
            }
        } catch (Exception ignored) {
            // 非 JSON，尝试提取引号内
        }
        Matcher m = QUOTED_PATTERN.matcher(trimmed);
        List<String> quoted = new ArrayList<>();
        while (m.find()) {
            String g = m.group(1).trim();
            if (!g.isEmpty()) quoted.add(g);
        }
        if (quoted.size() >= 2) return quoted.stream().limit(3).toList();
        String[] lines = trimmed.split("[\\n；;]+");
        List<String> cleaned = new ArrayList<>();
        for (String s : lines) {
            String t = s.replaceAll("^[\\d.、\\s\\-*]+", "").trim();
            if (t.length() >= 4 && t.length() <= 24) cleaned.add(t);
        }
        if (cleaned.size() >= 2) return cleaned.stream().limit(3).toList();
        return null;
    }
}
