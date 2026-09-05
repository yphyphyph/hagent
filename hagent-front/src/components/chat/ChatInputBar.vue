<template>
  <div class="input-bar">
    <div class="input-bar__box">
      <textarea
        ref="textareaRef"
        :value="modelValue"
        class="input-bar__textarea"
        rows="1"
        placeholder="输入消息，Enter 发送，Shift+Enter 换行"
        @compositionstart="composing = true"
        @compositionend="composing = false"
        @keydown.enter.exact.prevent="onEnter"
        @input="onInput"
        @focus="emit('focus')"
      ></textarea>
      <div class="input-bar__actions">
        <span class="input-bar__hint">
          <kbd>Enter</kbd> 发送 · <kbd>Shift</kbd>+<kbd>Enter</kbd> 换行
        </span>
        <div class="input-bar__send-zone">
          <!-- 输入长度计数器：码点长度超 8000 显示，超 10000 变红，不挤占发送区 -->
          <span
            v-if="inputLen > 8000"
            class="input-len-counter"
            :class="{ 'is-over': inputLen > 10000 }"
          >{{ inputLen }}/10000</span>
          <span
            v-if="inputLen > 10000"
            class="input-over-tip"
            role="alert"
          >输入字符已达上限，最多支持 10000 字符</span>
          <!-- 图标锚定三件套：环形图标 + 小 pill + 详情卡（空数据整体不渲染） -->
          <div
            v-if="contextUsage"
            ref="tokenWrapRef"
            class="token-wrap"
            @mouseenter="onWrapEnter"
            @mouseleave="onWrapLeave"
          >
            <span
              class="token-icon-wrap"
              :class="usageLevel"
              :title="`上下文已使用 ${contextUsage.percent}%`"
              role="button"
              tabindex="0"
              @click.stop="toggleDetail"
              @keydown.enter.stop.prevent="toggleDetail"
              @keydown.space.stop.prevent="toggleDetail"
            >
              <svg width="22" height="22" viewBox="0 0 22 22" aria-hidden="true">
                <circle cx="11" cy="11" r="9" fill="none" stroke="#e2e8f0" :stroke-width="2.5" />
                <circle
                  cx="11" cy="11" r="9" fill="none"
                  stroke="currentColor" :stroke-width="2.5" stroke-linecap="round"
                  :stroke-dasharray="ringDash"
                  transform="rotate(-90 11 11)"
                />
                <text x="11" y="11" text-anchor="middle" dominant-baseline="central" font-size="9">{{ contextUsage.percent }}</text>
              </svg>
            </span>
            <transition name="pill-fade">
              <div
                v-if="showTokenPill && !showDetail && contextUsage"
                class="context-pill"
                :class="usageLevel"
                role="status"
              >
                {{ contextUsage.percent }}% · {{ formatK(contextUsage.promptTokens) }} / {{ formatK(contextUsage.contextWindow) }} 上下文已使用
                <span v-if="isActual" class="stage-badge is-actual">实际</span>
              </div>
            </transition>
            <div
              v-if="showDetail && contextUsage"
              class="context-detail-card"
              @click.stop
            >
              <div class="context-detail-card__head">
                <span class="context-detail-card__title">上下文用量</span>
                <button type="button" class="context-detail-card__close" aria-label="关闭" @click.stop="closeDetail">×</button>
              </div>
              <div class="detail-total">
                <span class="detail-total__percent">{{ contextUsage.percent }}%</span>
                <span v-if="isActual" class="stage-badge is-actual">实际</span>
                <span class="detail-total__text">已使用 {{ formatK(contextUsage.promptTokens) }}/{{ formatK(contextUsage.contextWindow) }}</span>
              </div>
              <!-- stage=actual 时总量为 usage 回执实际值，分段仍为估算构成（与总量存在小差额） -->
              <div v-if="isActual" class="detail-bar-note">分段为估算构成</div>
              <div class="detail-bar">
                <span class="seg seg--system" :style="{ width: `${segPct.system}%` }"></span>
                <span class="seg seg--history" :style="{ width: `${segPct.history}%` }"></span>
                <span class="seg seg--rag" :style="{ width: `${segPct.rag}%` }"></span>
                <span class="seg seg--summary" :style="{ width: `${segPct.summary}%` }"></span>
              </div>
              <div class="context-detail-card__row"><span><i class="dot dot--system"></i>系统提示词</span><span>{{ formatNum(bd.system) }} · {{ segPct.system }}%</span></div>
              <div class="context-detail-card__row"><span><i class="dot dot--history"></i>对话消息</span><span>{{ formatNum(bd.history) }} · {{ segPct.history }}%</span></div>
              <div class="context-detail-card__row"><span><i class="dot dot--rag"></i>RAG 检索</span><span>{{ formatNum(bd.rag) }} · {{ segPct.rag }}%</span></div>
              <div class="context-detail-card__row"><span><i class="dot dot--summary"></i>摘要</span><span>{{ formatNum(bd.summary) }} · {{ segPct.summary }}%</span></div>
              <!-- 上轮实际仅参考，不参与环形圈与 detail-total（预估口径） -->
              <div class="context-detail-card__row"><span>上轮实际</span><span>{{ formatNum(contextUsage.actualPromptTokens ?? 0) }}（仅参考）</span></div>
              <div class="context-detail-card__foot">更新于 {{ updatedText }}</div>
            </div>
          </div>
          <!-- 三态：流式中=停止（红），有内容=发送（accent），空=禁用 -->
        <button
          v-if="streaming"
          type="button"
          class="send-btn send-btn--stop"
          title="AI 正在生成，点击停止"
          aria-label="AI 正在生成，点击停止"
          @click="$emit('stop')"
        >
          <span class="stop-square"></span>
        </button>
        <button
          v-else
          type="button"
          class="send-btn"
          :disabled="!modelValue?.trim() || inputLen > 10000"
          :title="inputLen > 10000 ? '输入已超限，请删减后发送' : '发送'"
          @click="$emit('send')"
        >
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M22 2 11 13" />
            <path d="M22 2 15 22l-4-9-9-4z" />
          </svg>
        </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import type { ContextUsage } from '@/composables/useChat'

