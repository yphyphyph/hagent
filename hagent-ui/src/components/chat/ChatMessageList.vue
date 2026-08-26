<template>
  <div ref="listRef" class="msg-list" @scroll.passive="onScroll">
    <!-- 欢迎屏：无消息且非加载时 -->
    <div v-if="!loading && messages.length === 0" class="welcome">
      <span class="welcome__badge">HAgent · Agentic AI</span>
      <h1 class="welcome__title">把问题交给<br /><em>会思考的助手</em></h1>
      <p class="welcome__subtitle">
        知识库问答 · 数据洞察 · 工具编排 —— 每一次回答都有据可循
      </p>

      <div class="welcome__input-wrap">
        <textarea
          ref="heroInputRef"
          v-model="draft"
          class="welcome-input"
          rows="1"
          placeholder="输入你的问题，Enter 发送…"
          @compositionstart="composing = true"
          @compositionend="composing = false"
          @keydown.enter.exact.prevent="onHeroEnter"
          @input="autoResize"
        ></textarea>
        <button class="welcome-send" type="button" :disabled="!draft.trim()" @click="submitDraft">
          发送
        </button>
      </div>

      <p class="welcome__hint">Enter 发送 · Shift + Enter 换行</p>

      <p v-if="guest" class="welcome__guest-tip">
        访客模式：对话不会被保存，<router-link to="/login">登录</router-link>后可保留历史会话
      </p>
    </div>

    <!-- 消息流 -->
    <template v-else>
      <div class="msg-stream">
        <div v-if="loading" class="msg-loading">会话加载中…</div>

        <div
          v-for="m in messages"
          :key="m.id"
          class="msg-row fade-up"
          :class="m.role === 'user' ? 'msg-row--user' : 'msg-row--ai'"
        >
          <!-- 用户消息：右侧气泡 -->
          <template v-if="m.role === 'user' && m.content">
            <div class="user-bubble">{{ m.content }}</div>
          </template>

          <!-- AI 回复：去气泡全宽排版 -->
          <div v-else-if="m.role === 'ai' && (m.content || m.tools?.length || m.thinkingContent)" class="ai-msg">
            <!-- 工具徽章行：去重后展示，同名工具合并为一次并追加 xN 计数 -->
            <div v-if="m.tools?.length" class="tool-row">
              <span v-for="badge in dedupedTools(m.tools)" :key="badge.name" class="tool-chip">
                <span class="tool-chip__dot"></span>{{ badge.label }}{{ badge.count > 1 ? ` x${badge.count}` : '' }}
              </span>
            </div>

            <!-- 思考过程折叠块（默认展开，点击收起，markdown 渲染，增量更新时自动贴底） -->
            <!-- 修复：外层 v-if 确保首个 thinking chunk 立即挂载；内层改用 v-if 而非 v-show，避免 v-show 初始 display:none 导致增量不可见与批量渲染延迟 -->
            <div v-if="m.thinkingContent" class="thinking-block">
              <button class="thinking-block__header" type="button" @click="toggleThinking(m)">
                <span class="thinking-block__icon" :class="{ 'is-expanded': isExpanded(m) }">▸</span>
                <span class="thinking-block__title">思考过程</span>
                <span class="thinking-block__hint">{{ isExpanded(m) ? '收起' : '展开' }}</span>
              </button>
              <div
                v-if="isExpanded(m)"
                ref="thinkingBodyRefs"
                class="thinking-block__body md-body"
                v-html="renderMarkdown(m.thinkingContent)"
                @click="onContentClick($event)"
              ></div>
            </div>

            <!-- 正文 -->
            <div
              class="md-body ai-content"
              v-html="renderMarkdown(m.content)"
              @click="onContentClick($event)"
            ></div>

            <!-- 回答耗时统计（仅展示：毫秒转秒，保留1位小数） -->
            <div v-if="m.elapsedMs !== undefined" class="ai-meta">
              <span>回答耗时 {{ (m.elapsedMs / 1000).toFixed(1) }} 秒</span>
            </div>
          </div>
        </div>

        <!-- 思考中占位（首字延迟） -->
        <div v-if="thinking" class="thinking fade-up">
          <span class="thinking-dot"></span>
          <span class="thinking-dot"></span>
          <span class="thinking-dot"></span>
          <span class="thinking-label">AI 思考中</span>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, reactive, ref, watch } from 'vue'
