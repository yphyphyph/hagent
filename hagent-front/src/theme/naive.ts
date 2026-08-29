/**
 * Naive UI 主题覆盖 —— 单一数据源
 *
 * Naive 的 theming 引擎（seemly）无法解析 CSS 变量（var(--bg)），
 * 因此这里以色值形式镜像 src/styles/tokens.css，两者必须保持同步：
 *   palette.dark    ↔ tokens.css :root（暗色主题）
 *   palette.light   ↔ tokens.css [data-theme='light']
 *   borderRadius    ↔ tokens.css --radius-sm（8px）
 *   fontFamily      ↔ tokens.css --font-sans
 */
import type { GlobalThemeOverrides } from 'naive-ui'
import { accentMeta, type AccentColor, type ThemeMode } from '@/stores/theme'

interface ThemePalette {
  bg: string
  bgElevated: string
  bgHover: string
  border: string
  text1: string
  text2: string
  text3: string
}

/** 语义色板，与 tokens.css 一一对应（见文件头同步约定） */
export const palette: Record<ThemeMode, ThemePalette> = {
  dark: {
    bg: '#0f172a',
    bgElevated: '#1b2336',
    bgHover: '#232e47',
    border: 'rgba(148,163,184,0.16)',
    text1: '#f8fafc',
    text2: '#94a3b8',
    text3: '#64748b',
  },
  light: {
    bg: '#f8fafc',
    bgElevated: '#ffffff',
    bgHover: '#f1f5f9',
    border: 'rgba(15,23,42,0.12)',
    text1: '#0f172a',
    text2: '#475569',
    text3: '#94a3b8',
  },
}

/** 按主题模式 + 强调色计算 Naive UI 主题覆盖 */
export function getNaiveOverrides(mode: ThemeMode, accent: AccentColor): GlobalThemeOverrides {
  const p = palette[mode]
  /* 浅色主题下强调色加深一级（600 级）保对比度，与 tokens.css 行为一致 */
  const isLightGreen = mode === 'light' && accent === 'green'
  const primary = isLightGreen ? '#16a34a' : accentMeta[accent].color
  const primaryHover = isLightGreen ? '#22c55e' : accentMeta[accent].hover

  return {
    common: {
      primaryColor: primary,
      primaryColorHover: primaryHover,
      primaryColorPressed: primary,
      primaryColorSuppl: primary,
      borderRadius: '8px',
      fontFamily:
        "'Inter Variable', Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif",
      bodyColor: p.bg,
      cardColor: p.bgElevated,
      modalColor: p.bgElevated,
      popoverColor: p.bgElevated,
      inputColor: p.bg,
      inputColorDisabled: p.bgHover,
      textColorBase: p.text1,
      textColor1: p.text1,
      textColor2: p.text2,
      textColor3: p.text3,
      borderColor: p.border,
      dividerColor: p.border,
      hoverColor: p.bgHover,
      actionColor: p.bgHover,
      tableHeaderColor: p.bgHover,
      tableColor: p.bgElevated,
    },
  }
}
