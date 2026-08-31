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
          :disabled="!modelValue?.trim()"
          title="发送"
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
</template>

<script setup lang="ts">
import { ref } from 'vue'

defineProps<{
  modelValue?: string
  streaming?: boolean
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
}
</style>
