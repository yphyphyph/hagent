package com.hagent.ai.memory;

import com.hagent.ai.config.AiConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * AI记忆服务
 * 管理AI对话的持久化记忆文件
 *
 * @author H
 */
@Slf4j
@Service
public class MemoryService {

    /** 允许的记忆分类白名单，防止路径穿越到任意目录 */
    private static final Set<String> ALLOWED_CATEGORIES = Set.of("user", "feedback", "project", "reference");

    /** 合法文件名正则：仅允许字母数字、下划线、连字符、点，禁止路径分隔符与 .. */
    private static final Pattern SAFE_FILENAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_.-]+$");

    private final AiConfig aiConfig;

    public MemoryService(AiConfig aiConfig) {
        this.aiConfig = aiConfig;
    }

    /**
     * 校验分类与文件名合法性，防止路径穿越攻击（如 ../../etc/passwd）
     *
     * @param category 记忆分类
     * @param fileName 文件名
     * @throws IllegalArgumentException 当分类不在白名单或文件名非法时抛出
     */
    private void validatePathParams(String category, String fileName) {
        if (category == null || !ALLOWED_CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("非法的记忆分类: " + category);
        }
        if (fileName == null || fileName.isBlank()
                || fileName.contains("..")
                || !SAFE_FILENAME_PATTERN.matcher(fileName).matches()) {
            throw new IllegalArgumentException("非法的文件名: " + fileName);
        }
    }

    /**
     * 获取记忆根目录
     *
     * @return 根目录路径
     */
    public String getMemoriesRootDirectory() {
        return aiConfig.getMemory().getMemoriesRootDirectory();
    }

    /**
     * 列出所有记忆分类及其文件
     *
     * @return 分类名称到文件列表的映射（键值对）
     */
    public Map<String, List<String>> listAllMemories() {
        Path rootPath = Paths.get(getMemoriesRootDirectory());
        if (!Files.exists(rootPath)) {
            log.warn("记忆根目录不存在: {}", rootPath);
            return Map.of();
        }

        Map<String, List<String>> result = new HashMap<>();
        try (Stream<Path> dirs = Files.list(rootPath)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                String categoryName = dir.getFileName().toString();
                try (Stream<Path> files = Files.list(dir)) {
                    List<String> fileNames = files
                            .filter(Files::isRegularFile)
                            .map(p -> p.getFileName().toString())
                            .collect(Collectors.toList());
                    result.put(categoryName, fileNames);
                } catch (IOException e) {
                    log.error("列出目录文件失败: {}", dir, e);
                }
            });
        } catch (IOException e) {
            log.error("列出记忆目录失败: {}", rootPath, e);
        }
        return result;
    }

    /**
     * 按分类和文件名读取记忆文件
     *
     * @param category 分类（user/feedback/project/reference）
     * @param fileName 文件名
     * @return 文件内容
     */
    public String readMemory(String category, String fileName) {
        validatePathParams(category, fileName);
        Path filePath = Paths.get(getMemoriesRootDirectory(), category, fileName);
        if (!Files.exists(filePath)) {
            log.warn("记忆文件不存在: {}", filePath);
            return "";
        }
        try {
            return Files.readString(filePath);
        } catch (IOException e) {
            log.error("读取记忆文件失败: {}", filePath, e);
            return "";
        }
    }

    /**
     * 创建或更新记忆文件
     *
     * @param category 分类（user/feedback/project/reference）
     * @param fileName 文件名
     * @param content  文件内容
     * @return 成功返回true，否则返回false
     */
    public boolean createMemory(String category, String fileName, String content) {
        validatePathParams(category, fileName);
        Path dirPath = Paths.get(getMemoriesRootDirectory(), category);
        Path filePath = dirPath.resolve(fileName);

        try {
            // 目录不存在时创建
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
            }
            Files.writeString(filePath, content);
            log.info("记忆文件已创建/更新: {}", filePath);
            return true;
        } catch (IOException e) {
            log.error("写入记忆文件失败: {}", filePath, e);
            return false;
        }
    }

    /**
     * 删除记忆文件
     *
     * @param category 分类（user/feedback/project/reference）
     * @param fileName 文件名
     * @return 成功返回true，否则返回false
     */
    public boolean deleteMemory(String category, String fileName) {
        validatePathParams(category, fileName);
        Path filePath = Paths.get(getMemoriesRootDirectory(), category, fileName);
        if (!Files.exists(filePath)) {
            log.warn("记忆文件不存在: {}", filePath);
            return false;
        }

        try {
            Files.delete(filePath);
            log.info("记忆文件已删除: {}", filePath);
            return true;
        } catch (IOException e) {
            log.error("删除记忆文件失败: {}", filePath, e);
            return false;
        }
    }

    /**
     * 获取MEMORY.md索引文件内容
     *
     * @return MEMORY.md内容或空字符串
     */
    public String getMemoryIndex() {
        Path indexPath = Paths.get(getMemoriesRootDirectory(), "MEMORY.md");
        if (!Files.exists(indexPath)) {
            return "";
        }
        try {
            return Files.readString(indexPath);
        } catch (IOException e) {
            log.error("读取MEMORY.md失败", e);
            return "";
        }
    }

    /**
     * 获取记忆统计信息
     *
     * @return 统计摘要文本
     */
    public String getMemoryStats() {
        Map<String, List<String>> memories = listAllMemories();
        StringBuilder stats = new StringBuilder();
        stats.append("记忆统计:\n");
        for (Map.Entry<String, List<String>> entry : memories.entrySet()) {
            int count = entry.getValue().size();
            stats.append(String.format("  - %s: %d files\n", entry.getKey(), count));
        }
        return stats.toString();
    }
}
