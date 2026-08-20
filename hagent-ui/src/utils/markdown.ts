/**
 * 统一 Markdown 渲染（marked + dompurify）
 *
 * 供 AI 对话气泡 / AI 工具执行结果等场景使用：
 * - marked 负责完整 GFM 语法（表格、标题、列表、代码块、链接等）
 * - dompurify 负责 XSS 消毒（v-html 注入前必须过滤）
 * - 流式输出时可直接调用：marked 对未闭合语法做宽松处理，逐 chunk 重渲即可
 *
 * 样式约定：渲染容器加 .md-body 类，样式定义在 src/styles/global.css
 */
import { marked } from 'marked'
import DOMPurify from 'dompurify'

marked.setOptions({
  gfm: true, // GitHub Flavored Markdown（表格、删除线、任务列表等）
  breaks: true, // 单换行渲染为 <br>，符合聊天消息习惯
})

/** 渲染 Markdown 为安全 HTML（已消毒，可直接 v-html） */
export function renderMarkdown(text: string): string {
  if (!text) return ''
  const html = marked.parse(text, { async: false })
  return DOMPurify.sanitize(html)
}
