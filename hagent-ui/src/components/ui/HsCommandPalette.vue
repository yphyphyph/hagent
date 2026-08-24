<template>
  <Teleport to="body">
    <Transition name="cp">
      <div
        v-if="open"
        class="cp-overlay"
        role="dialog"
        aria-modal="true"
        aria-label="HAgent 迷你终端"
        @mousedown.self="close"
      >
        <div class="cp-panel glass-card">
          <div class="cp-input-row">
            <span class="cp-prompt mono">$</span>
            <input
              ref="inputRef"
              v-model="input"
              class="cp-input mono"
              type="text"
              autocomplete="off"
              autocapitalize="off"
              spellcheck="false"
              placeholder="输入命令，help 查看帮助"
              @keydown="onInputKeydown"
            />
            <span class="cp-kbd mono">Esc</span>
          </div>

          <div ref="outputRef" class="cp-output mono">
            <template v-if="output.length === 0">
              <p class="cp-line cp-line--out">
                HAgent 控制台 v2 — 输入 <span class="accent">help</span> 查看全部命令
              </p>
            </template>

            <template v-for="(entry, i) in output" :key="i">
              <p v-if="entry.kind === 'cmd'" class="cp-line cp-line--cmd">{{ entry.text }}</p>
              <p v-else-if="entry.kind === 'out'" class="cp-line cp-line--out">{{ entry.text }}</p>
              <p v-else-if="entry.kind === 'ok'" class="cp-line cp-line--ok">{{ entry.text }}</p>
              <p v-else-if="entry.kind === 'err'" class="cp-line cp-line--err">{{ entry.text }}</p>
              <p v-else-if="entry.kind === 'chat'" class="cp-line cp-line--chat">
                <span class="cp-chat-label">ai &gt;</span>{{ entry.text
                }}<span v-if="entry.streaming" class="cp-caret">▍</span>
              </p>
              <div v-else-if="entry.kind === 'table'" class="cp-line cp-line--table">
                <div v-for="row in entry.rows" :key="row.cmd" class="cp-table__row">
                  <span class="cp-table__cmd accent">{{ row.cmd }}</span>
                  <span class="cp-table__desc text-2">{{ row.desc }}</span>
                </div>
              </div>
              <div v-else-if="entry.kind === 'chips'" class="cp-line cp-line--chips">
                <p v-if="entry.label" class="cp-chips__label">{{ entry.label }}</p>
                <span v-for="item in entry.items" :key="item" class="cp-chip">{{ item }}</span>
              </div>
            </template>
          </div>

          <div class="cp-footer mono">
            <span>Ctrl+K 唤起</span>
            <span>↑/↓ 历史</span>
            <span>Esc 关闭</span>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
/**
 * Ctrl+K 全局迷你终端
 * - Ctrl/Cmd+K 唤起（全局监听），Esc / 点击遮罩关闭
 * - 命令：help / ls / home / projects / tools / about / resume / articles / whoami / skills / chat <问题>
 * - chat 走 streamChat SSE 流式输出（需登录），关闭面板 / 组件卸载时 abort
 * - ↑/↓ 浏览历史命令（组件内维护）
 */
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { site, skillGroups } from '@/data/site'
import { streamChat, PORTAL_CHAT_ENDPOINT } from '@/api/chat'
import { isLoggedIn } from '@/utils/auth'

interface ChatEntry {
  kind: 'chat'
  text: string
  streaming: boolean
}

type OutputEntry =
  | { kind: 'cmd'; text: string }
  | { kind: 'out'; text: string }
  | { kind: 'ok'; text: string }
  | { kind: 'err'; text: string }
  | ChatEntry
  | { kind: 'table'; rows: { cmd: string; desc: string }[] }
  | { kind: 'chips'; label?: string; items: string[] }

