<template>
  <div class="hs-theme-toggle">
    <button
      class="hs-theme-toggle__btn"
      :title="isDark ? '切换到浅色' : '切换到暗色'"
      :aria-label="isDark ? '切换到浅色主题' : '切换到暗色主题'"
      @click="theme.toggleMode()"
    >
      <svg v-if="isDark" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
        <circle cx="12" cy="12" r="4" />
        <path d="M12 2v2m0 16v2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M2 12h2m16 0h2M4.9 19.1l1.4-1.4m11.4-11.4 1.4-1.4" />
      </svg>
      <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" />
      </svg>
    </button>
    <div class="hs-theme-toggle__accents">
      <button
        v-for="(meta, key) in accentMeta"
        :key="key"
        class="hs-theme-toggle__dot"
        :class="{ 'is-active': theme.accent === key }"
        :style="{ background: meta.color }"
        :title="meta.label"
        :aria-label="`强调色：${meta.label}`"
        :aria-pressed="theme.accent === key"
        @click="theme.setAccent(key as AccentColor)"
      ></button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useThemeStore, accentMeta, type AccentColor } from '@/stores/theme'

const theme = useThemeStore()
const isDark = computed(() => theme.mode === 'dark')
</script>

<style scoped>
.hs-theme-toggle {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.hs-theme-toggle__btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: var(--radius-md);
  border: 1px solid var(--border);
  background: var(--bg-elevated);
  color: var(--text-2);
  cursor: pointer;
  transition: all var(--dur-fast) var(--ease);
}
.hs-theme-toggle__btn:hover {
  color: var(--accent);
  border-color: var(--accent-border);
}
.hs-theme-toggle__accents {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  border-radius: var(--radius-full);
  border: 1px solid var(--border);
  background: var(--bg-elevated);
}
.hs-theme-toggle__dot {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: none;
  cursor: pointer;
  opacity: 0.55;
  transition: all var(--dur-fast) var(--ease);
}
.hs-theme-toggle__dot:hover {
  opacity: 1;
  transform: scale(1.15);
}
.hs-theme-toggle__dot.is-active {
  opacity: 1;
  box-shadow: 0 0 0 2px var(--bg), 0 0 0 3.5px currentColor;
  transform: scale(1.1);
}
</style>
