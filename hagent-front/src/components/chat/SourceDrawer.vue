<template>
  <!-- 遮罩 -->
  <Teleport to="body">
    <Transition name="drawer-fade">
      <div v-if="visible" class="source-drawer__overlay" @click="emit('close')"></div>
    </Transition>
    <Transition name="drawer-slide">
      <div v-if="visible" class="source-drawer" role="dialog" aria-modal="true" aria-label="引用来源">
        <!-- 头部 -->
        <div class="source-drawer__header">
          <div class="source-drawer__title-wrap">
            <h3 class="source-drawer__title">引用来源</h3>
            <span class="source-drawer__count">{{ sources.length }} 条</span>
          </div>
          <button class="source-drawer__close" type="button" aria-label="关闭" @click="emit('close')">×</button>
        </div>

        <!-- 索引切换（当来源多于1条时） -->
        <div v-if="sources.length > 1" class="source-drawer__tabs">
          <button
            v-for="(s, i) in sources"
            :key="tabKey(s, i)"
            class="source-tab"
            :class="{ 'is-active': i === currentIndex }"
            type="button"
            @click="currentIndex = i"
          >
            [{{ i + 1 }}]
          </button>
        </div>

        <!-- 内容区 -->
        <div class="source-drawer__body">
          <div v-if="activeSource" class="source-detail">
            <!-- 文件名行 -->
            <div class="source-detail__row">
              <span class="source-detail__label">文件名</span>
              <span class="source-detail__value mono ellipsis" :title="fileName(activeSource)">{{ fileName(activeSource) || '未知文件' }}</span>
            </div>
            <!-- 分数字行 -->
            <div class="source-detail__row">
              <span class="source-detail__label">相关度</span>
              <span class="source-detail__value">
                <span class="score-badge">{{ formatScore(activeSource.score) }}</span>
              </span>
            </div>
            <!-- 元信息行 -->
            <div v-if="activeSource.file_id || activeSource.chunk_id" class="source-detail__row source-detail__row--meta">
              <span v-if="activeSource.file_id" class="meta-chip">file_id: {{ String(activeSource.file_id) }}</span>
              <span v-if="activeSource.chunk_id" class="meta-chip">chunk: {{ String(activeSource.chunk_id) }}</span>
            </div>

            <!-- 片段 -->
            <div class="source-detail__snippet-wrap">
              <div class="source-detail__snippet-head">
                <span class="source-detail__label">原文片段</span>
                <span class="snippet-actions">
                  <button class="snippet-btn" type="button" @click="onCopySnippet">{{ copyLabel }}</button>
                  <button class="snippet-btn snippet-btn--primary" type="button" @click="onJump">{{ jumpLabel }}</button>
                </span>
              </div>
              <div class="source-snippet" :title="snippetText(activeSource)">{{ snippetText(activeSource) || '暂无片段' }}</div>
            </div>
          </div>
          <div v-else class="source-empty">暂无来源数据</div>

          <!-- 全部列表（底部概览） -->
          <div v-if="sources.length > 1" class="source-list">
            <div class="source-list__title">全部引用</div>
            <button
              v-for="(s, i) in sources"
              :key="listKey(s, i)"
              class="source-list__item"
              :class="{ 'is-active': i === currentIndex }"
              type="button"
              @click="currentIndex = i"
            >
              <span class="source-list__idx">[{{ i + 1 }}]</span>
              <span class="source-list__name ellipsis">{{ fileName(s) || '未知文件' }}</span>
              <span class="source-list__score mono">{{ formatScore(s.score) }}</span>
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ChatSource } from '@/api/chat'

const props = withDefaults(
  defineProps<{
    visible: boolean
    sources: ChatSource[]
    /** 外部指定初始选中索引 */
    activeIndex?: number
  }>(),
  { activeIndex: 0, sources: () => [] }
)

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'jump', source: ChatSource, index: number): void
}>()