import { renderMarkdown } from '@/utils/markdown'
import type { UiChatMessage } from '@/composables/useChat'

const props = withDefaults(
  defineProps<{
    messages: UiChatMessage[]
    loading?: boolean
    thinking?: boolean
    guest?: boolean
  }>(),
  { loading: false, thinking: false, guest: false }
)

const emit = defineEmits<{
  (e: 'send', text: string): void
}>()

/* ---------- 草稿与发送（欢迎屏 hero 输入框） ---------- */
const draft = ref('')
const heroInputRef = ref<HTMLTextAreaElement | null>(null)
const composing = ref(false)

function submitDraft() {
  const text = draft.value.trim()
  if (!text || composing.value) return
  emit('send', text)
  draft.value = ''
  const el = heroInputRef.value
  if (el) el.style.height = 'auto'
}

function onHeroEnter() {
  if (composing.value) return
  submitDraft()
}

/** textarea 高度自适应 */
function autoResize(e: Event) {
  const el = e.target as HTMLTextAreaElement
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 160)}px`
}

/* ---------- 思考过程折叠状态（默认展开） ---------- */
// 以消息 id 为 key 的展开映射；优先使用消息自身的 thinkingExpanded 字段，兼容本地状态
const expandedMap = reactive<Record<string, boolean>>({})
// 思考块内容容器引用（用于增量滚动）
const thinkingBodyRefs = ref<HTMLElement[]>([])

/** 判断是否被屏蔽的阶段关键词（与 useChat 保持一致，前端不展示生成/执行查询） */
const BLOCKED_STAGE_KEYWORDS = ['生成查询', '执行查询']
function isBlockedStage(text: string): boolean {
  return BLOCKED_STAGE_KEYWORDS.some((k) => text.includes(k))
}

function isExpanded(m: UiChatMessage): boolean {
  const key = String(m.id)
  // 若消息自带 thinkingExpanded 则以其为准，否则查本地 map；默认展开
  if (typeof m.thinkingExpanded === 'boolean') return m.thinkingExpanded
  if (key in expandedMap) return !!expandedMap[key]
  return true
}

function toggleThinking(m: UiChatMessage): void {
  const key = String(m.id)
  // 同步写回消息对象与本地映射，保持响应式
  const next = !isExpanded(m)
  // 优先写回消息字段（useChat 提供的可变对象）
  ;(m as UiChatMessage).thinkingExpanded = next
  expandedMap[key] = next
}

/* ---------- 工具徽章文案映射 ---------- */
const TOOL_LABELS: Record<string, string> = {
  searchKnowledge: '检索知识库',
  queryBusinessData: '生成查询 SQL',
  analyze_image: '分析图片',
}
function toolLabel(name: string): string {
  return TOOL_LABELS[name] ?? name
}

// 去重并计数：按首次出现顺序合并同名工具，返回用于徽章渲染的列表
function dedupedTools(tools?: string[]): Array<{ name: string; count: number; label: string }> {
  if (!tools?.length) return []
  const counter = new Map<string, number>()
  const order: string[] = []
  for (const t of tools) {
    if (!counter.has(t)) {
      counter.set(t, 1)
      order.push(t)
    } else {
      counter.set(t, (counter.get(t) ?? 0) + 1)
    }
  }
  return order.map((name) => ({
    name,
    count: counter.get(name) ?? 1,
    label: toolLabel(name),
  }))
}

/* ---------- 代码块复制（事件委托） ---------- */
async function onContentClick(e: MouseEvent) {
  const target = e.target as HTMLElement
  const btn = target.closest('.code-copy') as HTMLButtonElement | null
  if (!btn) return
  const code = btn.closest('.code-block')?.querySelector('code')
  if (!code) return
  try {
    await navigator.clipboard.writeText(code.textContent ?? '')
    btn.textContent = '已复制 ✓'
    btn.classList.add('code-copy--done')
    setTimeout(() => {
      btn.textContent = '复制'
      btn.classList.remove('code-copy--done')
    }, 1500)
  } catch {
    btn.textContent = '复制失败'
    setTimeout(() => (btn.textContent = '复制'), 1500)
  }
}

/* ---------- 自动贴底 ---------- */
const listRef = ref<HTMLElement | null>(null)
let stickToBottom = true

function onScroll() {
  const el = listRef.value
  if (!el) return
  stickToBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80
}

function scrollToBottom(force = false) {
  if (!force && !stickToBottom) return
  nextTick(() => {
    const el = listRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

watch(
  // 消息条数或任一消息内容/思考内容变化时尝试贴底（用户上滚时由 stickToBottom 暂停）
  () => [
    props.messages.length,
    props.messages.map((m) => `${m.content.length}:${(m.thinkingContent ?? '').length}:${m.thinkingExpanded ? 1 : 0}`).join(','),
    props.thinking,
  ],
  () => scrollToBottom(),
  // 使用 flush:'post' 确保 DOM 更新后立即滚动，immediate 保证首片思考到达时即触发贴底
  { flush: 'post', immediate: true }
)

// 思考内容增量监听：v-if 块展开时自动滚动思考容器到底部，确保增量逐片可见
// 关键修复：设置 flush:'post' + immediate:true，避免 Vue 将多片 thinking chunk 合并为一次 nextTick 才滚动
watch(
  () => props.messages.map((m) => m.thinkingContent ?? '').join('|'),
  () => {
    // 仅当存在展开的思考块时滚动
    const hasExpandedThinking = props.messages.some((m) => !!m.thinkingContent && isExpanded(m))
    if (!hasExpandedThinking) return
    nextTick(() => {
      // 滚动思考内容容器到底部（逐片增量均触发）
      for (const el of thinkingBodyRefs.value) {
        if (el) el.scrollTop = el.scrollHeight
      }
      // 同时保持整体消息列表贴底
      scrollToBottom()
    })
  },
  { flush: 'post', immediate: true }
)

// 额外监听：thinking 长度细粒度变化（避免 join 字符串批量合并，强制每片都触发滚动）
watch(
  () => props.messages.map((m) => (m.thinkingContent ?? '').length).join(','),
  () => {
    const hasExpandedThinking = props.messages.some((m) => !!m.thinkingContent && isExpanded(m))
    if (!hasExpandedThinking) return
    nextTick(() => {
      for (const el of thinkingBodyRefs.value) {
        if (el) el.scrollTop = el.scrollHeight
      }
      scrollToBottom()
    })
  },
  { flush: 'post', immediate: true }
)

onMounted(() => scrollToBottom(true))

defineExpose({ scrollToBottom })
</script>

<style scoped>
.msg-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  scroll-behavior: smooth;
  /* flex 纵向布局：欢迎屏 margin:auto 垂直居中，消息流正常排列 */
  display: flex;
  flex-direction: column;
}
.msg-list .msg-stream {
  width: 100%;
}

/* ---------- 欢迎屏 ---------- */
.welcome {
  max-width: 840px;
  width: 100%;
  margin: auto;
  padding: var(--space-5) var(--space-5) var(--space-6);
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
}
.welcome__badge {
  font-size: var(--text-xs);
  letter-spacing: 0.12em;
  color: var(--accent);
  background: var(--accent-soft);
  border: 1px solid var(--accent-border);
  border-radius: 999px;
  padding: 4px 14px;
}
.welcome__title {
  font-family: var(--font-display), 'Noto Serif SC', serif;
  font-size: clamp(34px, 6vw, 52px);
  font-weight: 700;
  line-height: 1.15;
  margin: var(--space-5) 0 0;
  color: var(--text-1);
}
.welcome__title em {
  font-style: normal;
  background: linear-gradient(120deg, var(--accent), var(--accent-strong, var(--accent)));
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.welcome__subtitle {
  margin-top: var(--space-3);
  color: var(--text-2);
  font-size: var(--text-base);
}
.welcome__input-wrap {
  width: 100%;
  margin-top: var(--space-5);
  display: flex;
  align-items: center;
  gap: var(--space-2);
  background: var(--bg-elevated);
  border: 1px solid var(--border-strong);
  border-radius: 16px;
  padding: var(--space-3);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.08);
  transition: border-color var(--dur-fast) var(--ease), box-shadow var(--dur-fast) var(--ease);
  text-align: left;
}
.welcome__input-wrap:focus-within {
  border-color: var(--accent-border);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.08), 0 0 0 3px var(--accent-soft);
}
.welcome-input {
  flex: 1;
  resize: none;
  border: none;
  outline: none;
  background: transparent;
  color: var(--text-1);
  font-size: var(--text-base);
  line-height: 1.6;
  font-family: inherit;
  max-height: 160px;
}
.welcome-send {
  flex-shrink: 0;
  height: 36px;
  padding: 0 18px;
  border-radius: 10px;
  border: none;
  background: var(--accent);
  color: #fff;
  font-weight: 600;
  cursor: pointer;
  transition: opacity var(--dur-fast) var(--ease), transform var(--dur-fast) var(--ease);
}
[data-theme='dark'] .welcome-send {
  color: #0f172a;
}
.welcome-send:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
.welcome-send:not(:disabled):hover {
  transform: translateY(-1px);
}
.welcome__hint {
  margin-top: var(--space-5);
  font-size: var(--text-xs);
  letter-spacing: 0.08em;
  color: var(--text-3);
}
.welcome__guest-tip {
  margin-top: var(--space-6);
  font-size: var(--text-xs);
  color: var(--text-3);
}
.welcome__guest-tip a {
  color: var(--accent);
}

/* ---------- 消息流 ---------- */
.msg-stream {
  max-width: 840px;
  margin: 0 auto;
  padding: var(--space-6) var(--space-5) var(--space-8);
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
}
.msg-loading {
  text-align: center;
  color: var(--text-3);
  font-size: var(--text-sm);
  padding: var(--space-8) 0;
}

.msg-row--user {
  display: flex;
  justify-content: flex-end;
}
.user-bubble {
  max-width: 78%;
  padding: 10px 16px;
  background: var(--accent-soft);
  border: 1px solid var(--accent-border);
  color: var(--text-1);
  border-radius: 16px 16px 4px 16px;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.7;
  font-size: var(--text-base);
}

/* AI 回复：去气泡全宽 */
.msg-row--ai {
  animation-delay: 40ms;
}
.ai-msg {
  min-width: 0;
}
.ai-content {
  color: var(--text-1);
  font-size: var(--text-base);
}

/* 回答耗时统计（底部小字，弱化展示） */
.ai-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: var(--space-3);
  padding-left: 2px;
  font-size: var(--text-xs);
  color: var(--text-3);
  opacity: 0.75;
  user-select: none;
}
.ai-meta::before {
  content: '';
  width: 1px;
  height: 10px;
  border-radius: 1px;
  background: var(--border);
}
.ai-meta span {
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.02em;
}

/* 工具徽章行 */
.tool-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}
.tool-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-xs);
  font-family: var(--font-mono);
  color: var(--text-2);
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 3px 12px;
}
.tool-chip__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
}

/* ---------- 思考过程折叠块 ---------- */
.thinking-block {
  margin-bottom: var(--space-3);
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--bg-elevated, var(--bg-soft, #f8fafc));
  overflow: hidden;
}
.thinking-block__header {
  width: 100%;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border: none;
  background: transparent;
  color: var(--text-2);
  font-size: var(--text-xs);
  font-weight: 600;
  letter-spacing: 0.04em;
  cursor: pointer;
  text-align: left;
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}
.thinking-block__header:hover {
  background: var(--accent-soft, rgba(0, 0, 0, 0.04));
  color: var(--text-1);
}
.thinking-block__icon {
  display: inline-block;
  font-size: 12px;
  line-height: 1;
  transition: transform 0.18s var(--ease);
}
.thinking-block__icon.is-expanded {
  transform: rotate(90deg);
}
.thinking-block__title {
  flex-shrink: 0;
}
.thinking-block__hint {
  margin-left: auto;
  font-size: 11px;
  color: var(--text-3);
  font-weight: 400;
}
.thinking-block__body {
  padding: 10px 14px 14px;
  border-top: 1px dashed var(--border);
  color: var(--text-2);
  font-size: var(--text-sm);
  line-height: 1.7;
  background: var(--bg, #fff);
  max-height: 320px;
  overflow-y: auto;
  white-space: normal;
  word-break: break-word;
}
[data-theme='dark'] .thinking-block {
  background: rgba(255, 255, 255, 0.04);
}
[data-theme='dark'] .thinking-block__body {
  background: rgba(255, 255, 255, 0.02);
}

/* ---------- 思考中 ---------- */
.thinking {
  display: flex;
  align-items: center;
  gap: 5px;
}
.thinking-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
  animation: pulse-dot 1.2s var(--ease) infinite;
}
.thinking-dot:nth-child(2) {
  animation-delay: 0.15s;
}
.thinking-dot:nth-child(3) {
  animation-delay: 0.3s;
}
.thinking-label {
  margin-left: var(--space-2);
  font-size: var(--text-xs);
  color: var(--text-3);
}

.fade-up {
  animation: fade-up 0.35s var(--ease) both;
}
</style>
