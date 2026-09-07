<template>
  <!-- 附件二选一菜单：锚定回形针按钮上方，点外部/Esc 关闭（监听随组件挂载，v-if 开关即生效） -->
  <div ref="menuRef" class="attach-menu" role="menu" aria-label="附件来源">
    <button type="button" class="attach-menu__item" role="menuitem" @click="emit('upload')">
      <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
        <path d="M17 8l-5-5-5 5" />
        <path d="M12 3v12" />
      </svg>
      上传文件
    </button>
    <!-- 访客不可见：知识库列表接口为 admin 权限 -->
    <button v-if="!guest" type="button" class="attach-menu__item" role="menuitem" @click="emit('knowledge')">
      <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <ellipse cx="12" cy="5" rx="9" ry="3" />
        <path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3" />
        <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5" />
      </svg>
      从知识库选择
    </button>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'

defineProps<{
  /** 访客门控：true 时隐藏「从知识库选择」项 */
  guest?: boolean
}>()

const emit = defineEmits<{
  (e: 'upload'): void
  (e: 'knowledge'): void
  (e: 'close'): void
}>()

const menuRef = ref<HTMLElement | null>(null)

/** 点击菜单外部关闭 */
function onDocumentClick(e: MouseEvent) {
  const el = menuRef.value
  if (el && e.target instanceof Node && el.contains(e.target)) return
  emit('close')
}

/** Esc 关闭 */
function onDocumentKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close')
}

/** 延迟注册计时器（onUnmounted 时若尚未触发需取消，防泄漏） */
let deferTimer: number | undefined

onMounted(() => {
  // 延迟一拍注册：打开菜单的那次点击事件仍在冒泡途中，若同步挂 document 监听，
  // 会被同一事件立即触发——e.target 是回形针按钮（菜单的兄弟节点，contains 为 false），
  // 误判「外部点击」导致菜单挂载即卸载、肉眼不可见。setTimeout(0) 让本次点击先完成派发。
  deferTimer = window.setTimeout(() => {
    deferTimer = undefined
    document.addEventListener('click', onDocumentClick)
    document.addEventListener('keydown', onDocumentKeydown)
  }, 0)
})

onUnmounted(() => {
  // 取消尚未触发的延迟注册，再移除已挂上的监听（两步都做，重复 remove 无副作用）
  if (deferTimer !== undefined) {
    window.clearTimeout(deferTimer)
    deferTimer = undefined
  }
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('keydown', onDocumentKeydown)
})
</script>

<style scoped>
.attach-menu {
  position: absolute;
  bottom: calc(100% + 8px);
  right: 0;
  min-width: 148px;
  z-index: 7;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  box-shadow: 0 12px 32px rgba(15, 23, 42, 0.18);
  padding: var(--space-1);
  display: flex;
  flex-direction: column;
}
.attach-menu__item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  border: none;
  background: transparent;
  color: var(--text-1);
  font-size: var(--text-sm);
  line-height: 1.5;
  text-align: left;
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  cursor: pointer;
  white-space: nowrap;
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}
.attach-menu__item:hover {
  background: var(--bg-hover);
  color: var(--text-1);
}
.attach-menu__item svg {
  color: var(--text-3);
  flex-shrink: 0;
}
</style>
