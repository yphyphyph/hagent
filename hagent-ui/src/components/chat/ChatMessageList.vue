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
          <div v-else-if="m.role === 'ai' && (m.content || m.tools?.length || m.thinkingContent || m.sources?.length)" class="ai-msg">
            <!-- 工具徽章行：去重后展示，同名工具合并为一次并追加 xN 计数 -->
            <div v-if="m.tools?.length" class="tool-row">
              <span v-for="badge in dedupedTools(m.tools)" :key="badge.name" class="tool-chip">
                <span class="tool-chip__dot"></span>{{ badge.label }}{{ badge.count > 1 ? ` x${badge.count}` : '' }}
              </span>
            </div>

            <!-- 思考过程折叠块：唯一进度载体，标题 8 阶段动态切换，默认展开、完成后 800ms 自动收起 -->
            <div v-if="shouldShowThinkingBlock(m)" class="thinking-block">
              <button class="thinking-block__header" type="button" @click="toggleThinking(m)">
                <span class="thinking-block__icon" :class="{ 'is-expanded': isExpanded(m) }">▸</span>
                <!-- 思考标题脉冲点：流式进行时带动画，思考结束时静止 -->
                <span class="thinking-title-dot" :class="{ 'thinking-title-dot--pulse': isThinkingActive(m) }"></span>
                <!-- 思考标题：主标题黑粗（思考中/思考结束/思考过程），副标题灰色展示阶段，避免重复；思考中时标题带呼吸渐变 -->
                <span
                  class="thinking-block__title thinking-block__title--main"
                  :class="{ 'thinking-block__title--breathing': isThinkingActive(m) }"
                  >{{ getThinkingMainTitle(m) }}</span
                >
                <span v-if="getThinkingStageSubtitle(m)" class="thinking-block__subtitle">{{ getThinkingStageSubtitle(m) }}</span>
                <span class="thinking-block__hint">{{ isExpanded(m) ? '收起' : '展开' }}</span>
              </button>
              <div
                v-if="isExpanded(m)"
                ref="thinkingBodyRefs"
                class="thinking-block__body md-body"
                @click="onContentClick($event)"
              >
                <div v-if="m.thinkingContent" v-html="renderMarkdown(m.thinkingContent)"></div>
                <div v-else-if="getThinkingStageSubtitle(m)" class="thinking-block__placeholder">{{ getThinkingStageSubtitle(m) }}</div>
                <div v-else class="thinking-block__placeholder">正在思考...</div>
              </div>
            </div>

            <!-- 正文（支持行内引用角标：[1] 点击可打开抽屉） -->
            <div
              class="md-body ai-content"
              v-html="renderWithCitations(m)"
              @click="onContentClick($event)"
            ></div>

            <!-- 溯源聚合胶囊（ChatGLM 风格：紧凑 pill 排列于搜索块底部，点击打开抽屉） -->
            <div v-if="m.sources?.length" class="source-pill-bar">
              <button
                class="source-pill"
                type="button"
                :title="`${m.sources.length}个来源，点击查看`"
                @click="openDrawer(m, 0)"
              >
                {{ m.sources.length }}个来源 &gt;
              </button>
            </div>

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

    <!-- 溯源抽屉 -->
    <SourceDrawer
      :visible="drawerVisible"
      :sources="drawerSources"
      :active-index="drawerIndex"
      @close="drawerVisible = false"
      @jump="onJumpToSource"
    />
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { renderMarkdown } from '@/utils/markdown'
import type { UiChatMessage, ChatSource } from '@/composables/useChat'
import SourceDrawer from '@/components/chat/SourceDrawer.vue'

