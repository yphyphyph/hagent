<template>
  <div class="page-header">
    <div class="page-header__text">
      <h2 class="page-header__title">{{ title }}</h2>
      <p v-if="description" class="page-header__desc">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="page-header__actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 后台通用页头：标题 + 描述 + 右侧操作区插槽
 * 统一各管理页手写的 .xx-head 结构，保证标题字号/间距/描述色一致（见 design/DESIGN.md）
 */
defineProps<{
  title: string
  description?: string
}>()
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--space-4);
  flex-wrap: wrap;
}
.page-header__title {
  font-size: var(--text-lg);
  font-weight: 700;
  letter-spacing: -0.02em;
}
.page-header__desc {
  color: var(--text-3);
  font-size: var(--text-sm);
  margin-top: 2px;
}
.page-header__actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
</style>