const props = defineProps<{
  modelValue?: string
  streaming?: boolean
  /** 上下文用量快照（来自 useChat.contextUsage，无数据时不展示入口） */
  contextUsage?: ContextUsage | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: string): void
  (e: 'send'): void
  (e: 'stop'): void
  (e: 'focus'): void
}>()

const textareaRef = ref<HTMLTextAreaElement | null>(null)
/** 中文输入法组词守卫：组词期间的 Enter 是选词，不触发发送 */
const composing = ref(false)
/** 图标悬浮状态：控制 context-pill 显隐（平时仅留环形进度入口） */
const showTokenPill = ref(false)
/** 详情卡显隐：点击图标切换 */
const showDetail = ref(false)
/** 图标锚定容器：供点击空白关闭时判定内外 */
const tokenWrapRef = ref<HTMLElement | null>(null)
/** hover 离开宽限计时器：pill 与图标之间移动不灭 */
let hideTimer: number | undefined

function onWrapEnter() {
  if (hideTimer !== undefined) {
    window.clearTimeout(hideTimer)
    hideTimer = undefined
  }
  showTokenPill.value = true
}

function onWrapLeave() {
  if (hideTimer !== undefined) window.clearTimeout(hideTimer)
  hideTimer = window.setTimeout(() => {
    showTokenPill.value = false
    hideTimer = undefined
  }, 250)
}

function toggleDetail() {
  showDetail.value = !showDetail.value
}

function closeDetail() {
  showDetail.value = false
}

/** 点击卡片外关闭：仅详情卡打开时生效 */
function onDocumentClick(e: MouseEvent) {
  if (!showDetail.value) return
  const el = tokenWrapRef.value
  if (el && e.target instanceof Node && el.contains(e.target)) return
  showDetail.value = false
}

