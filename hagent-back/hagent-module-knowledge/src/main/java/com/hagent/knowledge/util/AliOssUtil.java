package com.hagent.knowledge.util;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.CannedAccessControlList;
import com.hagent.common.exception.SystemException;
import com.hagent.knowledge.config.OssConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.Date;

/**
 * 阿里云OSS工具类，用于文件存储操作
 * 提供上传、删除、下载和预签名URL生成功能
 *
 * @author H
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "aliyun.oss", name = "endpoint")
public class AliOssUtil {

    private final OssConfig ossConfig;
    private OSS ossClient;

    public AliOssUtil(OssConfig ossConfig) {
        this.ossConfig = ossConfig;
    }

    @PostConstruct
    public void init() {
        this.ossClient = new OSSClientBuilder().build(
                ossConfig.getEndpoint(),
                ossConfig.getAccessKeyId(),
                ossConfig.getAccessKeySecret()
        );
        log.info("OSS客户端初始化成功");
    }

    @PreDestroy
    public void destroy() {
        if (ossClient != null) {
            ossClient.shutdown();
            log.info("OSS客户端已关闭");
        }
    }

    /**
     * 上传文件到OSS并返回公开URL（对象继承 Bucket 默认 ACL，通常为私有）
     *
     * @param inputStream 文件内容
     * @param objectName  OSS对象键
     * @return 文件URL
     */
    public String uploadFile(InputStream inputStream, String objectName) {
        return uploadFile(inputStream, objectName, false);
    }

    /**
     * 上传文件到OSS并返回URL，可指定对象为公共读
     * <p>
     * 当 {@code publicRead=true} 时，仅将当前对象的 ACL 设为 public-read，
     * 使其可被浏览器直接访问（如文章内嵌图片），而不改变 Bucket 全局 ACL，
     * 因此不会影响同 Bucket 下的私有文件（如知识库文档）。
     *
     * @param inputStream 文件内容
     * @param objectName  OSS对象键
     * @param publicRead  是否将该对象设为公共读
     * @return 文件URL
     */
    public String uploadFile(InputStream inputStream, String objectName, boolean publicRead) {
        try {
            // 先正常上传对象（不带 ACL 头，保证上传本身始终成功）
            ossClient.putObject(ossConfig.getBucketName(), objectName, inputStream);
            if (publicRead) {
                // 尽力将该对象设为公共读；若 Bucket 开启了「阻止公共访问」，此调用会被拒绝，
                // 此时仅告警、不影响上传成功。要让图片可公开访问，需在 OSS 控制台
                // 关闭「阻止公共访问」并将 Bucket ACL 设为公共读。
                try {
                    ossClient.setObjectAcl(ossConfig.getBucketName(), objectName, CannedAccessControlList.PublicRead);
                } catch (Exception aclEx) {
                    log.warn("设置对象公共读ACL失败（Bucket 可能开启了阻止公共访问）: {}, {}", objectName, aclEx.getMessage());
                }
            }
            String fileUrl = "https://" + ossConfig.getBucketName() + "."
                    + ossConfig.getEndpoint().replace("https://", "") + "/" + objectName;
            log.info("OSS上传成功: {}", objectName);
            return fileUrl;
        } catch (Exception e) {
            log.error("OSS上传失败: {}", objectName, e);
            throw new SystemException("OSS上传失败", e);
        }
    }

    /**
     * 从OSS删除文件
     *
     * @param objectName OSS对象键
     */
    public void deleteFile(String objectName) {
        try {
            ossClient.deleteObject(ossConfig.getBucketName(), objectName);
            log.info("OSS删除成功: {}", objectName);
        } catch (Exception e) {
            log.error("OSS删除失败: {}", objectName, e);
        }
    }

    /**
     * 从OSS下载文件
     *
     * @param objectName OSS对象键
     * @return 文件输入流
     */
    public InputStream downloadFile(String objectName) {
        try (InputStream ossStream = ossClient.getObject(ossConfig.getBucketName(), objectName).getObjectContent()) {
            byte[] data = ossStream.readAllBytes();
            log.info("OSS下载成功: {}, size={}", objectName, data.length);
            return new ByteArrayInputStream(data);
        } catch (Exception e) {
            log.error("OSS下载失败: {}", objectName, e);
            throw new SystemException("OSS下载失败", e);
        }
    }

    /**
     * 生成临时访问的预签名URL
     *
     * @param objectName  OSS对象键
     * @param expiration  过期时间
     * @return 预签名URL
     */
    public String generatePresignedUrl(String objectName, Date expiration) {
        try {
            URL url = ossClient.generatePresignedUrl(ossConfig.getBucketName(), objectName, expiration);
            return url.toString();
        } catch (Exception e) {
            log.error("OSS预签名URL生成失败: {}", objectName, e);
            throw new SystemException("OSS预签名URL生成失败", e);
        }
    }
}
