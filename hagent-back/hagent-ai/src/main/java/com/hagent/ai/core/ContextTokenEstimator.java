package com.hagent.ai.core;

import java.util.List;

/**
 * 上下文 Token 启发式估算工具
 * <p>
 * 仅做前端展示用初筛，不追求精确计数；不对国产模型使用 tiktoken（分词口径不准）。
 * 口径：CJK 区段按 1.5 字符/token，其余按 4 字符/token，分段累加后加 15% 脚手架裕量。
 *
 * @author H
 */
public final class ContextTokenEstimator {

    /** 脚手架裕量系数（system/role 等框架开销） */
    private static final double OVERHEAD_FACTOR = 1.15;

    private ContextTokenEstimator() {
    }

    /**
     * 估算单段文本的 token 数
     *
     * @param text 文本，为空时返回 0
     * @return 估算 token 数（非空文本至少为 1）
     * @author H
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjkChars = 0;
        int otherChars = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjkChars++;
            } else {
                otherChars++;
            }
        }
        // CJK 区段 1.5 字符/token，其余 4 字符/token，分段累加后加 15% 脚手架裕量
        double raw = cjkChars / 1.5 + otherChars / 4.0;
        return Math.max(1, (int) Math.round(raw * OVERHEAD_FACTOR));
    }

    /**
     * 对多段文本分别估算后求和
     *
     * @param parts 文本分段，为空时返回 0（其中 null 元素跳过）
     * @return 各段估算之和
     * @author H
     */
    public static int estimateMessages(List<String> parts) {
        if (parts == null || parts.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (String part : parts) {
            if (part != null) {
                total += estimate(part);
            }
        }
        return total;
    }

    /**
     * 判断字符是否落在 CJK 区段（统一表意文字/扩展 A/兼容区）
     *
     * @param c 待判断字符
     * @return 是否为 CJK 字符
     * @author H
     */
    private static boolean isCjk(char c) {
        // 与正则 [CJK 统一表意文字/扩展 A/兼容区] 同口径，按码点区间判断避免源码编码依赖
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF);
    }
}