// 当前选中索引（内部可切换）
const currentIndex = ref(props.activeIndex)
// 外部 activeIndex 变化时同步
watch(
  () => props.activeIndex,
  (v) => {
    currentIndex.value = Math.max(0, Math.min(v ?? 0, Math.max(0, props.sources.length - 1)))
  }
)
// 显示时重置为传入索引
watch(
  () => props.visible,
  (v) => {
    if (v) currentIndex.value = Math.max(0, Math.min(props.activeIndex ?? 0, Math.max(0, props.sources.length - 1)))
  }
)

const activeSource = computed(() => props.sources[currentIndex.value] ?? null)

// 文件名兼容多种键
function fileName(s: ChatSource): string {
  return String(s.file_name ?? s.fileName ?? '')
}

function snippetText(s: ChatSource): string {
  return String(s.snippet ?? s.content ?? '')
}

function formatScore(v: unknown): string {
  if (v == null || v === '') return '—'
  const n = Number(v)
  if (Number.isNaN(n)) return String(v)
  // 分数归一化展示：4位小数或百分比
  if (n <= 1 && n >= 0) return n.toFixed(4)
  return n.toFixed(2)
}

function tabKey(s: ChatSource, i: number): string {
  return `${String(s.file_id ?? '')}-${String(s.chunk_id ?? '')}-${i}`
}
function listKey(s: ChatSource, i: number): string {
  return `list-${String(s.file_id ?? '')}-${String(s.chunk_id ?? '')}-${i}`
}

// 复制片段
const copyLabel = ref('复制片段')
async function onCopySnippet() {
  const text = activeSource.value ? snippetText(activeSource.value) : ''
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    copyLabel.value = '已复制'
    setTimeout(() => (copyLabel.value = '复制片段'), 1500)
  } catch {
    copyLabel.value = '复制失败'
    setTimeout(() => (copyLabel.value = '复制片段'), 1500)
  }
}

// 跳转至原文（触发外部处理，默认行为由上层决定）
const jumpLabel = ref('查看原文')
function onJump() {
  if (!activeSource.value) return
  emit('jump', activeSource.value, currentIndex.value)
}
</script>

<style scoped>
/* 遮罩 */
.source-drawer__overlay {
  position: fixed;
  inset: 0;
  background: var(--overlay);
  backdrop-filter: blur(2px);
  z-index: 1000;
}

/* 抽屉主体 */
.source-drawer {
  position: fixed;
  top: 0;
  right: 0;
  width: min(440px, 92vw);
  height: 100vh;
  background: var(--bg-elevated);
  border-left: 1px solid var(--border);
  box-shadow: var(--shadow-lg);
  z-index: 1001;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 头部 */
.source-drawer__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4) var(--space-5);
  border-bottom: 1px solid var(--border);
  background: var(--bg-elevated);
  flex-shrink: 0;
}
.source-drawer__title-wrap {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
}
.source-drawer__title {
  font-size: var(--text-base);
  font-weight: 700;
  color: var(--text-1);
  margin: 0;
}
.source-drawer__count {
  font-size: var(--text-xs);
  color: var(--text-3);
  font-family: var(--font-mono);
}
.source-drawer__close {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-2);
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
  transition: all var(--dur-fast) var(--ease);
}
.source-drawer__close:hover {
  color: var(--text-1);
  border-color: var(--border-strong);
  background: var(--bg-hover);
}

/* 索引切换 */
.source-drawer__tabs {
  display: flex;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-5);
  border-bottom: 1px solid var(--border);
  overflow-x: auto;
  flex-shrink: 0;
}
.source-tab {
  min-width: 36px;
  height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--bg);
  color: var(--text-2);
  font-size: var(--text-xs);
  font-family: var(--font-mono);
  cursor: pointer;
  transition: all var(--dur-fast) var(--ease);
  flex-shrink: 0;
}
.source-tab.is-active {
  background: var(--accent);
  color: #0f172a;
  border-color: var(--accent);
  font-weight: 700;
}
[data-theme='light'] .source-tab.is-active {
  color: #fff;
}
.source-tab:hover:not(.is-active) {
  border-color: var(--accent-border);
  color: var(--accent);
}