const props = withDefaults(
  defineProps<{
    messages: UiChatMessage[]
    loading?: boolean
    thinking?: boolean
    stageText?: string
    streaming?: boolean
    guest?: boolean
    /** 动态思考标题（来自 useChat.thinkingTitle，8 阶段之一） */
    thinkingTitle?: string
  }>(),
  { loading: false, thinking: false, stageText: '', streaming: false, guest: false, thinkingTitle: '' }
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

/** 判断是否被屏蔽的阶段关键词（执行查询内部阶段不展示，生成查询已纳入 8 阶段） */
const BLOCKED_STAGE_KEYWORDS = ['执行查询']
function isBlockedStage(text: string): boolean {
  return BLOCKED_STAGE_KEYWORDS.some((k) => text.includes(k))
}

// 8 阶段动态标题（与 useChat 及后端保持一致）
const THINKING_TITLES = [
  '思考中…',
  '正在理解问题…',
  '正在分析问题…',
  '制定解题策略',
  '检索知识库',
  '生成查询 SQL',
  '构建输出结构',
  '最终润色',
] as const
const THINK_DONE_TITLE = '思考结束'
const STAGE_TO_TITLE: Record<string, string> = {
  '思考中…': THINKING_TITLES[0],
  '正在理解问题…': THINKING_TITLES[1],
  '正在理解问题...': THINKING_TITLES[1],
  '正在分析问题…': THINKING_TITLES[2],
  '正在分析问题...': THINKING_TITLES[2],
  '制定解题策略': THINKING_TITLES[3],
  '检索知识库': THINKING_TITLES[4],
  '生成查询 SQL': THINKING_TITLES[5],
  '构建输出结构': THINKING_TITLES[6],
  '正在组织答案...': THINKING_TITLES[6],
  '正在组织答案…': THINKING_TITLES[6],
  '最终润色': THINKING_TITLES[7],
  '思考结束': THINK_DONE_TITLE,
}
/** 将 stage 文案归一为 8 阶段标题（用于标题动态切换兜底） */
function normalizeStageToTitle(stage: string): string | null {
  if (!stage) return null
  const t = stage.trim()
  if (STAGE_TO_TITLE[t]) return STAGE_TO_TITLE[t]
  if (t.includes('理解')) return THINKING_TITLES[1]
  if (t.includes('分析')) return THINKING_TITLES[2]
  if (t.includes('策略')) return THINKING_TITLES[3]
  if (t.includes('检索')) return THINKING_TITLES[4]
  if (t.includes('SQL') || t.includes('查询')) return THINKING_TITLES[5]
  if (t.includes('结构') || t.includes('组织')) return THINKING_TITLES[6]
  if (t.includes('润色')) return THINKING_TITLES[7]
  if (t.includes('结束')) return THINK_DONE_TITLE
  return t
}
/** 获取当前消息的思考块动态标题（单折叠块标题随流式阶段切换，保留用于兼容旧逻辑与脉冲判断） */
function getThinkingTitle(m: UiChatMessage): string {
  // 流式进行中的最后一条 AI 消息：优先使用父组件传入的 thinkingTitle（useChat 驱动的 8 阶段标题）
  if (isLastAiMessage(m) && (props.streaming || props.thinking)) {
    if (props.thinkingTitle && props.thinkingTitle.trim()) return props.thinkingTitle
    if (props.stageText) {
      const normalized = normalizeStageToTitle(props.stageText)
      if (normalized) return normalized
    }
    return THINKING_TITLES[0]
  }
  // 流式结束后：若存在思考内容则显示“思考结束”，便于区分已完成状态
  if (m.thinkingContent && m.thinkingContent.length > 0) {
    if (isLastAiMessage(m) && !props.streaming && !props.thinking) return THINK_DONE_TITLE
    return THINK_DONE_TITLE
  }
  return THINKING_TITLES[0]
}

/** 获取思考块主标题：流式时为“思考中”，结束后为“思考结束”，历史消息为“思考过程” */
function getThinkingMainTitle(m: UiChatMessage): string {
  // 流式活跃中的最后一条 AI 消息统一显示“思考中”
  if (isLastAiMessage(m) && (props.streaming || props.thinking)) return '思考中'
  // 有思考内容且流式已结束：最后一条显示“思考结束”，历史消息显示“思考过程”
  if (m.thinkingContent && m.thinkingContent.length > 0) {
    if (isLastAiMessage(m) && !props.streaming && !props.thinking) return THINK_DONE_TITLE
    return '思考过程'
  }
  // 无思考内容但处于流式占位阶段
  if (isLastAiMessage(m) && (props.streaming || props.thinking)) return '思考中'
  return '思考过程'
}

/** 获取思考块阶段副标题：仅流式活跃时展示阶段，结束后不展示，避免与主标题重复 */
function getThinkingStageSubtitle(m: UiChatMessage): string | null {
  if (!isLastAiMessage(m)) return null
  if (!props.streaming && !props.thinking) return null
  // 屏蔽阶段不展示
  const rawStage = props.thinkingTitle?.trim() ? props.thinkingTitle : props.stageText
  if (!rawStage) return null
  if (isBlockedStage(rawStage)) return null
  const normalized = normalizeStageToTitle(rawStage)
  if (!normalized) return null
  // 主标题已为“思考中”，过滤通用“思考中…”避免重复
  if (normalized === THINKING_TITLES[0]) return null
  if (normalized === THINK_DONE_TITLE) return null
  if (normalized === '思考中' || normalized === '思考中…') return null
  return normalized
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

/* ---------- 思考块显隐与阶段副标题（单一收敛载体） ---------- */
function isLastAiMessage(m: UiChatMessage): boolean {
  for (let i = props.messages.length - 1; i >= 0; i--) {
    if (props.messages[i].role === 'ai') return String(props.messages[i].id) === String(m.id)
  }
  return false
}
function showStageSubtitle(m: UiChatMessage): boolean {
  if (!props.stageText) return false
  if (isBlockedStage(props.stageText)) return false
  if (!isLastAiMessage(m)) return false
  // 标题已由 thinkingTitle 动态驱动时，不再重复显示副标题，避免标题与副标题同文
  if (props.thinkingTitle && props.thinkingTitle.trim()) {
    const titleNorm = normalizeStageToTitle(props.thinkingTitle)
    const stageNorm = normalizeStageToTitle(props.stageText)
    if (titleNorm && stageNorm && titleNorm === stageNorm) return false
    // 若标题处于 8 阶段序列中，整体隐藏副标题，标题即为唯一载体
    if ((THINKING_TITLES as readonly string[]).includes(props.thinkingTitle) || props.thinkingTitle === THINK_DONE_TITLE) return false
  }
  return !!(props.streaming || props.thinking)
}
function shouldShowThinkingBlock(m: UiChatMessage): boolean {
  if (m.thinkingContent && m.thinkingContent.length > 0) return true
  // 流式进行中的最后一条 AI 消息，即使尚无 thinkingContent，也展示思考块（以阶段副标题为初始状态）
  if (isLastAiMessage(m) && (props.thinking || props.streaming)) return true
  return false
}

/** 判断思考标题是否处于活跃流式状态（用于脉冲点动画：streaming 时脉冲，思考结束时静止） */
function isThinkingActive(m: UiChatMessage): boolean {
  const main = getThinkingMainTitle(m)
  // 仅主标题为“思考中”且流式进行时脉冲，思考结束/思考过程静止
  if (main !== '思考中') return false
  // 仅最后一条 AI 消息且处于流式状态时脉冲
  return !!props.streaming && isLastAiMessage(m)
}

/* 首次收到 thinking 首片时自动展开；流式结束后自动收起，满足“默认展开、完成后收起” */
watch(
  () => props.messages.map((m) => (m.thinkingContent ?? '').length).join(','),
  (curr, prev) => {
    if (!prev) return
    const currLens = curr.split(',').map((v) => Number(v))
    const prevLens = prev.split(',').map((v) => Number(v))
    props.messages.forEach((m, idx) => {
      const cl = currLens[idx] ?? 0
      const pl = prevLens[idx] ?? 0
      if (cl > 0 && pl === 0) {
        const key = String(m.id)
        ;(m as UiChatMessage).thinkingExpanded = true
        expandedMap[key] = true
      }
    })
  },
  { flush: 'post' }
)
watch(
  () => props.streaming,
  (streaming, prev) => {
    if (prev && !streaming) {
      const last = [...props.messages].reverse().find((m) => m.role === 'ai' && m.thinkingContent)
      if (last) {
        const key = String(last.id)
        setTimeout(() => {
          ;(last as UiChatMessage).thinkingExpanded = false
          expandedMap[key] = false
        }, 800)
      }
    }
  },
  { flush: 'post' }
)
// thinking 标志结束时亦收起（兼容无 streaming 的场景）
watch(
  () => props.thinking,
  (thinking, prev) => {
    if (prev && !thinking && !props.streaming) {
      const last = [...props.messages].reverse().find((m) => m.role === 'ai' && m.thinkingContent)
      if (last) {
        const key = String(last.id)
        setTimeout(() => {
          ;(last as UiChatMessage).thinkingExpanded = false
          expandedMap[key] = false
        }, 800)
      }
    }
  },
  { flush: 'post' }
)

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

/* ---------- 代码块复制与行内引用点击（事件委托） ---------- */
async function onContentClick(e: MouseEvent) {
  const target = e.target as HTMLElement
  // 行内引用角标点击：优先处理
  const cite = target.closest('.source-badge-inline') as HTMLElement | null
  if (cite) {
    const idx = Number(cite.dataset.index ?? '-1')
    const msgId = cite.dataset.msg ?? ''
    if (!Number.isNaN(idx) && idx >= 0) {
      // 通过消息 id 查找对应的消息对象
      const msg = props.messages.find((m) => String(m.id) === msgId)
      if (msg) openDrawer(msg, idx)
      else {
        // 兜底：若找不到则用当前抽屉源直接跳转
        if (drawerSources.value.length > idx) {
          drawerIndex.value = idx
          drawerVisible.value = true
        }
      }
    }
    return
  }
  const btn = target.closest('.code-copy') as HTMLButtonElement | null
  if (!btn) return
  const code = btn.closest('.code-block')?.querySelector('code')
  if (!code) return
  try {
    await navigator.clipboard.writeText(code.textContent ?? '')
    btn.textContent = '已复制'
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

/* ---------- 溯源引用：渲染与抽屉 ---------- */
const router = useRouter()
const naiveMessage = useMessage()
const drawerVisible = ref(false)
const drawerSources = ref<ChatSource[]>([])
const drawerIndex = ref(0)

function fileNameOf(s: ChatSource): string {
  return String(s.file_name ?? s.fileName ?? '')
}

function sourceTitle(s: ChatSource): string {
  const name = fileNameOf(s)
  const snippet = String(s.snippet ?? s.content ?? '')
  return name ? `${name} — ${snippet.slice(0, 80)}` : snippet.slice(0, 80)
}

function citationKey(s: ChatSource, idx: number): string {
  return `${String(s.file_id ?? '')}-${String(s.chunk_id ?? '')}-${idx}`
}

// 行内引用渲染：先用 marked 渲染，再将文本中的 [1]/[2] 转为可点击角标（保留 markdown 结构）
function renderWithCitations(m: UiChatMessage): string {
  const base = renderMarkdown(m.content ?? '')
  if (!m.sources?.length) return base
  // 若正文已包含 [n] 文本标记，将其替换为带 data-index 的可点击 span，便于事件委托打开抽屉
  // 正则匹配 [1] [2] ... 且 n 在 1..sources.length 范围内
  let html = base
  // 仅当存在 [数字] 模式时才处理，避免无意义替换
  if (/\[(\d+)\]/.test(m.content ?? '')) {
    html = html.replace(/\[(\d+)\]/g, (match, numStr) => {
      const n = Number(numStr)
      if (Number.isNaN(n) || n < 1 || n > (m.sources?.length ?? 0)) return match
      const idx = n - 1
      return `<button class="source-badge-inline" type="button" data-index="${idx}" data-msg="${String(m.id)}" title="${escapeAttr(sourceTitle(m.sources![idx]))}">[${n}]</button>`
    })
  }
  return html
}

// 属性转义（抽屉标题用）
function escapeAttr(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function openDrawer(m: UiChatMessage, idx: number) {
  drawerSources.value = m.sources ?? []
  drawerIndex.value = idx
  drawerVisible.value = true
}

// 跳转至原文：优先跳转知识库页面，携带 file_id，便于高亮定位
function onJumpToSource(source: ChatSource, _idx: number) {
  const fid = source.file_id
  // 复制片段后提示（由抽屉内按钮处理复制），此处仅处理跳转
  try {
    if (fid != null && String(fid).trim()) {
      router.push({ path: '/admin/knowledge', query: { file_id: String(fid) } }).catch(() => {
        // 路由不存在时降级提示
        naiveMessage.info(`文件 ${fileNameOf(source) || fid} 原文查看（file_id=${fid}）`)
      })
    } else {
      const name = fileNameOf(source)
      if (name) {
        router.push({ path: '/admin/knowledge', query: { file_name: name } }).catch(() => {
          naiveMessage.info(`文件 ${name} 原文查看`)
        })
      } else {
        naiveMessage.info('该来源暂无可跳转的原文标识')
      }
    }
  } catch {
    naiveMessage.info('跳转失败，请在知识库中手动检索文件')
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
  // 消息条数或任一消息内容/思考内容/溯源变化时尝试贴底（用户上滚时由 stickToBottom 暂停）
  () => [
    props.messages.length,
    props.messages.map((m) => `${m.content.length}:${(m.thinkingContent ?? '').length}:${m.thinkingExpanded ? 1 : 0}:${m.sources?.length ?? 0}`).join(','),
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
/* 思考标题前的绿色脉冲点：静态为绿点，流式时带缩放+透明度脉冲动画 */
.thinking-title-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #22c55e;
  flex-shrink: 0;
}
.thinking-title-dot--pulse {
  animation: thinking-pulse 1.2s ease-in-out infinite;
}
@keyframes thinking-pulse {
  0% {
    transform: scale(0.8);
    opacity: 0.6;
  }
  50% {
    transform: scale(1.2);
    opacity: 1;
  }
  100% {
    transform: scale(0.8);
    opacity: 0.6;
  }
}
.thinking-block__title {
  flex-shrink: 0;
}
/* 主标题黑色加粗，副标题灰色细体，避免重复导致的双黑粗标题 */
.thinking-block__title--main {
  color: var(--text-1);
  font-weight: 700;
  font-size: var(--text-xs);
  letter-spacing: 0.04em;
}
/* 思考中标题呼吸渐变：仅在流式活跃时生效，静态时保持原有黑色 */
.thinking-block__title--breathing {
  background: linear-gradient(90deg, var(--text-1) 0%, var(--accent) 45%, var(--text-1) 85%);
  background-size: 200% 100%;
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  animation: thinking-shimmer 2s ease-in-out infinite;
}
/* 呼吸渐变关键帧：背景位置往返移动形成微光扫过效果 */
@keyframes thinking-shimmer {
  0% {
    background-position: 100% 0;
    opacity: 0.92;
  }
  50% {
    background-position: 0% 0;
    opacity: 1;
  }
  100% {
    background-position: 100% 0;
    opacity: 0.92;
  }
}
.thinking-block__subtitle {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  color: var(--text-2);
  font-weight: 400;
  letter-spacing: 0.02em;
  opacity: 0.9;
}
.thinking-block__subtitle::before {
  content: '—';
  margin: 0 6px;
  color: var(--text-3);
}
.thinking-block__hint {
  margin-left: auto;
  font-size: 11px;
  color: var(--text-3);
  font-weight: 400;
}
.thinking-block__placeholder {
  color: var(--text-3);
  font-size: var(--text-xs);
  font-style: italic;
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

/* ---------- 溯源聚合胶囊（ChatGLM 风格紧凑 pill） ---------- */
.source-pill-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: var(--space-3);
}
.source-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 28px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--bg-hover);
  color: var(--text-2);
  font-size: 12px;
  line-height: 1;
  font-weight: 500;
  letter-spacing: 0.02em;
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease),
    border-color var(--dur-fast) var(--ease), transform var(--dur-fast) var(--ease);
}
.source-pill:hover {
  background: var(--bg-elevated);
  border-color: var(--border-strong);
  color: var(--text-1);
  transform: translateY(-1px);
}
.source-pill:active {
  transform: translateY(0);
}

/* 行内角标（正文中的 [1] 替换） */
:deep(.source-badge-inline) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 4px;
  margin: 0 2px;
  border-radius: 999px;
  border: 1px solid var(--accent-border);
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 11px;
  font-family: var(--font-mono);
  font-weight: 700;
  line-height: 1;
  vertical-align: super;
  cursor: pointer;
  transition: all var(--dur-fast) var(--ease);
}
:deep(.source-badge-inline:hover) {
  background: var(--accent);
  color: #0f172a;
}
[data-theme='light'] :deep(.source-badge-inline:hover) {
  color: #fff;
}
</style>
