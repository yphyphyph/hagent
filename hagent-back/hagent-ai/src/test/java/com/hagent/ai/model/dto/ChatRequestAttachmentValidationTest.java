package com.hagent.ai.model.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聊天请求附件校验纯单测
 * <p>
 * 覆盖 AttachmentDTO 元素级校验（type 仅 image|document、mime 必须 image/*、
 * dataUrl 支持 base64 与 http(s) URL 二选一形态）
 * 与 ValidAttachments 列表级校验（数量上限、单张 5MB、总量 8MB、base64 解码失败 400 中文提示、
 * document 文档 name 必填且 dataUrl 仅允许 http(s) URL 或空串）。
 *
 * @author H
 */
class ChatRequestAttachmentValidationTest {

    /** Jakarta 校验器（纯单测，不依赖 Spring 容器） */
    private static ValidatorFactory validatorFactory;

    private static Validator validator;

    /** 单张图片解码后大小上限（5MB，与 AttachmentsValidator 保持同口径） */
    private static final int FIVE_MB = 5 * 1024 * 1024;

    /** 单张测试图片解码后大小（3MB：单张合法，3 张即超 8MB 总量） */
    private static final int THREE_MB = 3 * 1024 * 1024;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    /**
     * 合法请求（含 2 张合法图片，name 可选）应无任何违规
     */
    @Test
    void validRequestPasses() {
        ChatRequest request = buildRequest(List.of(
                image("data:image/png;base64," + tinyBase64(), "截图.png"),
                image("data:image/jpeg;base64," + tinyBase64(), null)));
        Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "合法附件请求不应有校验违规: " + messages(violations));
    }

    /**
     * 附件为 null（不传）应无任何违规（附件为可选项）
     */
    @Test
    void nullAttachmentsPasses() {
        ChatRequest request = buildRequest(null);
        Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "无附件请求不应有校验违规: " + messages(violations));
    }

    /**
     * 超过 4 张附件应被拒绝（数量上限）
     */
    @Test
    void moreThanFourImagesRejected() {
        ChatRequest request = buildRequest(List.of(
                image("data:image/png;base64," + tinyBase64(), null),
                image("data:image/png;base64," + tinyBase64(), null),
                image("data:image/png;base64," + tinyBase64(), null),
                image("data:image/png;base64," + tinyBase64(), null),
                image("data:image/png;base64," + tinyBase64(), null)));
        Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);
        assertTrue(messages(violations).contains("附件图片最多 4 张"), "5 张附件应触发数量上限违规");
    }

    /**
     * type 非 image|document 应被拒绝（仅支持 image 与 document 类型）
     */
    @Test
    void nonImageTypeRejected() {
        AttachmentDTO attachment = image("data:image/png;base64," + tinyBase64(), null);
        attachment.setType("file");
        ChatRequest request = buildRequest(List.of(attachment));
        Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);
        assertTrue(messages(violations).contains("附件类型仅支持 image 或 document"), "type=file 应触发类型违规");
    }

    /**
     * dataUrl 非 image/* mime 应被拒绝（mime 必须 image/*）
     */
    @Test
    void nonImageMimeRejected() {
        ChatRequest request = buildRequest(List.of(
                image("data:video/mp4;base64," + tinyBase64(), null)));
        Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);
        assertTrue(messages(violations).contains("附件图片必须为 data:image/*;base64 格式或 http(s) 图片链接"),
                "video mime 应触发格式违规");
    }

    /**
     * 合法 http(s) URL 形态附件应通过（OSS 上传后回传 URL 的契约，不做下载探测）
     */
    @Test
    void validHttpUrlPasses() {
        ChatRequest request = buildRequest(List.of(
                image("https://oss.example.com/blog/images/20260907/abc.png", null),
                image("http://oss.example.com/blog/images/20260907/def.jpg", "命名.jpg")));
        Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "合法 http(s) URL 附件不应有校验违规: " + messages(violations));
    }

    /**
     * ftp 等非 http(s) 协议 URL 应被拒绝（仅支持 http/https 图片链接）
     */
    @Test
    void ftpUrlRejected() {
        ChatRequest request = buildRequest(List.of(
                image("ftp://oss.example.com/blog/images/a.png", null)));
        Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);
        assertTrue(messages(violations).contains("附件图片必须为 data:image/*;base64 格式或 http(s) 图片链接"),
                "ftp URL 应触发格式违规");
    }

    /**
     * 非法串（既非 base64 data URL 也非 URL）应被拒绝
     */
    @Test
    void illegalStringRejected() {
        ChatRequest request = buildRequest(List.of(
                image("不是链接也不是base64", null)));
        Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);
        assertTrue(messages(violations).contains("附件图片必须为 data:image/*;base64 格式或 http(s) 图片链接"),
                "非法串应触发格式违规");
    }

    /**
     * 单张图片解码后超过 5MB 应被拒绝
     */
    @Test
    void singleImageOver5mbRejected() {
        String overSizeBase64 = Base64.getEncoder().encodeToString(new byte[FIVE_MB + 1]);
        ChatRequest request = buildRequest(List.of(
                image("data:image/png;base64," + overSizeBase64, null)));
        Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);
        assertTrue(messages(violations).contains("第 1 张附件图片超过 5MB 上限"),
                "单张超 5MB 应触发大小违规");
    }

    /**
     * 单张均合法但总量超过 8MB 应被拒绝（3 张各 3MB，合计 9MB）
     */
    @Test
    void totalImagesOver8mbRejected() {
        String threeMbBase64 = Base64.getEncoder().encodeToString(new byte[THREE_MB]);
        ChatRequest request = buildRequest(List.of(
                image("data:image/png;base64," + threeMbBase64, null),
                image("data:image/png;base64," + threeMbBase64, null),
                image("data:image/png;base64," + threeMbBase64, null)));
        Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);
        assertTrue(messages(violations).contains("附件图片总量不能超过 8MB"),
                "总量超 8MB 应触发总量违规");
        // 单张 3MB 合法，不应出现单张大小违规
        assertFalse(messages(violations).contains("超过 5MB 上限"), "单张 3MB 不应触发单张大小违规");
    }

    /**
     * base64 非法字符应被拒绝（解码失败给 400 中文提示，不 500）
     */
    @Test
    void illegalBase64Rejected() {
        ChatRequest request = buildRequest(List.of(
                image("data:image/png;base64,@@@非法字符@@@", null)));
        Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);
        assertTrue(messages(violations).contains("第 1 张附件图片数据解码失败，请提供合法的 base64 内容"),
                "非法 base64 应触发解码失败违规");
    }

    /**
     * dataUrl 为空应被拒绝（image 类型列表级非空校验，消息与原元素级口径一致）
     */
    @Test
    void blankDataUrlRejected() {
        ChatRequest request = buildRequest(List.of(image("", null)));
        Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);
        assertTrue(messages(violations).contains("附件图片数据不能为空"), "空 dataUrl 应触发非空违规");
    }

    /**
     * 合法 document 附件（name 必填 + http(s) 文档 URL）应通过（知识库上传回传 fileUrl 的契约）
     */
    @Test
    void validDocumentWithUrlPasses() {
        ChatRequest request = buildRequest(List.of(
                document("https://oss.example.com/knowledge/20260907/abc.md", "设计文档.md")));
        Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "合法 document 附件不应有校验违规: " + messages(violations));
    }

    /**
     * document 无 url（dataUrl 为 null 或空串）应通过（文档仅留痕时允许无外链）
     */
    @Test
    void validDocumentWithoutUrlPasses() {
        AttachmentDTO noUrl = document(null, "本地文档.md");
        AttachmentDTO blankUrl = document("", "空链文档.md");
        ChatRequest request = buildRequest(List.of(noUrl, blankUrl));
        Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "无 url 的 document 附件不应有校验违规: " + messages(violations));
    }

    /**
     * document 带 data: URL（base64 内联）应被拒绝（文档走知识库上传，不支持内联数据）
     */
    @Test
    void documentWithDataUrlRejected() {
        ChatRequest request = buildRequest(List.of(
                document("data:text/markdown;base64," + tinyBase64(), "内联文档.md")));
        Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);
        assertTrue(messages(violations).contains("仅支持 http(s) 链接或留空"), "document 带 data: URL 应触发形态违规");
    }

    /**
     * document 缺少 name 应被拒绝（落库 [文档：name](url) 标记依赖该字段）
     */
    @Test
    void documentWithoutNameRejected() {
        ChatRequest request = buildRequest(List.of(
                document("https://oss.example.com/knowledge/20260907/abc.md", null)));
        Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);
        assertTrue(messages(violations).contains("文档附件名称不能为空"), "document 缺 name 应触发必填违规");
    }

    /**
     * 构建聊天请求
     *
     * @param attachments 附件列表（可为 null）
     * @return 聊天请求
     * @author H
     */
    private ChatRequest buildRequest(List<AttachmentDTO> attachments) {
        ChatRequest request = new ChatRequest();
        request.setMessage("这张图片里是什么");
        request.setAttachments(attachments);
        return request;
    }

    /**
     * 构建图片附件
     *
     * @param dataUrl 图片数据 URL
     * @param name    附件名称（可选）
     * @return 图片附件
     * @author H
     */
    private AttachmentDTO image(String dataUrl, String name) {
        AttachmentDTO attachment = new AttachmentDTO();
        attachment.setType("image");
        attachment.setDataUrl(dataUrl);
        attachment.setName(name);
        return attachment;
    }

    /**
     * 构建文档附件
     *
     * @param dataUrl 文档 URL（知识库上传回传 fileUrl，可为 null/空串表示仅留痕）
     * @param name    附件名称（必填）
     * @return 文档附件
     * @author H
     */
    private AttachmentDTO document(String dataUrl, String name) {
        AttachmentDTO attachment = new AttachmentDTO();
        attachment.setType("document");
        attachment.setDataUrl(dataUrl);
        attachment.setName(name);
        return attachment;
    }

    /**
     * 生成 1 字节数据的合法 base64（最小合法图片数据占位）
     *
     * @return base64 字符串
     * @author H
     */
    private String tinyBase64() {
        return Base64.getEncoder().encodeToString(new byte[1]);
    }

    /**
     * 汇总违规消息便于断言与失败诊断
     *
     * @param violations 校验违规集合
     * @return 违规消息集合
     * @author H
     */
    private String messages(Set<ConstraintViolation<ChatRequest>> violations) {
        return violations.stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining(" | "));
    }
}
