package com.hagent.knowledge.controller;

import com.hagent.common.PageResult;
import com.hagent.common.Result;
import com.hagent.common.annotation.RateLimit;
import com.hagent.knowledge.dto.KnowledgeQueryRequest;
import com.hagent.knowledge.service.KnowledgeService;
import com.hagent.knowledge.vo.KnowledgeFileVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理控制器，用于RAG文档处理
 *
 * @author H
 */
@Tag(name = "知识库管理", description = "知识库文档上传、向量化与 RAG 搜索等接口")
@RestController
@RequestMapping("/admin/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    /**
     * 分页查询知识库文件（带筛选）
     *
     * @param request 查询参数
     * @return 分页文件列表
     */
    @GetMapping
    @Operation(summary = "查询知识库文件列表", description = "按文件名、扩展名、向量状态等条件分页查询知识库文件")
    @ApiResponse(responseCode = "200", description = "查询成功")
    public Result<PageResult<KnowledgeFileVO>> listFiles(@Valid KnowledgeQueryRequest request) {
        return Result.success(knowledgeService.listFiles(request));
    }

    /**
     * 上传文档到知识库
     *
     * @param file       要上传的文件
     * @param visibility 可见性(0=私有 1=公有)，缺省公有
     * @return 上传的文件VO
     */
    @PostMapping("/upload")
    @Operation(summary = "上传文档", description = "上传文档到知识库并异步触发向量化处理，可指定公私可见性")
    @ApiResponse(responseCode = "200", description = "上传成功")
    @RateLimit(window = 60, limit = 5, message = "上传过于频繁，请 60 秒后重试")
    public Result<KnowledgeFileVO> upload(@Parameter(description = "待上传的文档文件", required = true) @RequestParam("file") MultipartFile file,
                                          @Parameter(description = "可见性：0=私有 1=公有，缺省公有") @RequestParam(value = "visibility", required = false, defaultValue = "1") Integer visibility) {
        return Result.success(knowledgeService.uploadDocument(file, visibility));
    }

    /**
     * 切换文件可见性（公有/私有）
     * 公私属性变更后自动重新向量化，使向量元数据同步
     *
     * @param id         文件ID
     * @param visibility 可见性(0=私有 1=公有)
     * @return 更新后的文件VO
     */
    @PutMapping("/files/{id}/visibility")
    @Operation(summary = "切换文件可见性", description = "公有/私有切换，变更后自动重新向量化以同步向量元数据")
    @ApiResponse(responseCode = "200", description = "操作成功")
    public Result<KnowledgeFileVO> updateVisibility(@Parameter(description = "文件ID", required = true) @PathVariable Long id,
                                                    @Parameter(description = "可见性：0=私有 1=公有", required = true) @RequestParam("visibility") Integer visibility) {
        return Result.success(knowledgeService.updateVisibility(id, visibility));
    }

    /**
     * 删除知识库文件及其OSS对象
     *
     * @param id 文件ID
     * @return void
     */
    @DeleteMapping("/files/{id}")
    @Operation(summary = "删除知识库文件", description = "删除知识库文件及其 OSS 对象与向量数据")
    @ApiResponse(responseCode = "200", description = "删除成功")
    public Result<Void> deleteFile(@Parameter(description = "文件ID", required = true) @PathVariable Long id) {
        knowledgeService.deleteFile(id);
        return Result.success();
    }

    /**
     * RAG向量搜索知识库
     *
     * @param request 搜索查询
     * @return 搜索结果
     */
    @PostMapping("/search")
    @Operation(summary = "RAG向量搜索", description = "基于向量相似度检索知识库内容")
    @ApiResponse(responseCode = "200", description = "搜索成功")
    public Result<List<Map<String, Object>>> search(@RequestBody @Valid KnowledgeQueryRequest request) {
        return Result.success(knowledgeService.search(request.getQuery()));
    }

    /**
     * 获取文件的向量处理状态
     *
     * @param id 文件ID
     * @return 包含状态的文件VO
     */
    @GetMapping("/status/{id}")
    @Operation(summary = "获取向量处理状态", description = "查询指定文件的向量化处理状态")
    @ApiResponse(responseCode = "200", description = "查询成功")
    public Result<KnowledgeFileVO> getVectorStatus(@Parameter(description = "文件ID", required = true) @PathVariable Long id) {
        return Result.success(knowledgeService.getVectorStatus(id));
    }

    /**
     * 触发文件向量化处理
     *
     * @param id 文件ID
     * @return 更新后的文件VO
     */
    @PostMapping("/files/{id}/vectorize")
    @Operation(summary = "触发文件向量化", description = "手动触发指定文件的向量化处理")
    @ApiResponse(responseCode = "200", description = "操作成功")
    public Result<KnowledgeFileVO> vectorizeFile(@Parameter(description = "文件ID", required = true) @PathVariable Long id) {
        return Result.success(knowledgeService.vectorizeFile(id));
    }

    /**
     * 替换知识库文档文件（保留原记录ID，自动触发重新向量化）
     *
     * @param id   文件ID
     * @param file 新文件
     * @return 更新后的文件VO
     */
    @PostMapping("/files/{id}/replace")
    @Operation(summary = "替换知识库文档", description = "替换文档文件并保留原记录ID，自动触发重新向量化")
    @ApiResponse(responseCode = "200", description = "替换成功")
    public Result<KnowledgeFileVO> replaceFile(@Parameter(description = "文件ID", required = true) @PathVariable Long id,
                                               @Parameter(description = "新文档文件", required = true) @RequestParam("file") MultipartFile file) {
        return Result.success(knowledgeService.replaceFile(id, file));
    }

    /**
     * 知识库统计信息
     *
     * @return 统计数据
     */
    @GetMapping("/statistics")
    @Operation(summary = "知识库统计", description = "获取知识库文件总数、各向量状态数量与存储占用等统计")
    @ApiResponse(responseCode = "200", description = "查询成功")
    public Result<Map<String, Object>> statistics() {
        return Result.success(knowledgeService.statistics());
    }

    /**
     * 知识库健康检查
     *
     * @return 健康信息
     */
    @GetMapping("/health")
    @Operation(summary = "知识库健康检查", description = "检查向量存储可用性、待处理/失败数量等")
    @ApiResponse(responseCode = "200", description = "查询成功")
    public Result<Map<String, Object>> health() {
        return Result.success(knowledgeService.health());
    }
}
