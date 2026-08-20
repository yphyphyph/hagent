package com.hagent.ai.service;

/**
 * 图片数据处理服务接口
 * <p>
 * 将本地路径 / http(s) URL / base64 data URI 统一转换为 data:{mime};base64,{b64} 格式，
 * 供多模态视觉模型直接消费。
 *
 * @author H
 */
public interface ImageDataService {

    /**
     * 将图片来源统一转换为 data URI
     *
     * @param image 图片来源：本地绝对路径 / http(s) 链接 / data:image/...;base64,...
     * @return data:{mime};base64,{b64}
     */
    String toDataUri(String image);
}
