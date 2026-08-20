<template>
  <canvas ref="canvasRef" class="hs-particles" aria-hidden="true"></canvas>
</template>

<script setup lang="ts">
/**
 * canvas 粒子连线背景（Butterfly canvas-nest 效果）
 * - 粒子随机漂移 + 近距离连线，颜色读取 --accent，暗/浅主题自适应
 * - 性能：devicePixelRatio 上限 1.5、IntersectionObserver 视口可见才跑、
 *   document.hidden 暂停、prefers-reduced-motion 禁用、移动端粒子减半
 * - fixed 全屏、z-index: -1、pointer-events: none，不挡交互
 */
import { onMounted, onUnmounted, ref } from 'vue'
import { getCssVar } from '@/composables/useThemeColor'

interface Particle {
  x: number
  y: number
  vx: number
  vy: number
  r: number
}

const props = withDefaults(
  defineProps<{
    /** 粒子数，0 表示按主题/屏幕自动计算 */
    count?: number
    /** 连线最大距离 px */
    maxDist?: number
    /** 漂移速度系数 */
    speed?: number
  }>(),
  { count: 0, maxDist: 130, speed: 0.4 }
)

const canvasRef = ref<HTMLCanvasElement | null>(null)

let ctx: CanvasRenderingContext2D | null = null
let particles: Particle[] = []
let raf = 0
let width = 0
let height = 0
let accent = '#22c55e'
let isDark = true
let reduceMotion = false
let visible = true // IntersectionObserver + visibilitychange 综合可见性
let docVisible = true
let lastTime = 0

let io: IntersectionObserver | null = null
let mo: MutationObserver | null = null

/** 暗色模式下把 accent 与白色混合 ~30%，让粒子在深底上更亮 */
function mixWithWhite(hex: string, ratio: number): string {
  const r = parseInt(hex.slice(1, 3), 16)
  const g = parseInt(hex.slice(3, 5), 16)
  const b = parseInt(hex.slice(5, 7), 16)
  const mix = (c: number) => Math.round(c + (255 - c) * ratio)
  return `rgb(${mix(r)}, ${mix(g)}, ${mix(b)})`
}

function readTheme() {
  const base = getCssVar('--accent', '#22c55e')
  isDark = document.documentElement.dataset.theme !== 'light'
  accent = isDark && /^#[0-9a-f]{6}$/i.test(base) ? mixWithWhite(base, 0.3) : base
}

function resize() {
  const canvas = canvasRef.value
  if (!canvas) return
  const dpr = Math.min(window.devicePixelRatio || 1, 1.5)
  width = window.innerWidth
  height = window.innerHeight
  canvas.width = Math.round(width * dpr)
  canvas.height = Math.round(height * dpr)
  canvas.style.width = `${width}px`
  canvas.style.height = `${height}px`
  ctx?.setTransform(dpr, 0, 0, dpr, 0, 0)
}

function spawn() {
  const mobile = width < 768
  let n = props.count > 0 ? props.count : isDark ? 60 : 48
  if (mobile) n = Math.max(12, Math.floor(n / 2))
  particles = Array.from({ length: n }, () => ({
    x: Math.random() * width,
    y: Math.random() * height,
    vx: (Math.random() - 0.5) * props.speed,
    vy: (Math.random() - 0.5) * props.speed,
    r: Math.random() * 1.6 + 1,
  }))
}

function step(time: number) {
  if (!ctx || !running()) return
  const dt = Math.min(32, time - lastTime || 16)
  lastTime = time
  const s = dt / 16

  ctx.clearRect(0, 0, width, height)
  const maxD2 = props.maxDist * props.maxDist
  const lineAlpha = isDark ? 0.75 : 0.45

  for (let i = 0; i < particles.length; i++) {
    const p = particles[i]
    p.x += p.vx * s
    p.y += p.vy * s
    if (p.x < -20) p.x = width + 20
    else if (p.x > width + 20) p.x = -20
    if (p.y < -20) p.y = height + 20
    else if (p.y > height + 20) p.y = -20

    for (let j = i + 1; j < particles.length; j++) {
      const q = particles[j]
      const dx = p.x - q.x
      const dy = p.y - q.y
      const d2 = dx * dx + dy * dy
      if (d2 < maxD2) {
        ctx.strokeStyle = accent
        ctx.globalAlpha = (1 - d2 / maxD2) * lineAlpha
        ctx.lineWidth = 1
        ctx.beginPath()
        ctx.moveTo(p.x, p.y)
        ctx.lineTo(q.x, q.y)
        ctx.stroke()
      }
    }
  }

  ctx.globalAlpha = isDark ? 1 : 0.8
  ctx.fillStyle = accent
  for (const p of particles) {
    ctx.beginPath()
    ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2)
    ctx.fill()
  }
  ctx.globalAlpha = 1

  raf = requestAnimationFrame(step)
}

function running(): boolean {
  return visible && docVisible && !reduceMotion
}

function updateLoop() {
  cancelAnimationFrame(raf)
  if (running()) {
    lastTime = 0
    raf = requestAnimationFrame(step)
  }
}

function onResize() {
  resize()
  spawn()
  updateLoop()
}

function onVisibility() {
  docVisible = !document.hidden
  updateLoop()
}

onMounted(() => {
  reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  if (reduceMotion) return

  readTheme()
  ctx = canvasRef.value?.getContext('2d') ?? null
  if (!ctx) return

  resize()
  spawn()

  io = new IntersectionObserver((entries) => {
    visible = entries[0]?.isIntersecting ?? false
    updateLoop()
  })
  if (canvasRef.value) io.observe(canvasRef.value)

  // 主题 / 强调色变化时重读颜色（canvas 绘制自动用新值）
  mo = new MutationObserver(readTheme)
  mo.observe(document.documentElement, { attributes: true, attributeFilter: ['data-accent', 'data-theme'] })

  window.addEventListener('resize', onResize)
  document.addEventListener('visibilitychange', onVisibility)

  raf = requestAnimationFrame(step)
})

onUnmounted(() => {
  cancelAnimationFrame(raf)
  io?.disconnect()
  mo?.disconnect()
  window.removeEventListener('resize', onResize)
  document.removeEventListener('visibilitychange', onVisibility)
})
</script>

<style scoped>
.hs-particles {
  position: fixed;
  inset: 0;
  z-index: var(--z-bg-particles);
  pointer-events: none;
}
</style>
