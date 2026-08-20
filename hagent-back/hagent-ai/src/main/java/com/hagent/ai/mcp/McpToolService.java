package com.hagent.ai.mcp;

import com.hagent.ai.tool.TextToSqlTool;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具台服务
 * <p>
 * 通过反射扫描已注册的 MCP 工具（{@code @Tool} 方法），提供：
 * 1. 工具总览（名称、描述、参数定义）
 * 2. 在线调试 invoke（按参数名装配后反射调用）
 * 3. MCP 接入配置生成（供外部客户端连接）
 *
 * @author H
 */
@Slf4j
@Service
public class McpToolService {

    private final ObjectMapper objectMapper;
    /** 已注册的 MCP 工具对象（与 McpServerConfig 中一致） */
    private final List<Object> toolObjects;

    @Value("${spring.application.name:hagent}")
    private String appName;

    @Value("${server.port:8080}")
    private String serverPort;

    public McpToolService(ObjectMapper objectMapper,
                          KnowledgeSearchTool knowledgeSearchTool,
                          DataAnalysisTool dataAnalysisTool,
                          AnalyzeImageTool analyzeImageTool,
                          TextToSqlTool textToSqlTool) {
        this.objectMapper = objectMapper;
        this.toolObjects = List.of(knowledgeSearchTool, dataAnalysisTool, analyzeImageTool, textToSqlTool);
    }

    /**
     * 反射列出所有 MCP 工具及其参数定义
     *
     * @return 工具描述列表
     */
    public List<Map<String, Object>> listTools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Object bean : toolObjects) {
            Class<?> clazz = resolveTargetClass(bean);
            for (Method method : clazz.getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool == null) {
                    continue;
                }
                Map<String, Object> desc = new LinkedHashMap<>();
                String toolName = tool.name() != null && !tool.name().isEmpty() ? tool.name() : method.getName();
                desc.put("name", toolName);
                desc.put("method", method.getName());
                desc.put("description", tool.description());
                desc.put("owner", clazz.getSimpleName());
                desc.put("returnType", method.getReturnType().getSimpleName());

                List<Map<String, Object>> params = new ArrayList<>();
                for (Parameter parameter : method.getParameters()) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("name", parameter.getName());
                    p.put("type", parameter.getType().getSimpleName());
                    ToolParam tp = parameter.getAnnotation(ToolParam.class);
                    p.put("description", tp != null ? tp.description() : "");
                    p.put("required", tp == null || tp.required());
                    params.add(p);
                }
                desc.put("params", params);
                tools.add(desc);
            }
        }
        return tools;
    }

    /**
     * 在线调试：按工具名与参数调用对应 @Tool 方法
     *
     * @param toolName 工具名（@Tool.name 或方法名）
     * @param args     参数（按参数名映射）
     * @return 调用结果
     */
    public Object invoke(String toolName, Map<String, Object> args) {
        Map<String, Object> safeArgs = args != null ? args : Map.of();
        for (Object bean : toolObjects) {
            Class<?> clazz = resolveTargetClass(bean);
            for (Method method : clazz.getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool == null) {
                    continue;
                }
                String name = tool.name() != null && !tool.name().isEmpty() ? tool.name() : method.getName();
                if (!name.equals(toolName) && !method.getName().equals(toolName)) {
                    continue;
                }
                try {
                    Parameter[] parameters = method.getParameters();
                    Object[] invokeArgs = new Object[parameters.length];
                    for (int i = 0; i < parameters.length; i++) {
                        Parameter parameter = parameters[i];
                        Object raw = safeArgs.get(parameter.getName());
                        invokeArgs[i] = convert(raw, parameter.getType());
                    }
                    method.setAccessible(true);
                    // 反射调用需作用于原始 bean 而非代理类，通过 bean 实例调用可正确走 Spring 增强
                    return method.invoke(bean, invokeArgs);
                } catch (Exception e) {
                    log.error("MCP工具调用失败: toolName={}", toolName, e);
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    throw new IllegalStateException("工具调用失败: " + cause.getMessage(), cause);
                }
            }
        }
        throw new IllegalArgumentException("未找到工具: " + toolName);
    }

    /**
     * 生成 MCP 接入配置（供外部 MCP 客户端连接本服务）
     *
     * @return 接入配置
     */
    public Map<String, Object> connectionConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("serverName", appName);
        config.put("transport", "sse");
        config.put("protocol", "MCP over HTTP SSE");
        config.put("sseEndpoint", "/sse");
        config.put("messageEndpoint", "/mcp/message");
        config.put("toolCount", listTools().size());

        // 标准 MCP 客户端配置片段（示例）
        Map<String, Object> sample = new LinkedHashMap<>();
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("url", "http://<host>:" + serverPort + "/sse");
        server.put("type", "sse");
        sample.put(appName, server);
        Map<String, Object> mcpServers = new LinkedHashMap<>();
        mcpServers.put("mcpServers", sample);
        config.put("clientConfigSample", mcpServers);
        return config;
    }

    /**
     * 解析真实目标类（穿透 CGLIB/JDK 代理），确保能读到 @Tool 注解方法
     */
    private Class<?> resolveTargetClass(Object bean) {
        Class<?> clazz = bean.getClass();
        // Spring CGLIB 代理类名包含 $$，其父类为真实类
        if (clazz.getName().contains("$$")) {
            return clazz.getSuperclass();
        }
        return clazz;
    }

    /**
     * 将原始参数值转换为方法参数类型
     */
    private Object convert(Object raw, Class<?> targetType) {
        if (raw == null) {
            // 基本类型缺省值，避免反射调用 NPE
            if (targetType == int.class || targetType == Integer.class) {
                return targetType.isPrimitive() ? 0 : null;
            }
            if (targetType == long.class) {
                return 0L;
            }
            if (targetType == double.class) {
                return 0d;
            }
            if (targetType == boolean.class) {
                return false;
            }
            return null;
        }
        try {
            return objectMapper.convertValue(raw, targetType);
        } catch (Exception e) {
            return raw;
        }
    }
}
