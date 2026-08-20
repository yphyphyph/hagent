package com.hagent.knowledge.util;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义分块工具：按段落 / 句子边界切分文本，尽量不切断完整语义单元
 * <p>
 * 分块策略：
 * 1. 先按空行拆分为段落；
 * 2. 段落在目标块大小内则整体累积，超过则按句子边界（中英文标点）二次拆分；
 * 3. 单句仍超长时按最大长度硬切；
 * 4. 相邻块保留一定重叠（取上一块尾部若干字符），提升检索召回连续性。
 *
 * @author H
 */
@Component
public class SemanticChunker {

    /** 句子结束标点（中英文） */
    private static final String SENTENCE_DELIMITERS = "。！？!?…";

    /**
     * 将文本按语义边界切分为块。
     *
     * @param text      原始文本
     * @param chunkSize 每块目标最大字符数
     * @param overlap   相邻块重叠字符数
     * @return 文本块列表（已过滤空白块）
     */
    public List<String> chunk(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        // 归一化换行
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        String[] paragraphs = normalized.split("\n\\s*\n");

        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            String para = paragraph.strip();
            if (para.isEmpty()) {
                continue;
            }
            // 段落本身超过块大小：按句子拆分
            if (para.length() > chunkSize) {
                flush(chunks, current);
                current = new StringBuilder();
                for (String sentence : splitSentences(para, chunkSize)) {
                    if (current.length() + sentence.length() > chunkSize && current.length() > 0) {
                        chunks.add(current.toString().strip());
                        current = new StringBuilder(tail(current.toString(), overlap));
                    }
                    current.append(sentence);
                }
                continue;
            }
            // 累积段落，超出目标大小则先落块
            if (current.length() + para.length() + 1 > chunkSize && current.length() > 0) {
                chunks.add(current.toString().strip());
                current = new StringBuilder(tail(current.toString(), overlap));
            }
            if (current.length() > 0) {
                current.append("\n");
            }
            current.append(para);
        }
        flush(chunks, current);
        return chunks;
    }

    /**
     * 将段落按句子边界拆分；单句超长时按 chunkSize 硬切。
     */
    private List<String> splitSentences(String paragraph, int chunkSize) {
        List<String> sentences = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paragraph.length(); i++) {
            char c = paragraph.charAt(i);
            sb.append(c);
            if (SENTENCE_DELIMITERS.indexOf(c) >= 0) {
                sentences.add(sb.toString());
                sb = new StringBuilder();
            }
        }
        if (sb.length() > 0) {
            sentences.add(sb.toString());
        }
        // 对超长单句做硬切
        List<String> result = new ArrayList<>();
        for (String s : sentences) {
            if (s.length() <= chunkSize) {
                result.add(s);
            } else {
                for (int start = 0; start < s.length(); start += chunkSize) {
                    result.add(s.substring(start, Math.min(start + chunkSize, s.length())));
                }
            }
        }
        return result;
    }

    /**
     * 取文本尾部若干字符作为下一块的重叠前缀。
     */
    private String tail(String text, int overlap) {
        if (overlap <= 0 || text.length() <= overlap) {
            return overlap <= 0 ? "" : text;
        }
        return text.substring(text.length() - overlap);
    }

    /**
     * 落块：将当前累积内容加入结果（非空白）。
     */
    private void flush(List<String> chunks, StringBuilder current) {
        if (current != null && !current.toString().isBlank()) {
            chunks.add(current.toString().strip());
        }
    }
}
