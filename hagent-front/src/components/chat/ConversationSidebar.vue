<template>
  <aside class="conv-side">
    <!-- 品牌区 -->
    <router-link to="/" class="conv-brand">
      <span class="conv-brand__mark">H</span>
      <span class="conv-brand__meta">
        <span class="conv-brand__name">HAgent</span>
        <span class="conv-brand__tag">Agentic AI 平台</span>
      </span>
    </router-link>

    <!-- 顶部：新建 + 搜索 -->
    <div class="conv-side__head">
      <button class="new-btn" type="button" @click="$emit('new')">
        <span class="new-btn__plus">+</span> 新建会话
      </button>
      <input v-model="keyword" class="search-input" type="text" placeholder="搜索会话…" />
    </div>

    <div class="conv-scroll">
      <p v-if="loading" class="conv-tip">加载中…</p>
      <p v-else-if="filtered.length === 0" class="conv-tip">
        {{ keyword ? '没有匹配的会话' : '暂无历史会话' }}
      </p>

      <div v-for="group in groups" :key="group.label" class="conv-group">
        <p class="conv-group__label">{{ group.label }}</p>
        <button
          v-for="c in group.items"
          :key="c.id"
          type="button"
          class="conv-item"
          :class="{ 'conv-item--active': c.id === activeId, 'conv-item--editing': editingId === c.id }"
          @click="onSelect(c)"
        >
          <template v-if="editingId === c.id">
            <input
              ref="renameInputRef"
              v-model="editingTitle"
              class="rename-input"
              type="text"
              @click.stop
              @keydown.enter.prevent="commitRename(c)"
              @keydown.esc.prevent="cancelRename"
              @blur="commitRename(c)"
            />
          </template>
          <template v-else>
            <span class="conv-item__title">{{ c.title || '未命名会话' }}</span>
            <span v-if="c.id < 0" class="demo-badge">演示</span>
            <span v-if="!guest" class="conv-item__ops" @click.stop>
              <span class="op op--rename" title="重命名" @click="startRename(c)">✎</span>
              <span class="op op--del" title="删除" @click="onDelete(c)">
                {{ deletingId === c.id ? '确认?' : '✕' }}
              </span>
            </span>
          </template>
        </button>
      </div>
    </div>

    <!-- 访客提示 -->
    <div v-if="guest" class="conv-guest">
      <p>访客模式下对话不会被保存</p>
      <button class="conv-guest__login" @click="$emit('login')">登录后可新建会话并保存历史</button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { Conversation } from '@/api/chat'

const props = defineProps<{
  conversations: Conversation[]
  activeId?: number
  loading?: boolean
  guest?: boolean
}>()

const emit = defineEmits<{
  (e: 'select', c: Conversation): void
  (e: 'new'): void
  (e: 'delete', id: number): void
  (e: 'rename', id: number, title: string): void
  (e: 'login'): void
}>()

/* ---------- 搜索过滤 ---------- */
const keyword = ref('')
const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return props.conversations
  return props.conversations.filter((c) => (c.title ?? '').toLowerCase().includes(k))
})

/* ---------- 时间分组（复用 useChat 的分组逻辑） ---------- */
const groups = computed(() => groupByTime(filtered.value))

function groupByTime(list: Conversation[]): Array<{ label: string; items: Conversation[] }> {
  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const dayMs = 86400000
  const groups = [
    { label: '今天', maxDays: 1, items: [] as Conversation[] },
    { label: '7 天内', maxDays: 7, items: [] as Conversation[] },
    { label: '30 天内', maxDays: 30, items: [] as Conversation[] },
    { label: '更早', maxDays: Infinity, items: [] as Conversation[] },
  ]
  for (const c of list) {
    const t = c.updatedAt || c.createdAt
    const ts = t ? new Date(t).getTime() : 0
    const days = Number.isNaN(ts) || ts === 0 ? Infinity : Math.max(0, Math.ceil((startOfToday - ts) / dayMs))
    groups.find((g) => days < g.maxDays)?.items.push(c)
  }
  return groups.filter((g) => g.items.length > 0)
}

/* ---------- 行内重命名 ---------- */
const editingId = ref<number | null>(null)
const editingTitle = ref('')

function startRename(c: Conversation) {
  editingId.value = c.id
  editingTitle.value = c.title ?? ''
  void nextTick(() => {
    const inputs = document.querySelectorAll<HTMLInputElement>('.rename-input')
    inputs[inputs.length - 1]?.focus()
  })
}

function commitRename(c: Conversation) {
  if (editingId.value !== c.id) return
  const title = editingTitle.value.trim()
  if (title && title !== (c.title ?? '')) {
    emit('rename', c.id, title)
  }
  cancelRename()
}

function cancelRename() {
  editingId.value = null
  editingTitle.value = ''
}

