/**
 * 读取 CSS 变量（设计 token）的辅助函数。
 * 用于需要 JS 拿到实际色值的地方（canvas 粒子、涟漪等）。
 */
export function getCssVar(name: string, fallback = ''): string {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return value || fallback
}
