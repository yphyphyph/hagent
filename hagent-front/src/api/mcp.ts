import request from '@/utils/request'

/**
 * MCP 工具台
 * 对应后端 McpToolController（/admin/ai/mcp）
 */
export interface McpToolParam {
  /** 参数名 */
  name: string
  /** Java 类型简称 */
  type?: string
  /** 参数说明 */
  description?: string
  /** 是否必填 */
  required?: boolean
}

export interface McpToolItem {
  /** 工具名（@Tool.name 或方法名） */
  name: string
  /** 反射方法名 */
  method?: string
  /** 工具描述 */
  description?: string
  /** 所属工具类简称（来源） */
  owner?: string
  /** 返回类型简称 */
  returnType?: string
  /** 参数定义 */
  params?: McpToolParam[]
}

/** MCP 接入配置 */
export interface McpConnectionConfig {
  serverName?: string
  transport?: string
  protocol?: string
  sseEndpoint?: string
  messageEndpoint?: string
  toolCount?: number
  clientConfigSample?: Record<string, unknown>
}

export const mcpApi = {
  /** MCP 工具总览（反射列出所有 @Tool 方法） */
  listTools(): Promise<McpToolItem[]> {
    return request.get<McpToolItem[], McpToolItem[]>('/admin/ai/mcp/tools')
  },

  /** 在线调试：调用指定工具，请求体 { toolName, params } */
  invoke(toolName: string, params: Record<string, unknown>): Promise<unknown> {
    return request.post<unknown, unknown>('/admin/ai/mcp/invoke', { toolName, params })
  },

  /** MCP 接入配置生成 */
  config(): Promise<McpConnectionConfig> {
    return request.get<McpConnectionConfig, McpConnectionConfig>('/admin/ai/mcp/config')
  },
}