/** Esc 关闭详情卡 */
function onDocumentKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && showDetail.value) showDetail.value = false
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick)
  document.addEventListener('keydown', onDocumentKeydown)
})

onUnmounted(() => {
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('keydown', onDocumentKeydown)
  if (hideTimer !== undefined) window.clearTimeout(hideTimer)
})

/** 输入码点长度（中文 emoji 准确计数） */
const inputLen = computed(() => [...(props.modelValue ?? '')].length)
/** 用量等级：70% 黄 90% 红 */
const usageLevel = computed(() => {
  const p = props.contextUsage?.percent ?? 0
  if (p >= 90) return 'is-danger'
  if (p >= 70) return 'is-warn'
  return ''
})
/** 数值口径：actual=usage 回执实际值 / estimated=流中估算（缺省视为估算，兼容旧事件）；估算不显示徽标，实际显示绿色「实际」 */
const isActual = computed(() => props.contextUsage?.stage === 'actual')
/** 环形进度：C=2πr，r=9，弧长随 percent */
const RING_C = 2 * Math.PI * 9
const ringDash = computed(() => {
  const p = props.contextUsage?.percent ?? 0
  return `${((RING_C * p) / 100).toFixed(1)} ${RING_C.toFixed(1)}`
})
/** 明细四段（缺失置 0） */
const bd = computed(() => ({
  history: props.contextUsage?.breakdown.history ?? 0,
  summary: props.contextUsage?.breakdown.summary ?? 0,
  rag: props.contextUsage?.breakdown.rag ?? 0,
  system: props.contextUsage?.breakdown.system ?? 0,
}))
/** 分段占比：总数为 0 时显示 0% 防除零 */
const segPct = computed(() => {
  const total = props.contextUsage?.promptTokens ?? 0
  if (!total || total <= 0) return { system: 0, history: 0, rag: 0, summary: 0 }
  const pct = (v: number) => Math.min(100, Math.max(0, Math.round((v / total) * 100)))
  return { system: pct(bd.value.system), history: pct(bd.value.history), rag: pct(bd.value.rag), summary: pct(bd.value.summary) }
})
/** 快照更新时间（HH:mm:ss） */
const updatedText = computed(() => {
  const ts = props.contextUsage?.updatedAt
  if (!ts) return '--'
  const d = new Date(ts)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
})

/** token 数转 K（保留 1 位小数） */
function formatK(n: number): string {
  return `${(n / 1000).toFixed(1)}K`
}
/** 明细数字千分位 */
function formatNum(n: number): string {
  return n.toLocaleString('en-US')
}

function onEnter() {
  if (composing.value) return
  emit('send')
}

/** v-model 桥接 + 高度自适应 */
function onInput(e: Event) {
  const el = e.target as HTMLTextAreaElement
  emit('update:modelValue', el.value)
  autoResize(e)
}

