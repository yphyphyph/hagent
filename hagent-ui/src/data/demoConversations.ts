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
 * 演示会话静态数据（5 条 canned 会话）
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
      {
        id: 'demo-1-m3',
        role: 'user',
        content: '那知识库问答是怎么实现的，用到了 RAG 吗？',
        time: '09:31',
      },
      {
        id: 'demo-1-m4',
        role: 'ai',
        content: `是的，知识库问答基于 RAG 检索增强生成实现，整体流程分为四个阶段：

1. **文档预处理**：上传的文档按段落切分并做清洗，去掉冗余格式，保留标题层级作为语义边界。
2. **向量化与入库**：每个分块通过 Embedding 模型转为向量，写入向量数据库并建立索引，同时保存原文与元数据。
3. **检索与重排**：收到提问时，先将问题向量化做相似度检索，召回最相关的若干分块，再通过重排模型筛选出最贴合提问的上下文。
4. **上下文注入生成**：把筛选后的上下文拼接到提示词中，连同用户问题一起交给大模型生成回答，并在回答末尾标注引用来源。

这种方式让模型既能利用自身能力，也能基于你的私有文档作答。`,
        time: '09:31',
        tools: ['searchKnowledge'],
      },
      {
        id: 'demo-1-m5',
        role: 'user',
        content: '如果文档很长，会不会超出 token 限制？',
        time: '09:33',
      },
      {
        id: 'demo-1-m6',
        role: 'ai',
        content: `不会直接超出，系统在多个环节做了限制与兜底：

- **分块控制**：单个分块默认 500 到 800 字，重叠 50 到 100 字，保证语义完整的同时控制长度。
- **召回数量限制**：每次检索最多取 Top 5 相关分块，避免上下文过长。
- **提示词预算**：拼接时会计算总 token 数，超出阈值则按相关度从低到高截断，优先保留最相关的部分。
- **超长文档处理**：对于数万字的文档，会在入库阶段就切成多块分别向量化，查询时只取与问题最相关的几块，而不是全文注入。

所以即使知识库里有很长的文档，单次问答的上下文也能稳定在模型窗口内。`,
        time: '09:33',
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
        time: '10:05',
      },
      {
        id: 'demo-2-m2',
        role: 'ai',
        content: '已完成统计，当前知识库共有 12 个文件，涵盖技术文档、产品说明与规章制度三类，已全部完成向量化，可直接进行检索问答。',
        time: '10:05',
        tools: ['searchKnowledge'],
      },
      {
        id: 'demo-2-m3',
        role: 'user',
        content: '哪些类型的文档最多？',
        time: '10:06',
      },
      {
        id: 'demo-2-m4',
        role: 'ai',
        content: `按文档类型统计如下：

| 文档类型 | 数量 | 占比 |
|---------|------|------|
| 技术文档 | 6 | 50% |
| 产品说明 | 4 | 33% |
| 规章制度 | 2 | 17% |
| **合计** | **12** | **100%** |

技术文档数量最多，主要包括架构设计、接口说明和部署手册三部分。如果你想看某一类的明细，可以直接说比如“列一下技术文档清单”。`,
        time: '10:06',
        tools: ['searchKnowledge'],
      },
      {
        id: 'demo-2-m5',
        role: 'user',
        content: '最近一周新增了多少个文件？',
        time: '10:07',
      },
      {
        id: 'demo-2-m6',
        role: 'ai',
        content: `最近 7 天共新增 3 个文件：

- 2 个技术文档，分别是《向量检索优化说明》和《MCP 工具接入指南》，均已完成向量化
- 1 个产品说明《用户端功能清单 v2.1》

目前新增文件的检索与问答已生效，你可以直接针对新文档内容提问。`,
        time: '10:07',
        tools: ['searchKnowledge'],
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
        time: '09:33',
      },
      {
        id: 'demo-3-m5',
        role: 'user',
        content: '帮我查一下这篇文章现在的状态',
        time: '09:33',
      },
      {
        id: 'demo-3-m6',
        role: 'ai',
        content: `查询结果如下：

- 文章 ID：3
- 标题：《Spring AI 集成实践》
- 状态：已发布
- 更新时间：2026-08-26 09:33
- 操作人：当前登录用户

数据已同步更新，知识库中的检索结果也会反映最新状态。`,
        time: '09:34',
        tools: ['searchKnowledge'],
      },
    ],
  },
  {
    id: -4,
    title: '图像理解演示',
    updatedAt: new Date().toISOString(),
    messages: [
      {
        id: 'demo-4-m1',
        role: 'user',
        content: '你能识别图片内容吗？',
        time: '14:20',
      },
      {
        id: 'demo-4-m2',
        role: 'ai',
        content: `可以，系统支持图像理解能力，涵盖以下几类：

- **场景与物体识别**：识别图片中的人物、物体、场景和整体氛围
- **文字提取**：对图片中的印刷文字、手写备注、表格进行 OCR 识别
- **细节描述**：按区域描述构图、颜色、光线和关键元素

支持常见格式如 JPG、PNG、WebP，单张图片建议不超过 10MB。你可以直接上传图片，并告诉我需要做什么，比如“描述一下这张图”或“提取图中文字”。`,
        time: '14:20',
      },
      {
        id: 'demo-4-m3',
        role: 'user',
        content: '这张图里有什么？',
        time: '14:21',
      },
      {
        id: 'demo-4-m4',
        role: 'ai',
        content: `根据你上传的图片，内容描述如下：

- **主体**：画面中央是一座湖泊，湖面平静，倒映着远处的雪山
- **前景**：近处有几棵松树，枝叶清晰，光影落在草地上
- **背景**：远景为连绵的雪山，天空呈淡蓝色，有少量云层
- **整体氛围**：光线柔和，色调以蓝绿为主，给人宁静开阔的感觉

如果你需要更细粒度的分析，比如数一下树木数量或提取图中文字，也可以继续告诉我。`,
        time: '14:21',
      },
      {
        id: 'demo-4-m5',
        role: 'user',
        content: '能帮我提取图中右下角的水印文字吗？',
        time: '14:22',
      },
      {
        id: 'demo-4-m6',
        role: 'ai',
        content: `已对右下角区域做增强识别，提取结果如下：

- 文字内容：“© HAgent 2026”
- 位置：图片右下角，半透明水印
- 置信度：较高，字符清晰无遮挡

如果图片中还有其他需要提取的文字，可以指定区域，比如“左上角标题”或“表格中的第二行”，我会针对性识别。`,
        time: '14:22',
      },
    ],
  },
  {
    id: -5,
    title: '多轮上下文演示',
    updatedAt: new Date().toISOString(),
    messages: [
      {
        id: 'demo-5-m1',
        role: 'user',
        content: '帮我推荐一本关于人工智能的入门书',
        time: '15:10',
      },
      {
        id: 'demo-5-m2',
        role: 'ai',
        content: `推荐《人工智能：一种现代的方法》（Russell / Norvig）：

- **定位**：经典入门教材，覆盖搜索、推理、机器学习、自然语言处理等核心topic
- **适合人群**：零基础或有少量编程基础的读者，讲解由浅入深
- **特点**：理论与案例结合，每章附习题与延伸阅读

如果你偏实践，也可以考虑《深度学习入门：基于 Python 的理论与实现》，更侧重动手代码。需要我按你的基础进一步筛选吗？`,
        time: '15:10',
      },
      {
        id: 'demo-5-m3',
        role: 'user',
        content: '这本书的作者还有其他作品吗？',
        time: '15:11',
      },
      {
        id: 'demo-5-m4',
        role: 'ai',
        content: `你指的是《人工智能：一种现代的方法》的两位作者，他们的其他代表作如下：

- **Stuart Russell**：著有《Human Compatible》，探讨人工智能与人类价值观对齐的问题
- **Peter Norvig**：著有《Paradigms of Artificial Intelligence Programming》，聚焦 Lisp 与 AI 编程范式，在 Google 任职期间也发表了大量关于搜索与数据科学的文章

两人合著的这本教材本身也有多个版本，第四版更新了深度学习相关章节。想了解哪一本的详细目录，我可以直接展开。`,
        time: '15:11',
        tools: ['searchKnowledge'],
      },
      {
        id: 'demo-5-m5',
        role: 'user',
        content: '我刚才问了什么，能帮我总结一下吗？',
        time: '15:12',
      },
      {
        id: 'demo-5-m6',
        role: 'ai',
        content: `当然，这是本轮对话的上下文总结：

1. 你先让我推荐一本人工智能入门书，我推荐了《人工智能：一种现代的方法》并补充了一本实践类备选
2. 接着你追问这本书作者的其他作品，我列出了 Russell 和 Norvig 各自的代表作
3. 现在你让我回顾刚才的提问，以上就是完整脉络

系统会记住同一会话中的提问历史，所以后续你可以直接说“那本实践类书的作者呢”或“帮我对比一下这两本”，我都能基于上下文继续作答。`,
        time: '15:12',
      },
    ],
  },
]
