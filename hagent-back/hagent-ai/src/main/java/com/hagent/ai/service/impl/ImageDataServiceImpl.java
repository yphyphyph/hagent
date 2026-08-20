package com.hagent.ai.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hagent.common.exception.BusinessException;
import com.hagent.ai.service.ImageDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;

/**
 * 图片数据处理服务实现
 * <p>
 * 将本地路径 / http(s) URL / base64 data URI 统一转换为 data:{mime};base64,{b64}。
 * 限制：单图 ≤ 50MB；支持 JPEG/PNG/GIF/WebP/BMP。
 *
 * @author H
 */
@Slf4j
@Service
public class ImageDataServiceImpl implements ImageDataService {

    /** 图片最大字节数（50MB） */
    private static final long MAX_IMAGE_BYTES = 50L * 1024 * 1024;

    /** 允许的图片扩展名 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp");

    /** 允许的图片 MIME 类型 */
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp");

    /** URL 下载超时（秒） */
    private static final long DOWNLOAD_TIMEOUT = 30L;

    /** HTTP 客户端（重定向跟随） */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(DOWNLOAD_TIMEOUT))
            .build();

    /**
     * 将图片来源统一转换为 data URI
     *
     * @param image 图片来源
     * @return data:{mime};base64,{b64}
     */
    @Override
    public String toDataUri(String image) {
        if (StrUtil.isBlank(image)) {
            throw new BusinessException("图片来源为空");
        }
        String trimmed = image.trim();
        if (StrUtil.startWith(trimmed, "data:")) {
            return parseDataUri(trimmed);
        }
        if (StrUtil.startWith(trimmed, "http://") || StrUtil.startWith(trimmed, "https://")) {
            return downloadToDataUri(trimmed);
        }
        return fileToDataUri(trimmed);
    }

    /**
     * 解析 data URI
     *
     * @param image data URI 字符串
     * @return 校验后的 data URI
     */
    private String parseDataUri(String image) {
        int idx = image.indexOf(";base64,");
        if (idx < 0) {
            throw new BusinessException("不支持的图片来源格式（data URI 必须以 data:image/...;base64, 开头）");
        }
        String mime = image.substring(5, image.indexOf(";")).toLowerCase();
        if (!ALLOWED_MIME_TYPES.contains(mime)) {
            throw new BusinessException("不支持的图片格式：" + mime + "（仅支持 JPEG/PNG/GIF/WebP/BMP）");
        }
        String b64 = image.substring(idx + ";base64,".length());
        if ((long) b64.length() * 3 / 4 > MAX_IMAGE_BYTES) {
            throw new BusinessException("图片超过 50MB 上限");
        }
        return image;
    }

    /**
     * 本地文件转 data URI
     *
     * @param pathStr 文件路径
     * @return data:{mime};base64,{b64}
     */
    private String fileToDataUri(String pathStr) {
        Path path = Path.of(pathStr);
        if (!Files.exists(path)) {
            throw new BusinessException("图片文件不存在：" + pathStr);
        }
        if (!Files.isRegularFile(path)) {
            throw new BusinessException("路径不是文件：" + pathStr);
        }
        String fileName = path.getFileName().toString().toLowerCase();
        int dotIdx = fileName.lastIndexOf('.');
        String ext = dotIdx >= 0 ? fileName.substring(dotIdx) : "";
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BusinessException("不支持的图片格式：" + pathStr + "（仅支持 JPEG/PNG/GIF/WebP/BMP）");
        }
        try {
            long size = Files.size(path);
            if (size > MAX_IMAGE_BYTES) {
                throw new BusinessException("图片文件超过 50MB 上限：" + pathStr);
            }
            byte[] data = Files.readAllBytes(path);
            String mime = probeMime(fileName);
            return toDataUriStr(data, mime);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("读取图片文件失败：" + e.getMessage());
        }
    }

    /**
     * 下载 URL 图片转 data URI
     *
     * @param url 图片 URL
     * @return data:{mime};base64,{b64}
     */
    private String downloadToDataUri(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(DOWNLOAD_TIMEOUT))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status >= 400) {
                throw new BusinessException("图片下载失败：" + url + "（HTTP " + status + "）");
            }
            byte[] body = response.body();
            if (body == null || body.length == 0) {
                throw new BusinessException("图片下载失败：" + url + "（响应为空）");
            }
            if (body.length > MAX_IMAGE_BYTES) {
                throw new BusinessException("图片超过 50MB 上限：" + url);
            }
            // Content-Type 优先，缺失回退 URL 扩展名
            String contentType = response.headers().firstValue("content-type").orElse("");
            String mime = StrUtil.isBlank(contentType) ? "" : contentType.split(";")[0].trim().toLowerCase();
            if (StrUtil.isNotBlank(mime)) {
                if (!ALLOWED_MIME_TYPES.contains(mime)) {
                    throw new BusinessException("目标不是图片：" + url + "（Content-Type: " + mime + "）");
                }
            } else {
                mime = probeMime(url.toLowerCase());
                if (!ALLOWED_MIME_TYPES.contains(mime)) {
                    throw new BusinessException("目标不是图片：" + url + "（无法识别 Content-Type）");
                }
            }
            return toDataUriStr(body, mime);
        } catch (BusinessException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new BusinessException("图片下载失败：" + url + "（请求超时）");
        } catch (Exception e) {
            throw new BusinessException("图片下载失败：" + url + "（" + e.getClass().getSimpleName() + "）");
        }
    }

    /**
     * 根据文件名推断 MIME 类型
     *
     * @param fileName 文件名
     * @return MIME 类型
     */
    private String probeMime(String fileName) {
        String mime = java.net.URLConnection.guessContentTypeFromName(fileName);
        return StrUtil.isNotBlank(mime) ? mime.toLowerCase() : "image/jpeg";
    }

    /**
     * 字节数组转 data URI
     *
     * @param data 字节数据
     * @param mime MIME 类型
     * @return data:{mime};base64,{b64}
     */
    private String toDataUriStr(byte[] data, String mime) {
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(data);
    }
}