function autoResize(e: Event) {
  const el = e.target as HTMLTextAreaElement
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 160)}px`
}

defineExpose({ focus: () => textareaRef.value?.focus() })
</script>

<style scoped>
.input-bar__box {
  position: relative;
  max-width: 840px;
  margin: 0 auto;
  background: var(--bg-elevated);
  border: 1px solid var(--border-strong);
  border-radius: 16px;
  padding: var(--space-3) var(--space-3) var(--space-2) var(--space-4);
  transition: border-color var(--dur-fast) var(--ease), box-shadow var(--dur-fast) var(--ease);
}
.input-bar__box:focus-within {
  border-color: var(--accent-border);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.06), 0 0 0 3px var(--accent-soft);
}
.input-bar__textarea {
  display: block;
  width: 100%;
  resize: none;
  border: none;
  outline: none;
  background: transparent;
  color: var(--text-1);
  font-size: 15px;
  line-height: 1.6;
  font-family: inherit;
  min-height: 44px;
  max-height: 160px;
}
.input-bar__textarea::placeholder {
  color: var(--text-3);
}
.input-bar__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: var(--space-1);
}
.input-bar__hint {
  font-size: 11px;
  color: var(--text-3);
}
.input-bar__hint kbd {
  font-family: var(--font-mono);
  background: var(--bg-hover);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 0 5px;
}
/* 发送区定位锚点：轻量入口 absolute 不占布局，发送按钮位置不受影响 */
.input-bar__send-zone {
  position: relative;
  display: flex;
  align-items: center;
}
/* 图标锚定容器：发送区内相对定位，三件套的定位基准 */
.token-wrap {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-right: 8px;
}
/* 轻量入口：环形进度图标（70% 黄 90% 红 by usageLevel），点击切换详情卡 */
.token-icon-wrap {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  color: var(--accent);
  cursor: pointer;
  outline: none;
}
.token-icon-wrap svg text {
  fill: currentColor;
  font-size: 9px;
  line-height: 1;
}
.token-icon-wrap.is-warn {
  color: #b45309;
}
.token-icon-wrap.is-danger {
  color: #dc2626;
}
/* hover 小 pill：锚定图标正上方，不再居中横跨输入框 */
.context-pill {
  position: absolute;
  bottom: calc(100% + 8px);
  right: 0;
  white-space: nowrap;
  z-index: 6;
  background: #fff;
  color: #333;
  font-size: 12px;
  line-height: 1.5;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(15, 23, 42, 0.12);
  border: 1px solid var(--border);
  padding: 6px 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  user-select: none;
}
.context-pill.is-warn {
  color: #b45309;
}
.context-pill.is-danger {
  color: #dc2626;
}
[data-theme='dark'] .context-pill {
  background: #1e293b;
  color: #e2e8f0;
  border-color: rgba(148, 163, 184, 0.2);
}
[data-theme='dark'] .context-pill.is-warn {
  color: #fbbf24;
}
[data-theme='dark'] .context-pill.is-danger {
  color: #f87171;
}
/* 详情卡：自制浮层，锚定图标正上方 */
.context-detail-card {
  position: absolute;
  bottom: calc(100% + 8px);
  right: 0;
  width: 264px;
  z-index: 7;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  box-shadow: 0 12px 32px rgba(15, 23, 42, 0.18);
  padding: 12px 14px;
  font-size: 12px;
  color: var(--text-1);
}
[data-theme='dark'] .context-detail-card {
  background: #1e293b;
  border-color: rgba(148, 163, 184, 0.2);
  color: #e2e8f0;
}
.context-detail-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.context-detail-card__title {
  font-size: 13px;
  font-weight: 600;
}
.context-detail-card__close {
  border: none;
  background: transparent;
  color: var(--text-3);
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
  padding: 2px 6px;
  border-radius: 6px;
}
.context-detail-card__close:hover {
  background: var(--bg-hover);
  color: var(--text-1);
}
.detail-total {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}
.detail-total__percent {
  font-size: 22px;
  font-weight: 700;
  line-height: 1;
}
.detail-total__text {
  font-size: 12px;
  color: var(--text-3);
}
/* 口径徽标：仅 stage=actual 时显示绿色「实际」（估算态不显示，保持默认干净），pill 与详情卡共用 */
.stage-badge {
  display: inline-block;
  font-size: 10px;
  line-height: 1;
  padding: 3px 6px;
  border-radius: 999px;
  border: 1px solid var(--border);
  color: var(--text-3);
  background: transparent;
  vertical-align: middle;
  margin-left: 6px;
  white-space: nowrap;
  user-select: none;
}
.stage-badge.is-actual {
  color: #059669;
  border-color: rgba(5, 150, 105, 0.35);
  background: rgba(5, 150, 105, 0.06);
}
[data-theme='dark'] .stage-badge.is-actual {
  color: #34d399;
  border-color: rgba(52, 211, 153, 0.35);
  background: rgba(52, 211, 153, 0.1);
}
/* stage=actual 时分段提示（总量为实际值，分段为估算构成） */
.detail-bar-note {
  font-size: 11px;
  color: var(--text-3);
  margin-bottom: 6px;
}
.detail-bar {
  display: flex;
  height: 6px;
  border-radius: 999px;
  overflow: hidden;
  background: #eef2f7;
  margin-bottom: 10px;
}
[data-theme='dark'] .detail-bar {
  background: rgba(148, 163, 184, 0.2);
}
.detail-bar .seg {
  height: 100%;
  min-width: 0;
}
.context-detail-card__bar {
  display: flex;
  height: 8px;
  border-radius: 4px;
  overflow: hidden;
  background: var(--bg-hover);
  margin-bottom: 10px;
}
.context-detail-card__bar .seg {
  height: 100%;
  min-width: 0;
}
.seg--system { background: #6366f1; }
.seg--history { background: #22c55e; }
.seg--rag { background: #f59e0b; }
.seg--summary { background: #a78bfa; }
.context-detail-card__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 3px 0;
}
.context-detail-card__row .dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
}
.dot--system { background: #3b82f6; }
.dot--history { background: #22c55e; }
.dot--rag { background: #f59e0b; }
.dot--summary { background: #a855f7; }
.context-detail-card__foot {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--border);
  color: var(--text-3);
  font-size: 11px;
}
/* pill 显隐过渡 */
.pill-fade-enter-active,
.pill-fade-leave-active {
  transition: opacity 0.2s ease;
}
.pill-fade-enter-from,
  .pill-fade-leave-to {
  opacity: 0;
}
/* 输入长度计数器：发送区旁轻量展示，超限变红 */
.input-len-counter {
  margin-right: 8px;
  font-size: 11px;
  color: var(--text-3);
  white-space: nowrap;
  user-select: none;
}
.input-len-counter.is-over {
  color: #dc2626;
}
[data-theme='dark'] .input-len-counter.is-over {
  color: #f87171;
}
/* 超限红色小字：计数器旁轻量提示，不挤占发送按钮布局 */
.input-over-tip {
  margin-right: 8px;
  font-size: 12px;
  line-height: 1.5;
  color: #ef4444;
  white-space: nowrap;
  user-select: none;
}
[data-theme='dark'] .input-over-tip {
  color: #f87171;
}
.send-btn {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: none;
  background: var(--accent);
  color: #fff;
  cursor: pointer;
  transition: opacity var(--dur-fast) var(--ease), transform var(--dur-fast) var(--ease),
    background var(--dur-fast) var(--ease);
}
[data-theme='dark'] .send-btn {
  color: #0f172a;
}
.send-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
.send-btn:not(:disabled):hover {
  transform: translateY(-1px);
}
/* 停止态：淡粉圆底 + 红色方块 + streaming 脉冲（仅 transform/opacity/box-shadow，无布局抖动） */
.send-btn--stop {
  background: rgba(254, 205, 211, 0.9);
  box-shadow: 0 0 0 0 rgba(239, 68, 68, 0.3);
  animation: streaming-ring 1.8s cubic-bezier(0.4, 0, 0.6, 1) infinite;
}
[data-theme='dark'] .send-btn--stop {
  color: #ef4444 !important;
}
.send-btn--stop:hover {
  transform: none;
  background: #fecaca;
}
.stop-square {
  width: 12px;
  height: 12px;
  border-radius: 2px;
  background: #ef4444;
  animation: streaming-pulse 1.4s ease-in-out infinite;
  will-change: transform, opacity;
}

@keyframes streaming-pulse {
  0% {
    transform: scale(1);
    opacity: 1;
  }
  50% {
    transform: scale(1.08);
    opacity: 0.92;
  }
  100% {
    transform: scale(1);
    opacity: 1;
  }
}

@keyframes streaming-ring {
  0% {
    box-shadow: 0 0 0 0 rgba(239, 68, 68, 0.3);
  }
  70% {
    box-shadow: 0 0 0 10px rgba(239, 68, 68, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(239, 68, 68, 0);
  }
}

/* 尊重减少动效偏好：降级为静态 */
@media (prefers-reduced-motion: reduce) {
  .send-btn--stop,
  .stop-square {
    animation: none;
  }
  .pill-fade-enter-active,
  .pill-fade-leave-active {
    transition: none;
  }
}
</style>