const COMMANDS: { cmd: string; desc: string }[] = [
  { cmd: 'help', desc: '显示全部命令及说明' },
  { cmd: 'ls', desc: '列出所有可用命令' },
  { cmd: 'tools', desc: '前往 AI 工具' },
  { cmd: 'knowledge', desc: '前往知识库' },
  { cmd: 'settings', desc: '前往设置' },
  { cmd: 'whoami', desc: '显示当前登录用户信息' },
  { cmd: 'skills', desc: '列出全部技能' },
  { cmd: 'chat <问题>', desc: '向 AI 提问（需登录）' },
]

const ROUTE_MAP: Record<string, string> = {
  tools: '/admin/tools',
  knowledge: '/admin/knowledge',
  settings: '/admin/settings',
}

const router = useRouter()

const open = ref(false)
const input = ref('')
const output = ref<OutputEntry[]>([])
const historyList = ref<string[]>([])
const historyIndex = ref(-1)

const inputRef = ref<HTMLInputElement | null>(null)
const outputRef = ref<HTMLDivElement | null>(null)

let streamEntry: ChatEntry | null = null
let chatAbort: AbortController | null = null

function scrollBottom() {
  nextTick(() => {
    const el = outputRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function openPanel() {
  open.value = true
  input.value = ''
  nextTick(() => inputRef.value?.focus())
  scrollBottom()
}

function close() {
  abortChat()
  open.value = false
}

function abortChat() {
  if (streamEntry) streamEntry.streaming = false
  streamEntry = null
  chatAbort?.abort()
  chatAbort = null
}

/* ---------- 命令执行 ---------- */
function startChat(question: string) {
  abortChat()
  const entry: ChatEntry = { kind: 'chat', text: '', streaming: true }
  output.value.push(entry)
  streamEntry = entry
  chatAbort = streamChat(PORTAL_CHAT_ENDPOINT, question, {
    scene: 1,
    handlers: {
      onToken: (text) => {
        if (streamEntry) streamEntry.text += text
      },
      onDone: () => {
        if (streamEntry) streamEntry.streaming = false
        streamEntry = null
        chatAbort = null
      },
      onError: (msg) => {
        if (streamEntry) streamEntry.streaming = false
        streamEntry = null
        chatAbort = null
        output.value.push({ kind: 'err', text: msg })
      },
    },
  })
  scrollBottom()
}

function execute() {
  const raw = input.value.trim()
  if (!raw) return

  const last = historyList.value[historyList.value.length - 1]
  if (last !== raw) historyList.value.push(raw)
  historyIndex.value = -1
  output.value.push({ kind: 'cmd', text: `$ ${raw}` })
  input.value = ''

  const [cmd = '', ...rest] = raw.split(/\s+/)
  const arg = rest.join(' ').trim()

  if (cmd === 'help') {
    output.value.push({ kind: 'table', rows: COMMANDS })
  } else if (cmd === 'ls') {
    output.value.push({ kind: 'out', text: COMMANDS.map((c) => c.cmd).join('  ') })
  } else if (cmd === 'whoami') {
    output.value.push({ kind: 'ok', text: site.role })
    output.value.push({ kind: 'out', text: site.description })
  } else if (cmd === 'skills') {
    for (const group of skillGroups) {
      output.value.push({ kind: 'chips', label: group.category, items: group.items })
    }
  } else if (cmd === 'chat') {
    if (!arg) {
      output.value.push({ kind: 'out', text: '用法: chat <问题>，例如: chat 介绍一下你自己' })
    } else if (!isLoggedIn()) {
      output.value.push({ kind: 'err', text: '请先登录后再使用 chat 命令' })
    } else {
      startChat(arg)
    }
  } else if (ROUTE_MAP[cmd]) {
    router.push(ROUTE_MAP[cmd])
    close()
  } else {
    output.value.push({ kind: 'err', text: `command not found: ${cmd}` })
  }
  scrollBottom()
}

/* ---------- 历史浏览 ---------- */
function navHistory(dir: -1 | 1) {
  const len = historyList.value.length
  if (len === 0) return
  if (dir === -1) {
    historyIndex.value = historyIndex.value === -1 ? len - 1 : Math.max(0, historyIndex.value - 1)
  } else {
    if (historyIndex.value === -1) return
    historyIndex.value += 1
    if (historyIndex.value >= len) {
      historyIndex.value = -1
      input.value = ''
      return
    }
  }
  input.value = historyList.value[historyIndex.value]
}

function onInputKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter') {
    e.preventDefault()
    execute()
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    navHistory(-1)
  } else if (e.key === 'ArrowDown') {
    e.preventDefault()
    navHistory(1)
  }
}

/* ---------- 全局快捷键 ---------- */
function onGlobalKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    if (open.value) close()
    else openPanel()
  } else if (e.key === 'Escape' && open.value) {
    close()
  }
}

