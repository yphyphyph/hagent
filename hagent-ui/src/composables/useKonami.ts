/**
 * Konami 指令彩蛋：↑↑↓↓←→←→BA
 * - 序列匹配后给 <html> 加 data-easter="konami"，由 global.css 驱动 CRT 扫描线模式
 * - 激活态下按任意键（Esc / 重输 Konami / 普通按键）关闭
 * - 输入框 / 可编辑区域中不参与序列匹配（避免打字误触发）
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'

const KONAMI_SEQUENCE = [
  'ArrowUp',
  'ArrowUp',
  'ArrowDown',
  'ArrowDown',
  'ArrowLeft',
  'ArrowRight',
  'ArrowLeft',
  'ArrowRight',
  'b',
  'a',
]

export function useKonami() {
  const active = ref(false)
  let index = 0

  function activate() {
    if (active.value) return
    active.value = true
    index = 0
    document.documentElement.setAttribute('data-easter', 'konami')
    window.dispatchEvent(
      new CustomEvent('hs:notify', { detail: { type: 'success', content: 'Konami 已激活，按任意键关闭' } })
    )
  }

  function deactivate() {
    if (!active.value) return
    active.value = false
    index = 0
    document.documentElement.removeAttribute('data-easter')
  }

  function onKeydown(e: KeyboardEvent) {
    // 激活态：任意键关闭
    if (active.value) {
      deactivate()
      return
    }

    // 输入场景不参与序列匹配（如输入框里打 b/a）
    const target = e.target as HTMLElement | null
    const typing =
      target?.tagName === 'INPUT' || target?.tagName === 'TEXTAREA' || target?.isContentEditable === true
    if (typing) return

    const key = e.key
    if (key === KONAMI_SEQUENCE[index]) {
      index++
      if (index === KONAMI_SEQUENCE.length) {
        activate()
      }
    } else {
      // 失配重置；若当前键恰是序列首键则从头再试（如 ↑↑↑）
      index = key === KONAMI_SEQUENCE[0] ? 1 : 0
    }
  }

  onMounted(() => {
    window.addEventListener('keydown', onKeydown)
  })

  onBeforeUnmount(() => {
    window.removeEventListener('keydown', onKeydown)
    document.documentElement.removeAttribute('data-easter')
  })

  return { active, deactivate }
}
