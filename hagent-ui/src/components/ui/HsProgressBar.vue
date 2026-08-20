<template>
  <div class="hs-progress" :class="{ 'is-active': active }" aria-hidden="true">
    <div class="hs-progress__bar" :style="{ width: `${width}%` }"></div>
  </div>
</template>

<script setup lang="ts">
/**
 * 顶部路由进度条
 * - router/index.ts 在 beforeEach/afterEach/onError 派发 hs:route-start / hs:route-end
 * - 无 end 事件时 10s 兜底自动收尾（防止 aborted 导航卡住）
 */
import { onMounted, onUnmounted, ref } from 'vue'

const active = ref(false)
const width = ref(0)

let raf = 0
let endTimer: ReturnType<typeof setTimeout> | null = null
let safetyTimer: ReturnType<typeof setTimeout> | null = null

function start() {
  if (endTimer) {
    clearTimeout(endTimer)
    endTimer = null
  }
  if (safetyTimer) {
    clearTimeout(safetyTimer)
    safetyTimer = null
  }
  active.value = true
  width.value = 10
  cancelAnimationFrame(raf)
  raf = requestAnimationFrame(function grow() {
    width.value = Math.min(82, width.value + (82 - width.value) * 0.12)
    raf = requestAnimationFrame(grow)
  })
  safetyTimer = setTimeout(finish, 10000)
}

function finish() {
  if (!active.value) return
  cancelAnimationFrame(raf)
  width.value = 100
  endTimer = setTimeout(() => {
    active.value = false
    width.value = 0
  }, 280)
}

onMounted(() => {
  window.addEventListener('hs:route-start', start)
  window.addEventListener('hs:route-end', finish)
})

onUnmounted(() => {
  window.removeEventListener('hs:route-start', start)
  window.removeEventListener('hs:route-end', finish)
  cancelAnimationFrame(raf)
  if (endTimer) clearTimeout(endTimer)
  if (safetyTimer) clearTimeout(safetyTimer)
})
</script>

<style scoped>
.hs-progress {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  z-index: var(--z-progress);
  pointer-events: none;
  opacity: 0;
  transition: opacity var(--dur) var(--ease);
}
.hs-progress.is-active {
  opacity: 1;
}
.hs-progress__bar {
  height: 100%;
  width: 0;
  border-radius: var(--radius-full);
  background: linear-gradient(90deg, var(--accent), var(--accent-hover));
  box-shadow: 0 0 8px var(--accent-soft), 0 0 16px var(--accent-soft);
}
</style>
