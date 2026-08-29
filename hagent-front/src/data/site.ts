/**
 * HAgent v2 站点静态数据（原型阶段 mock，后续接入后端）
 */

export const site = {
  name: 'HAgent',
  owner: 'H',
  role: 'Java 全栈 · AI 应用开发者',
  slogan: '把代码写成作品，把 AI 落进业务',
  description:
    '6 年一线 Java 开发经验，深耕制造业数字化（APMOM / OA / WMS / 资产系统），专注大模型应用落地（Spring AI / RAG / MCP / SSE）。',
  location: '广东东莞',
  email: '159***@qq.com',
  csdn: 'https://blog.csdn.net/Ying_ph',
  website: 'https://www.huivip.xyz',
  github: 'https://github.com/yphyphyph',
  startYear: 2020,
}

export interface Tool {
  id: string
  name: string
  desc: string
  category: '对话' | '知识库' | '效率'
  icon: string
  status: '可用' | '演示中' | '开发中'
}

export const tools: Tool[] = [
  { id: 'chat', name: 'AI 智能对话', desc: 'SSE 流式对话，支持 MCP 工具调用查询业务数据', category: '对话', icon: 'chat', status: '可用' },
  { id: 'rag', name: '知识库问答', desc: '上传文档构建专属知识库，RAG 检索问答', category: '知识库', icon: 'book', status: '可用' },
  { id: 'ecn', name: 'ECN 变更影响分析', desc: '大模型解析变更单，自动生成影响清单', category: '效率', icon: 'doc', status: '演示中' },
  { id: 'prompt', name: 'Prompt 工程助手', desc: '结构化 Prompt 模板与优化建议', category: '效率', icon: 'wand', status: '开发中' },
]

export interface SkillGroup {
  category: string
  items: string[]
}

export const skillGroups: SkillGroup[] = [
  {
    category: '后端开发',
    items: ['Java', 'Spring Boot', 'Spring Cloud', 'Spring Security', 'MyBatis-Plus', '微服务'],
  },
  {
    category: 'AI 应用',
    items: ['Spring AI', 'SSE 流式对话', 'RAG 知识检索', 'MCP 工具调用', 'Prompt 工程'],
  },
  {
    category: '前端开发',
    items: ['Vue 3', 'TypeScript', 'Naive UI', 'Vite'],
  },
  {
    category: '数据与中间件',
    items: ['MySQL', 'PostgreSQL', 'Redis', 'RabbitMQ', 'Elasticsearch', 'MinIO'],
  },
  {
    category: '部署运维',
    items: ['Docker', 'Nginx', 'Jenkins / Zadig', 'Linux', 'K8s'],
  },
]

export const timeline = [
  { period: '2021.11 – 至今', company: '广东***科技有限公司', role: 'Java 软件工程师', desc: 'APMOM 智能工厂、OA 办公系统、云资产管理系统、充值平台、商城系统' },
  { period: '2021.06 – 2021.10', company: '广东***科技有限公司', role: 'Java 软件工程师', desc: '松山湖高新区公共租赁住房管理系统' },
  { period: '2020.04 – 2021.05', company: '***集团股份有限公司', role: '软件工程师', desc: 'CRM 客户关系管理系统' },
]