/** 选择会话（重命名中不触发切换） */
function onSelect(c: Conversation) {
  if (editingId.value === c.id) return
  emit('select', c)
}

/** 两步删除确认：第一次点变“确认?”，再次点击才真正删除 */
function onDelete(c: Conversation) {
  if (deletingId.value === c.id) {
    emit('delete', c.id)
    deletingId.value = null
  } else {
    deletingId.value = c.id
  }
}

/* ---------- 两步删除确认 ---------- */
const deletingId = ref<number | null>(null)
let delTimer: ReturnType<typeof setTimeout> | null = null

// 模板中直接 $emit('delete') 前做两步确认：第一次点变“确认?”，3秒未确认自动复位
watch(deletingId, (v) => {
  if (delTimer) clearTimeout(delTimer)
  if (v !== null) {
    delTimer = setTimeout(() => (deletingId.value = null), 3000)
  }
})
</script>

<style scoped>
.conv-side {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

/* ---------- 品牌区 ---------- */
.conv-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 16px 12px;
  text-decoration: none;
}
.conv-brand__mark {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  background: var(--accent);
  color: #fff;
  font-family: var(--font-mono);
  font-weight: 800;
  font-size: 17px;
  border-radius: 9px;
  flex-shrink: 0;
}
[data-theme='dark'] .conv-brand__mark {
  color: #0f172a;
}
.conv-brand__meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.conv-brand__name {
  font-family: var(--font-display), 'Noto Serif SC', serif;
  font-weight: 700;
  font-size: 17px;
  color: var(--text-1);
  line-height: 1.2;
}
.conv-brand__tag {
  font-size: 11px;
  color: var(--text-3);
  letter-spacing: 0.04em;
}

.conv-side__head {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-4);
  border-bottom: 1px solid var(--border);
}
.new-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 38px;
  border-radius: 10px;
  border: 1px solid var(--border-strong);
  background: transparent;
  color: var(--text-1);
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease);
}
.new-btn:hover {
  background: var(--bg-hover);
  border-color: var(--accent-border);
}
.new-btn__plus {
  font-size: 16px;
  color: var(--accent);
}
.search-input {
  height: 32px;
  padding: 0 10px;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: var(--bg);
  color: var(--text-1);
  font-size: var(--text-xs);
  outline: none;
  transition: border-color var(--dur-fast) var(--ease);
}
.search-input:focus {
  border-color: var(--accent-border);
}

.conv-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--space-3);
}
.conv-tip {
  text-align: center;
  color: var(--text-3);
  font-size: var(--text-xs);
  padding: var(--space-6) 0;
}
.conv-group + .conv-group {
  margin-top: var(--space-4);
}
.conv-group__label {
  margin: 0 0 var(--space-1);
  padding-left: var(--space-3);
  font-size: 11px;
  letter-spacing: 0.08em;
  color: var(--text-3);
}

.conv-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  width: 100%;
  min-height: 38px;
  padding: var(--space-2) var(--space-3);
  border: none;
  border-radius: var(--radius-md);
  background: transparent;
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease);
}
.conv-item:hover {
  background: var(--bg-hover);
}
.conv-item--active {
  background: var(--accent-soft);
}
.conv-item__title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: left;
  font-size: var(--text-sm);
  color: var(--text-1);
}
.conv-item__ops {
  display: flex;
  gap: 4px;
  opacity: 0;
  transition: opacity var(--dur-fast) var(--ease);
}
.conv-item:hover .conv-item__ops,
.conv-item--editing .conv-item__ops {
  opacity: 1;
}
.op {
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border-radius: 5px;
  font-size: 12px;
  color: var(--text-3);
  cursor: pointer;
}
.op:hover {
  background: var(--bg-elevated);
  color: var(--text-1);
}
.op--del:hover {
  color: #ef4444;
}
.rename-input {
  flex: 1;
  min-width: 0;
  height: 28px;
  padding: 0 8px;
  border-radius: 6px;
  border: 1px solid var(--accent-border);
  background: var(--bg);
  color: var(--text-1);
  font-size: var(--text-sm);
  outline: none;
}

.conv-guest {
  padding: var(--space-4);
  border-top: 1px solid var(--border);
  font-size: var(--text-xs);
  color: var(--text-3);
}
.conv-guest p {
  margin: 0 0 4px;
}
.conv-guest a {
  color: var(--accent);
  text-decoration: none;
}
.demo-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 18px;
  padding: 0 6px;
  border-radius: 999px;
  background: #dcfce7;
  color: #15803d;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  flex-shrink: 0;
  border: 1px solid #bbf7d0;
}
.conv-guest__login {
  display: inline-flex;
  align-items: center;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--accent);
  font-size: var(--text-xs);
  font-weight: 600;
  cursor: pointer;
  text-decoration: none;
}
.conv-guest__login:hover {
  text-decoration: underline;
}
</style>
