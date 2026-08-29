<template>
  <!-- 门户壳：仅提供全屏高度，布局由聊天页自行编排（侧栏贴左通高 + 主区铺满） -->
  <div class="portal-shell">
    <router-view />
  </div>
</template>

<script setup lang="ts">
</script>

<style scoped>
.portal-shell {
  height: 100vh;
  overflow: hidden;
}
</style>
