package com.hagent.knowledge.controller;

import com.hagent.common.Result;
import com.hagent.common.annotation.RateLimit;
import com.hagent.common.exception.BusinessException;
import com.hagent.knowledge.util.AliOssUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 通用文件上传控制器
 * 主要服务于博客文章编辑器的图片上传（粘贴/拖拽/选择），复用阿里云 OSS 能力
 *
 * @author H
 */
@Slf4j
@Tag(name = "文件上传", description = "通用文件上传接口，服务于文章编辑器的图片上传")
@RestController
@RequestMapping("/admin/upload")
public class UploadController {

    /** OSS 工具类，未配置 aliyun.oss.endpoint 时为 null */
    private final AliOssUtil aliOssUtil;

    /** 图片大小上限：5MB */
    private static final long MAX_IMAGE_SIZE = 5L * 1024 * 1024;

    /** 日期目录格式 */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    public UploadController(@Autowired(required = false) AliOssUtil aliOssUtil) {
        this.aliOssUtil = aliOssUtil;
    }

    /**
     * 上传图片并返回可访问的公开 URL
     *
     * @param file 图片文件
     * @return 包含 url 字段的结果
     */
    @PostMapping("/image")
    @Operation(summary = "上传图片（文章编辑器）", description = "上传图片到阿里云 OSS 并返回可公开访问的 URL")
    @ApiResponse(responseCode = "200", description = "上传成功")
    @RateLimit(window = 60, limit = 30, message = "上传过于频繁，请稍后重试")
    public Result<Map<String, String>> uploadImage(@Parameter(description = "待上传的图片文件", required = true) @RequestParam("file") MultipartFile file) {
        if (aliOssUtil == null) {
            throw new BusinessException("对象存储未配置，无法上传图片");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件为空");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessException("仅支持上传图片文件");
        }
        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new BusinessException("图片大小不能超过 5MB");
        }

        // 生成 OSS 对象键：blog/images/yyyyMMdd/<uuid><ext>
        String original = file.getOriginalFilename();
        String ext = "";
        if (original != null && original.contains(".")) {
            ext = original.substring(original.lastIndexOf('.'));
        }
        String objectName = "blog/images/" + LocalDate.now().format(DATE_FMT) + "/"
                + UUID.randomUUID().toString().replace("-", "") + ext;

        try {
            // 文章图片需被浏览器直接公开访问，上传时将该对象设为公共读
            String url = aliOssUtil.uploadFile(file.getInputStream(), objectName, true);
            Map<String, String> data = new HashMap<>();
            data.put("url", url);
            log.info("文章图片上传成功: {}", objectName);
            return Result.success(data);
        } catch (IOException e) {
            log.error("读取上传图片失败: {}", objectName, e);
            throw new BusinessException("图片上传失败");
        }
    }
}
