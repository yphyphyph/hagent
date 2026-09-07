package com.hagent.ai.core;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentToolLoopService 流式工具调用块剥离分片单测
 * 覆盖起始/结束标记被 tokenizer 切成两半的跨分片场景，以及文本解析单测
 *
 * @author H
 */
class AgentToolLoopStripTest {

    /**
     * 起始标记被切成两半：上片以 &lt;tool_ 结尾，下片以 call&gt; 开头
     * 期望碎片不泄漏为正文，块整体剥离，前后正文保留
     */
    @Test
    void stripStartMarkerSplitAcrossChunks() {
        boolean[] inBlock = {false};
        StringBuilder buf = new StringBuilder();
        StringBuilder seen = new StringBuilder();
        seen.append(AgentToolLoopService.stripToolCallBlock("你好<tool_", inBlock, buf));
        seen.append(AgentToolLoopService.stripToolCallBlock(
                "call><function=searchKnowledge>xxx</function></tool_call>，再见", inBlock, buf));
        assertEquals("你好，再见", seen.toString());
        assertFalse(inBlock[0]);
    }

    /**
     * 起始标记在尖括号处被切开：上片以 &lt; 结尾，下片以 tool_call&gt; 开头
     * 期望暂存疑似区，联合后整体剥离
     */
    @Test
    void stripStartMarkerSplitAtBracket() {
        boolean[] inBlock = {false};
        StringBuilder buf = new StringBuilder();
        StringBuilder seen = new StringBuilder();
        seen.append(AgentToolLoopService.stripToolCallBlock("正文<", inBlock, buf));
        assertEquals("正文", seen.toString());
        seen.append(AgentToolLoopService.stripToolCallBlock(
                "tool_call><function=searchKnowledge>x</function></tool_call>尾巴", inBlock, buf));
        assertEquals("正文尾巴", seen.toString());
        assertFalse(inBlock[0]);
    }

    /**
     * 结束标记被切成两半：上片以 &lt;/tool 结尾，下片以 _call&gt; 开头
     * 期望块内用 buf+chunk 联合搜到结束标记，后续正文不被吞掉
     */
    @Test
    void stripEndMarkerSplitAcrossChunks() {
        boolean[] inBlock = {false};
        StringBuilder buf = new StringBuilder();
        StringBuilder seen = new StringBuilder();
        seen.append(AgentToolLoopService.stripToolCallBlock(
                "正文<tool_call><function=searchKnowledge>查北京天气</function>", inBlock, buf));
        assertEquals("正文", seen.toString());
        assertTrue(inBlock[0]);
        seen.append(AgentToolLoopService.stripToolCallBlock("</tool", inBlock, buf));
        assertEquals("正文", seen.toString());
        assertTrue(inBlock[0]);
        seen.append(AgentToolLoopService.stripToolCallBlock("_call>后续正文", inBlock, buf));
        assertEquals("正文后续正文", seen.toString());
        assertFalse(inBlock[0]);
    }

    /**
     * 普通小于号不误伤：上片尾部 &lt; 暂存后，下片证明不是标记开头，原样输出
     */
    @Test
    void stripPlainLessThanNotSwallowed() {
        boolean[] inBlock = {false};
        StringBuilder buf = new StringBuilder();
        StringBuilder seen = new StringBuilder();
        seen.append(AgentToolLoopService.stripToolCallBlock("a <", inBlock, buf));
        seen.append(AgentToolLoopService.stripToolCallBlock(" b", inBlock, buf));
        assertEquals("a < b", seen.toString());
        assertFalse(inBlock[0]);
    }

    /**
     * 单分片完整块：一次到达即整体剥离，前后正文保留
     */
    @Test
    void stripWholeBlockSingleChunk() {
        boolean[] inBlock = {false};
        StringBuilder buf = new StringBuilder();
        String result = AgentToolLoopService.stripToolCallBlock(
                "a<tool_call><function=searchKnowledge>x</function></tool_call>b", inBlock, buf);
        assertEquals("ab", result);
        assertFalse(inBlock[0]);
    }

    /**
     * 文本形式工具调用解析：完整块解析出函数名与参数，数值类参数转数字
     */
    @Test
    void parseTextToolCallWholeBlock() throws Exception {
        AgentToolLoopService service =
                new AgentToolLoopService(new ObjectMapper(), null, null, null);
        Method method = AgentToolLoopService.class.getDeclaredMethod("parseTextToolCall", String.class);
        method.setAccessible(true);
        String text = "<tool_call>\n<function=searchKnowledge>\n"
                + "<parameter=query>北京天气</parameter>\n"
                + "<parameter=topK>5</parameter>\n</tool_call>";
        AssistantMessage.ToolCall call =
                (AssistantMessage.ToolCall) method.invoke(service, text);
        assertNotNull(call);
        assertEquals("searchKnowledge", call.name());
        assertTrue(call.arguments().contains("北京天气"));
        assertTrue(call.arguments().contains("\"topK\":5"));
    }
}
