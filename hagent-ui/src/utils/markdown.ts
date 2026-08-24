/**
 * 统一 Markdown 渲染（marked + highlight.js + dompurify）
 *
 * 供 AI 对话消息 / AI 工具执行结果等场景使用：
 * - marked 负责完整 GFM 语法（表格、标题、列表、代码块、链接等）
 * - highlight.js 负责代码块语法高亮（深色岛主题，与设计系统一致）
 * - 代码块输出统一结构：<div.code-block><div.code-head>语言+复制按钮</div><pre><code></div>
 *   复制按钮通过容器事件委托实现（见 ChatMessageList 的 onCopyClick），无需内联 JS
 * - dompurify 负责 XSS 消毒（v-html 注入前必须过滤）
 * - 流式输出时可直接调用：marked 对未闭合语法做宽松处理，逐 chunk 重渲即可
 *
 * 样式约定：渲染容器加 .md-body 类，代码块样式见 src/styles/global.css
 */
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/core'

/* 按需注册常用语言，控制包体积 */
import bash from 'highlight.js/lib/languages/bash'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import python from 'highlight.js/lib/languages/python'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'

hljs.registerLanguage('bash', bash)
hljs.registerLanguage('shell', bash)
hljs.registerLanguage('java', java)
hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('js', javascript)
hljs.registerLanguage('json', json)
hljs.registerLanguage('python', python)
hljs.registerLanguage('py', python)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('typescript', typescript)
hljs.registerLanguage('ts', typescript)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('html', xml)
hljs.registerLanguage('yaml', yaml)

marked.setOptions({
  gfm: true, // GitHub Flavored Markdown（表格、删除线、任务列表等）
  breaks: true, // 单换行渲染为 <br>，符合聊天消息习惯
})

/** HTML 转义（代码块头部语言标签用） */
function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/** 自定义渲染器：代码块 → 高亮 + 语言标签 + 复制按钮结构 */
const renderer = {
  code({ text, lang }: { text: string; lang?: string }): string {
    const language = (lang ?? '').trim().split(/\s+/)[0] ?? ''
    let highlighted: string
    let displayLang = language
    if (language && hljs.getLanguage(language)) {
      highlighted = hljs.highlight(text, { language }).value
    } else {
      highlighted = escapeHtml(text)
      // 无语言标记时尝试自动检测，识别成功则采用检测结果
      if (!language) {
        try {
          const detected = hljs.highlightAuto(text)
          highlighted = detected.value
          displayLang = detected.language ?? ''
        } catch {
          // 自动检测失败则保持纯文本
        }
      }
    }
    const langLabel = displayLang ? `<span class="code-lang">${escapeHtml(displayLang)}</span>` : '<span class="code-lang">text</span>'
    return (
      `<div class="code-block">` +
      `<div class="code-head">${langLabel}<button class="code-copy" type="button">复制</button></div>` +
      `<pre><code class="hljs">${highlighted}</code></pre>` +
      `</div>`
    )
  },
}

marked.use({ renderer })

/** DOMPurify 白名单补充：允许代码块结构与按钮（事件由容器委托绑定） */
const PURIFY_CONFIG = {
  ADD_TAGS: ['button'],
  ADD_ATTR: ['type'],
}

/** 渲染 Markdown 为安全 HTML（已消毒，可直接 v-html） */
export function renderMarkdown(text: string): string {
  if (!text) return ''
  const html = marked.parse(text, { async: false })
  return DOMPurify.sanitize(html, PURIFY_CONFIG)
}
