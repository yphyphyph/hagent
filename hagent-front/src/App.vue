<template>
  <HsBackground />
  <HsParticles />
  <HsProgressBar />
  <HsCommandPalette />

  <!-- Konami CRT 扫描线叠加层：默认 display:none，useKonami 激活后由 [data-easter] 驱动显示 -->
  <div class="hs-crt-overlay" aria-hidden="true"></div>

  <!-- 首屏 Preloader：logo 呼吸 600ms 后淡出 -->
  <Transition name="fade">
    <div v-if="showPreloader" class="hs-preloader" aria-hidden="true">
      <span class="hs-preloader__mark">H</span>
    </div>
  </Transition>

  <n-config-provider
    :theme="naiveTheme"
    :theme-overrides="naiveOverrides"
    :locale="zhCN"
    :date-locale="dateZhCN"
  >
    <n-message-provider placement="top">
      <NotifyBridge />
      <router-view v-slot="{ Component }">
        <!-- View Transitions API 可用时用原生过渡，否则用 Vue <Transition> -->
        <Transition v-if="!useVTA" name="page" mode="out-in">
          <component :is="Component" />
        </Transition>
        <component v-else :is="Component" />
      </router-view>
    </n-message-provider>
  </n-config-provider>
</template>

<script setup lang="ts">
import { computed, h, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  darkTheme,
  lightTheme,
  NConfigProvider,
  NMessageProvider,
  useMessage,
  type GlobalThemeOverrides,
} from 'naive-ui'
import { zhCN, dateZhCN } from 'naive-ui'
import { useThemeStore } from '@/stores/theme'
import { getNaiveOverrides } from '@/theme/naive'
import HsBackground from '@/components/ui/HsBackground.vue'
import HsParticles from '@/components/ui/HsParticles.vue'
import HsProgressBar from '@/components/ui/HsProgressBar.vue'
import HsCommandPalette from '@/components/ui/HsCommandPalette.vue'
import { mountClickRipple } from '@/composables/useClickRipple'
import { isViewTransitionSupported } from '@/composables/useViewTransition'
import { useKonami } from '@/composables/useKonami'

const themeStore = useThemeStore()

/* 彩蛋：Konami 指令（↑↑↓↓←→←→BA）→ <html> 加 data-easter="konami" 触发 CRT 模式 */
useKonami()

const naiveTheme = computed(() => (themeStore.mode === 'dark' ? darkTheme : lightTheme))

/** 当前浏览器支持 View Transitions API 且未开启减少动效 */
const useVTA = isViewTransitionSupported()

/* ---------- 首屏 Preloader ---------- */
const showPreloader = ref(true)
let preloaderTimer: ReturnType<typeof setTimeout> | null = null

onMounted(() => {
  mountClickRipple()
  preloaderTimer = setTimeout(() => {
    showPreloader.value = false
  }, 600)
})

onBeforeUnmount(() => {
  if (preloaderTimer) clearTimeout(preloaderTimer)
})

/** Naive 主题覆盖已收敛到 src/theme/naive.ts（与 tokens.css 的同步约定见该文件头注释） */
const naiveOverrides = computed<GlobalThemeOverrides>(() =>
  getNaiveOverrides(themeStore.mode, themeStore.accent)
)

/**
 * 桥接 utils/request.ts 的 window 事件 → Naive UI message
 */
const NotifyBridge = () => {
  const message = useMessage()
  window.addEventListener('hs:notify', ((e: CustomEvent<{ type: string; content: string }>) => {
    const { type, content } = e.detail
    if (type === 'error') message.error(content)
    else if (type === 'warning') message.warning(content)
    else if (type === 'success') message.success(content)
    else message.info(content)
  }) as EventListener)
  return h('span', { style: 'display:none' })
}
</script>

<style scoped>
.hs-preloader {
  position: fixed;
  inset: 0;
  z-index: var(--z-preloader);
  display: grid;
  place-items: center;
  background: var(--bg);
}
.hs-preloader__mark {
  width: 56px;
  height: 56px;
  display: grid;
  place-items: center;
  background: var(--accent);
  color: #0f172a;
  font-family: var(--font-mono);
  font-weight: 800;
  font-size: 24px;
  border-radius: var(--radius-lg);
  box-shadow: 0 0 40px var(--accent-soft), 0 0 80px var(--accent-soft);
  animation: hs-preload-breathe 1.2s ease-in-out infinite;
}
@keyframes hs-preload-breathe {
  0%,
  100% {
    transform: scale(1);
    opacity: 1;
  }
  50% {
    transform: scale(1.12);
    opacity: 0.85;
  }
}
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.4s var(--ease);
}
.fade-leave-to {
  opacity: 0;
}
</style>
