/**
 * View Transitions API 支持检测与包装。
 * - 支持时：用 document.startViewTransition 做原生跨页过渡（cross-fade）
 * - 不支持 / 用户偏好减少动效时：fallback 到 Vue 的 <Transition name="page">
 * 注意：Vue <Transition> 与 View Transitions 不能同时启用（会双重动画），
 * App.vue / PortalLayout.vue 用 isViewTransitionSupported() 决定是否渲染 <Transition>。
 */

type UpdateCallback = () => void | Promise<void>

interface ViewTransitionDoc {
  startViewTransition?: (updateCallback: UpdateCallback) => void
}

const doc = document as ViewTransitionDoc

export function isViewTransitionSupported(): boolean {
  if (typeof doc.startViewTransition !== 'function') return false
  return !window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

export function startViewTransition(fn: UpdateCallback): void {
  doc.startViewTransition!(fn)
}

export function withViewTransition(fn: UpdateCallback): void {
  if (isViewTransitionSupported()) {
    startViewTransition(fn)
  } else {
    fn()
  }
}
