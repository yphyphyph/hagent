import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

export type ThemeMode = 'dark' | 'light'
export type AccentColor = 'green' | 'blue' | 'purple' | 'amber'

const MODE_KEY = 'hagent_theme_mode'
const ACCENT_KEY = 'hagent_theme_accent'

export const accentMeta: Record<AccentColor, { label: string; color: string; hover: string }> = {
  green: { label: '运行绿', color: '#22c55e', hover: '#4ade80' },
  blue: { label: '科技蓝', color: '#3b82f6', hover: '#60a5fa' },
  purple: { label: '极客紫', color: '#8b5cf6', hover: '#a78bfa' },
  amber: { label: '琥珀橙', color: '#f59e0b', hover: '#fbbf24' },
}

function applyToDom(mode: ThemeMode, accent: AccentColor) {
  document.documentElement.dataset.theme = mode
  document.documentElement.dataset.accent = accent
}

export const useThemeStore = defineStore('theme', () => {
  const savedMode = localStorage.getItem(MODE_KEY) as ThemeMode | null
  const savedAccent = localStorage.getItem(ACCENT_KEY) as AccentColor | null

  const mode = ref<ThemeMode>(savedMode ?? 'dark')
  const accent = ref<AccentColor>(savedAccent ?? 'green')

  applyToDom(mode.value, accent.value)

  watch(mode, (v) => {
    localStorage.setItem(MODE_KEY, v)
    applyToDom(v, accent.value)
  })
  watch(accent, (v) => {
    localStorage.setItem(ACCENT_KEY, v)
    applyToDom(mode.value, v)
  })

  function toggleMode() {
    mode.value = mode.value === 'dark' ? 'light' : 'dark'
  }
  function setAccent(color: AccentColor) {
    accent.value = color
  }

  return { mode, accent, toggleMode, setAccent }
})
