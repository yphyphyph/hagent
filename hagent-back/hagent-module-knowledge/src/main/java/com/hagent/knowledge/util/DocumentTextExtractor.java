package com.hagent.knowledge.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 文档文本抽取工具，基于 Apache Tika 支持多格式解析
 * <p>
 * 支持 PDF、Word(doc/docx)、Excel(xls/xlsx)、PPT(ppt/pptx)、HTML、CSV、纯文本/Markdown 等格式。
 * 对纯文本类格式（txt/md）走轻量 UTF-8 读取以规避 Tika 编码探测开销；其余格式统一走 Tika。
 *
 * @author H
 */
@Slf4j
@Component
public class DocumentTextExtractor {

    /**
     * Tika 内容处理器写入上限（字符）：-1 表示不限制，避免大文件被默认 100KB 截断。
     */
    private static final int UNLIMITED_WRITE_LIMIT = -1;

    /**
     * 从输入流中抽取纯文本内容。
     *
     * @param inputStream 文件输入流（调用方负责关闭）
     * @param fileExt     文件扩展名（小写，用于快速路径判断）
     * @param fileName    文件名（用于 Tika metadata 辅助类型识别）
     * @return 抽取出的纯文本；解析失败或内容为空返回 null
     */
    public String extractText(InputStream inputStream, String fileExt, String fileName) {
        if (inputStream == null) {
            return null;
        }
        try {
            // 纯文本/Markdown：直接按 UTF-8 读取，保留原始换行结构（利于语义分块）
            if ("txt".equalsIgnoreCase(fileExt) || "md".equalsIgnoreCase(fileExt)
                    || "markdown".equalsIgnoreCase(fileExt)) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }

            // 其余格式统一交给 Tika AutoDetectParser 解析
            BodyContentHandler handler = new BodyContentHandler(UNLIMITED_WRITE_LIMIT);
            Metadata metadata = new Metadata();
            if (fileName != null) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
            }
            AutoDetectParser parser = new AutoDetectParser();
            parser.parse(inputStream, handler, metadata, new ParseContext());
            String text = handler.toString();
            if (text == null || text.isBlank()) {
                log.warn("Tika 解析结果为空: fileName={}, ext={}", fileName, fileExt);
                return null;
            }
            return text;
        } catch (TikaException | org.xml.sax.SAXException e) {
            log.error("Tika 解析文档失败: fileName={}, ext={}", fileName, fileExt, e);
            return null;
        } catch (Exception e) {
            log.error("抽取文档文本失败: fileName={}, ext={}", fileName, fileExt, e);
            return null;
        }
    }
}
