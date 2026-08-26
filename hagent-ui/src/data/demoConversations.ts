import type { UiChatMessage } from '@/composables/useChat'

/**
 * 演示会话模型（静态数据，id < 0 标记为演示会话，避免与真实雪花 id 碰撞）
 * @author H
 */
export interface DemoConversation {
  id: number
  title: string
  updatedAt: string
  messages: UiChatMessage[]
}

/**
 * 演示会话静态数据（3 条 canned 会话）
 * - id 均为负数，天然与雪花正数区隔；id < 0 即演示标记
 * - 不依赖任何运行时逻辑，侧栏与消息区可直接消费
 */
export const demoConversations: DemoConversation[] = [
  {
    id: -1,
    title: '知识库问答演示',
    updatedAt: new Date().toISOString(),
    messages: [
      {
        id: 'demo-1-m1',
        role: 'user',
        content: '这个项目用了哪些技术栈？',
        time: '09:30',
      },
      {
        id: 'demo-1-m2',
        role: 'ai',
        content: `### 技术栈概览

本项目采用前后端分离架构，核心技术栈如下：

- **后端**：Spring Boot 3.x / Spring Security / MyBatis-Plus / MySQL / Redis
- **前端**：Vue 3 + TypeScript + Vite + Naive UI
- **AI 能力**：Spring AI / RAG 知识库检索 / MCP 工具调用 / SSE 流式对话
- **部署**：Docker / Nginx

> 以上为演示数据，登录后可体验真实知识库问答能力。`,
        time: '09:30',
        tools: ['searchKnowledge'],
      },
    ],
  },
  {
    id: -2,
    title: '数据统计演示',
    updatedAt: new Date().toISOString(),
    messages: [
      {
        id: 'demo-2-m1',
        role: 'user',
        content: '知识库里现在有多少个文件？',
        time: '09:31',
      },
      {
        id: 'demo-2-m2',
        role: 'ai',
        content: '已完成统计，当前知识库共有 12 个文件，涵盖技术文档、产品说明与规章制度三类，已全部完成向量化，可直接进行检索问答。',
        time: '09:31',
      },
    ],
  },
  {
    id: -3,
    title: '数据变更确认演示',
    updatedAt: new Date().toISOString(),
    messages: [
      {
        id: 'demo-3-m1',
        role: 'user',
        content: '帮我把 id 为 3 的文章状态改为已发布',
        time: '09:32',
      },
      {
        id: 'demo-3-m2',
        role: 'ai',
        content: '该操作将修改数据，是否执行？回复「确认」或「取消」',
        time: '09:32',
      },
      {
        id: 'demo-3-m3',
        role: 'user',
        content: '确认',
        time: '09:32',
      },
      {
        id: 'demo-3-m4',
        role: 'ai',
        content: '操作已执行完成，共更新 1 条数据。',
        time: '09:32',
      },
    ],
  },
]
