<template>
  <div class="hs-bg" aria-hidden="true">
    <div class="hs-bg__blob hs-bg__blob--1"></div>
    <div class="hs-bg__blob hs-bg__blob--2"></div>
    <div class="hs-bg__blob hs-bg__blob--3"></div>
  </div>
</template>

<script setup lang="ts">
/**
 * Aurora 动态渐变背景层
 * - fixed 全屏、z-index: -2，位于所有内容之下，pointer-events: none
 * - 主光晕用 var(--accent-soft)：强调色切换 / 主题切换时自动适配
 * - 辅助光晕用 var(--aurora-glow-2/3)：暗色深蓝底 + 冷蓝/紫罗兰，浅色更亮更淡
 * - 用 transform 位移动画（GPU 合成）避免 background-position 重绘开销
 */
</script>

<style scoped>
.hs-bg {
  position: fixed;
  inset: 0;
  z-index: var(--z-bg-aurora);
  overflow: hidden;
  pointer-events: none;
  background: var(--aurora-base);
}

.hs-bg__blob {
  position: absolute;
  border-radius: 50%;
  will-change: transform;
}

/* 主光晕：核心亮斑用 --aurora-accent（随强调色/主题），外层光晕用 --accent-soft 自动适配 */
.hs-bg__blob--1 {
  width: 860px;
  height: 860px;
  top: -18%;
  left: -8%;
  background:
    radial-gradient(circle 260px at 50% 46%, var(--aurora-accent) 0%, transparent 74%),
    radial-gradient(circle, var(--accent-soft) 0%, transparent 68%);
  animation: hs-aurora-1 18s var(--ease) infinite alternate;
}

/* 冷蓝辅助光晕 */
.hs-bg__blob--2 {
  width: 920px;
  height: 920px;
  right: -14%;
  top: 22%;
  background:
    radial-gradient(circle 300px at 50% 52%, var(--aurora-glow-2) 0%, transparent 76%),
    radial-gradient(circle, var(--aurora-glow-2) 0%, transparent 68%);
  animation: hs-aurora-2 22s var(--ease) infinite alternate;
}

/* 紫罗兰辅助光晕 */
.hs-bg__blob--3 {
  width: 700px;
  height: 700px;
  left: 30%;
  bottom: -22%;
  background:
    radial-gradient(circle 230px at 50% 48%, var(--aurora-glow-3) 0%, transparent 76%),
    radial-gradient(circle, var(--aurora-glow-3) 0%, transparent 68%);
  animation: hs-aurora-3 16s var(--ease) infinite alternate;
}

/* 小号强调色光晕（右下，增加层次） */
.hs-bg__blob--4 {
  width: 380px;
  height: 380px;
  right: 8%;
  bottom: -8%;
  background: radial-gradient(circle, var(--aurora-accent) 0%, transparent 70%);
  opacity: 0.7;
  animation: hs-aurora-4 14s var(--ease) infinite alternate;
}

@keyframes hs-aurora-1 {
  0% {
    transform: translate3d(0, 0, 0) scale(1);
  }
  50% {
    transform: translate3d(6%, 9%, 0) scale(1.12);
  }
  100% {
    transform: translate3d(-4%, 4%, 0) scale(1.04);
  }
}

@keyframes hs-aurora-2 {
  0% {
    transform: translate3d(0, 0, 0) scale(1.05);
  }
  50% {
    transform: translate3d(-7%, -6%, 0) scale(1);
  }
  100% {
    transform: translate3d(5%, 3%, 0) scale(1.12);
  }
}

@keyframes hs-aurora-3 {
  0% {
    transform: translate3d(0, 0, 0) scale(1);
  }
  50% {
    transform: translate3d(-9%, -7%, 0) scale(1.1);
  }
  100% {
    transform: translate3d(3%, 8%, 0) scale(1.02);
  }
}

@keyframes hs-aurora-4 {
  0% {
    transform: translate3d(0, 0, 0) scale(1);
  }
  100% {
    transform: translate3d(-6%, -5%, 0) scale(1.15);
  }
}
</style>