/* 主体内容 */
.source-drawer__body {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

.source-detail {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.source-detail__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}
.source-detail__row--meta {
  justify-content: flex-start;
  flex-wrap: wrap;
}
.source-detail__label {
  font-size: var(--text-xs);
  color: var(--text-3);
  letter-spacing: 0.04em;
  flex-shrink: 0;
}
.source-detail__value {
  font-size: var(--text-sm);
  color: var(--text-1);
  text-align: right;
  min-width: 0;
}
.score-badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: 999px;
  background: var(--accent-soft);
  border: 1px solid var(--accent-border);
  color: var(--accent);
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  font-weight: 600;
}
.meta-chip {
  font-size: 11px;
  font-family: var(--font-mono);
  color: var(--text-3);
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 2px 8px;
}

/* 片段 */
.source-detail__snippet-wrap {
  margin-top: var(--space-2);
}
.source-detail__snippet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-2);
}
.snippet-actions {
  display: flex;
  gap: var(--space-2);
}
.snippet-btn {
  font-size: var(--text-xs);
  padding: 4px 10px;
  border-radius: 6px;
  border: 1px solid var(--border);
  background: var(--bg);
  color: var(--text-2);
  cursor: pointer;
  transition: all var(--dur-fast) var(--ease);
}
.snippet-btn:hover {
  border-color: var(--border-strong);
  color: var(--text-1);
}
.snippet-btn--primary {
  background: var(--accent);
  color: #0f172a;
  border-color: var(--accent);
  font-weight: 600;
}
[data-theme='light'] .snippet-btn--primary {
  color: #fff;
}
.snippet-btn--primary:hover {
  filter: brightness(1.05);
  color: #0f172a;
}
[data-theme='light'] .snippet-btn--primary:hover {
  color: #fff;
}
.source-snippet {
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius-md);
  background: var(--bg);
  border: 1px solid var(--border);
  color: var(--text-2);
  font-size: var(--text-sm);
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 320px;
  overflow-y: auto;
}

.source-empty {
  padding: var(--space-6) 0;
  text-align: center;
  color: var(--text-3);
  font-size: var(--text-sm);
}

/* 全部引用列表 */
.source-list {
  border-top: 1px solid var(--border);
  padding-top: var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.source-list__title {
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--text-3);
  letter-spacing: 0.04em;
  margin-bottom: var(--space-1);
}
.source-list__item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  width: 100%;
  text-align: left;
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  border: 1px solid transparent;
  background: transparent;
  cursor: pointer;
  transition: all var(--dur-fast) var(--ease);
}
.source-list__item:hover {
  background: var(--bg-hover);
  border-color: var(--border);
}
.source-list__item.is-active {
  background: var(--accent-soft);
  border-color: var(--accent-border);
}
.source-list__idx {
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  color: var(--accent);
  font-weight: 700;
  flex-shrink: 0;
}
.source-list__name {
  flex: 1;
  font-size: var(--text-sm);
  color: var(--text-1);
  min-width: 0;
}
.source-list__score {
  font-size: var(--text-xs);
  color: var(--text-3);
  flex-shrink: 0;
}

/* 过渡 */
.drawer-fade-enter-active,
.drawer-fade-leave-active {
  transition: opacity var(--dur) var(--ease);
}
.drawer-fade-enter-from,
.drawer-fade-leave-to {
  opacity: 0;
}
.drawer-slide-enter-active,
.drawer-slide-leave-active {
  transition: transform var(--dur) var(--ease);
}
.drawer-slide-enter-from,
.drawer-slide-leave-to {
  transform: translateX(100%);
}
</style>
