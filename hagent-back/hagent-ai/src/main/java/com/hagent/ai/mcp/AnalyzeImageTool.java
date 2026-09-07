package com.hagent.ai.mcp;

import com.hagent.ai.model.ImageMcpConfig;
import com.hagent.ai.service.ImageDataService;
import com.hagent.ai.service.VisionClientService;
import com.hagent.ai.service.VisionConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 图片识别 MCP 工具
 * <p>
 * 使用多模态视觉模型识别图片并回答问题。图片支持本地文件路径、http(s) URL 或 base64 data URI。
 * 所有异常在方法内部捕获并以中文字符串返回，不抛给 MCP 框架，便于文本模型原样转述给用户。
 *
 * @author H
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyzeImageTool {

    /** 工具名称 */
    private static final String TOOL_NAME = "analyze_image";

    /** 未配置时的提示文案 */
    private static final String NOT_CONFIGURED_MSG = "未配置视觉模型 API，请在系统配置中填写 ai.vision.base-url、ai.vision.api-key、ai.vision.model";

    /** 有效 base64 段最短长度（低于此视为模型复述的占位符；真实图片 data URI 至少数百字符） */
    private static final int MIN_VALID_B64_LENGTH = 100;

    /** 占位符 base64 的短路提示文案（引导模型跳过无效工具轮次） */
    private static final String PLACEHOLDER_MSG = "图片数据不完整（收到占位符），请直接基于你已看到的用户图片内容回答，无需调用本工具";

    private final VisionConfigService visionConfigService;
    private final ImageDataService imageDataService;
    private final VisionClientService visionClientService;

    /**
     * 使用多模态视觉模型识别图片并回答问题
     *
     * @param image    图片来源：本地绝对路径 / http(s) 链接 / data:image/...;base64,...
     * @param question 关于图片的问题或指令
     * @return 多模态模型的纯文本回答；错误以中文字符串返回
     */
    @Tool(name = "analyze_image", description = "分析网络图片或本地图片文件。注意：用户在聊天中直接上传的图片你已经可以直接看到，无需调用本工具；仅当用户消息中出现明确的图片 URL 或本地文件路径时才使用；严禁编造或猜测图片 URL")
    public String analyzeImage(
            @ToolParam(description = "图片来源：本地绝对路径 / http(s) 链接 / data:image/...;base64,...") String image,
            @ToolParam(description = "关于图片的问题或指令") String question) {
        long start = System.currentTimeMillis();
        long duration;
        // 占位符 base64 短路：模型无法复现数 MB base64，会以 "data:image/jpeg;base64,..." 字面占位符
        // 调用本工具，直接返回提示让模型改用原生视觉已见内容，省一轮无效工具调用 @author H
        if (isPlaceholderBase64(image)) {
            log.warn("[analyze_image] 收到占位符 base64，短路返回提示: image={}", truncateForLog(image));
            return PLACEHOLDER_MSG;
        }
        try {
            ImageMcpConfig config = visionConfigService.getCurrentConfig();
            if (!visionConfigService.isConfigured()) {
                duration = System.currentTimeMillis() - start;
                log.warn("[analyze_image] 未配置，耗时{}ms，image={}，question={}", duration,
                        truncateForLog(image), singleLineForLog(question));
                return NOT_CONFIGURED_MSG;
            }
            String dataUri = imageDataService.toDataUri(image);
            String result = visionClientService.chatWithImage(dataUri, question, config);
            duration = System.currentTimeMillis() - start;
            log.info("[analyze_image] 成功，耗时{}ms，image={}，question={}，result={}", duration,
                    truncateForLog(image), singleLineForLog(question), singleLineForLog(result));
            return result;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "图片识别失败";
            duration = System.currentTimeMillis() - start;
            log.warn("[analyze_image] 失败，耗时{}ms，image={}，question={}，error={}", duration,
                    truncateForLog(image), singleLineForLog(question), msg);
            return msg;
        }
    }

    /**
     * 判定是否为占位符 base64：data: 开头、含 ;base64, 且 base64 段过短或以 ... 结尾
     *
     * @param image 图片输入参数
     * @return 是否为占位符
     * @author H
     */
    private boolean isPlaceholderBase64(String image) {
        if (image == null || !image.startsWith("data:") || !image.contains(";base64,")) {
            return false;
        }
        String b64 = image.substring(image.indexOf(";base64,") + ";base64,".length());
        return b64.length() < MIN_VALID_B64_LENGTH || b64.endsWith("...");
    }

    /**
     * 截断 image 参数用于日志：data URI 只记前缀与长度，其他超 200 字符截断
     *
     * @param image 图片输入参数
     * @return 截断后的字符串
     */
    private String truncateForLog(String image) {
        if (image == null || image.isEmpty()) {
            return "";
        }
        if (image.startsWith("data:") && image.contains(";base64,")) {
            int b64Len = image.length() - image.indexOf(";base64,") - ";base64,".length();
            // Math.min 防短串越界：模型可能传字面占位符（如 "data:image/jpeg;base64,..."，26 字符），
            // 无条件 substring(0, 40) 会抛 StringIndexOutOfBoundsException 且发生在 catch 日志行时吞掉真实异常 @author H
            return image.substring(0, Math.min(40, image.length())) + "...(base64 length " + b64Len + ")";
        }
        return image.length() > 200 ? image.substring(0, 200) + "..." : image;
    }

    /**
     * 多行归一用于日志
     *
     * @param text 原始文本
     * @return 单行文本
     */
    private String singleLineForLog(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("\r", " ").replace("\n", " ");
    }
}