onMounted(() => {
  window.addEventListener('keydown', onGlobalKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onGlobalKeydown)
  abortChat()
})
</script>

<style scoped>
.cp-overlay {
  position: fixed;
  inset: 0;
  z-index: var(--z-palette);
  display: grid;
  place-items: start center;
  padding-top: min(16vh, var(--space-7));
  padding-left: var(--space-4);
  padding-right: var(--space-4);
  background: var(--overlay);
}

.cp-panel {
  width: min(560px, 90vw);
  max-height: min(440px, 70vh);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: var(--shadow-lg);
}

.cp-input-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-4);
  border-bottom: 1px solid var(--border);
}

.cp-prompt {
  color: var(--accent);
  font-weight: 700;
  font-size: var(--text-md);
  line-height: 1;
}

.cp-input {
  flex: 1;
  min-width: 0;
  background: transparent;
  border: none;
  outline: none;
  color: var(--text-1);
  font-size: var(--text-base);
  caret-color: var(--accent);
}
.cp-input::placeholder {
  color: var(--text-3);
}

.cp-kbd {
  flex-shrink: 0;
  color: var(--text-3);
  font-size: var(--text-xs);
  padding: 1px 7px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
}

.cp-output {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-4);
  font-size: var(--text-sm);
  line-height: 1.75;
}

.cp-line {
  margin-bottom: var(--space-2);
  white-space: pre-wrap;
  word-break: break-word;
}
.cp-line--cmd {
  color: var(--text-3);
}
.cp-line--out {
  color: var(--text-2);
}
.cp-line--ok {
  color: var(--accent);
}
.cp-line--err {
  color: var(--danger);
}
.cp-line--chat {
  color: var(--text-1);
}
.cp-chat-label {
  color: var(--accent);
  margin-right: var(--space-2);
}
.cp-caret {
  color: var(--accent);
  animation: blink-caret 1s steps(1) infinite;
}

.cp-table__row {
  display: grid;
  grid-template-columns: minmax(120px, 40%) 1fr;
  gap: var(--space-3);
  padding: 2px 0;
}
.cp-table__cmd {
  font-weight: 600;
}
.cp-table__desc {
  overflow-wrap: break-word;
}

.cp-chips__label {
  color: var(--text-3);
  margin-bottom: var(--space-1);
}
.cp-chip {
  display: inline-block;
  margin: 0 var(--space-2) var(--space-2) 0;
  padding: 2px 10px;
  border-radius: var(--radius-full);
  border: 1px solid var(--accent-border);
  background: var(--accent-soft);
  color: var(--accent);
  font-size: var(--text-xs);
}

.cp-footer {
  display: flex;
  gap: var(--space-4);
  padding: var(--space-2) var(--space-4);
  border-top: 1px solid var(--border);
  color: var(--text-3);
  font-size: var(--text-xs);
}

.cp-enter-active,
.cp-leave-active {
  transition: opacity var(--dur) var(--ease), transform var(--dur) var(--ease);
}
.cp-enter-from,
.cp-leave-to {
  opacity: 0;
  transform: translateY(-8px) scale(0.98);
}
</style>
