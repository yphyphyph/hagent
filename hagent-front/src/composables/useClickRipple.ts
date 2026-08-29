import { getCssVar } from './useThemeColor'

/**
 * 全局点击涟漪：pointerdown 时在点击位置生成一个 accent 色圆圈扩散消失。
 * - 低透明度（暗色 0.5 / 浅色 0.25），pointer-events: none，不挡交互
 * - prefers-reduced-motion 时自动禁用
 * - 幂等：多次调用只挂载一次
 */

let mounted = false
let layer: HTMLDivElement | null = null

function ensureLayer(): HTMLDivElement {
  if (layer && document.body.contains(layer)) return layer
  layer = document.createElement('div')
  layer.className = 'hs-ripple-layer'
  layer.setAttribute('aria-hidden', 'true')
  document.body.appendChild(layer)
  return layer
}

function onPointerDown(e: PointerEvent) {
  if (e.button !== 0) return
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return

  const host = ensureLayer()
  const accent = getCssVar('--accent', '#22c55e')
  const isDark = document.documentElement.dataset.theme !== 'light'

  const size = Math.max(24, Math.min(window.innerWidth, window.innerHeight) * 0.04)
  const el = document.createElement('span')
  el.className = 'hs-ripple'
  el.style.width = `${size}px`
  el.style.height = `${size}px`
  el.style.left = `${e.clientX - size / 2}px`
  el.style.top = `${e.clientY - size / 2}px`
  el.style.setProperty('--ripple-color', accent)
  el.style.setProperty('--ripple-alpha', isDark ? '0.5' : '0.25')

  host.appendChild(el)
  el.addEventListener('animationend', () => el.remove(), { once: true })
}

export function mountClickRipple(): void {
  if (mounted) return
  mounted = true
  window.addEventListener('pointerdown', onPointerDown, { passive: true })
}
