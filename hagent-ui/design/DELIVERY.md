# HAgent 门户设计交付清单（DELIVERY）

> HAgent Portal Home Prototype v1.0 | Design: Linear-dark × Editorial | 交付日期 2026-08-06
> 质量审查结果：23/25 PASS（评审报告见团队质量审查环节记录）

---

## 一、交付文件总览

| 文件 | 路径 | 用途 |
|------|------|------|
| 设计规范文档 | `design/DESIGN.md` | 设计系统完整规范：设计方向决策、色彩/字体/间距/动效令牌说明、组件规则、Anti-Slop 约束。人与 AI 共读的「设计宪法」 |
| 设计令牌源 | `design/tokens.css` | 单一令牌源（Single Source of Truth）。`:root` 放共用令牌（brand/spacing/radius/motion/font），`.portal-dark` / `.admin-light` 两套语义层仅切换 surface / text / border |
| 后台主题配置 | `design/antd-theme.json` | Ant Design Vue 4.2 `ConfigProvider` 的 theme 配置，与 `tokens.css` 的 `.admin-light` 语义层一一对应，用于后台管理端 |
| 门户首页原型 | `design/prototype/home.html` | 单文件可运行原型：tokens 已内嵌，唯一外部依赖为 Google Fonts CDN。双击即可在浏览器打开预览 |
| 本清单 | `design/DELIVERY.md` | 交付说明与 Vue 项目接入指引 |

### 原型页面结构（home.html）

```
header.site-nav        64px 毛玻璃顶栏（Logo H / 导航 / GitHub / 进入后台 / 移动端抽屉）
main
 ├─ section.hero       杂志式超大衬线标题 + 元信息行 + H 水印 + 双 CTA
 ├─ section#posts      精选文章：序号 / 标题 / 摘要 / 日期 / 标签 的杂志列表
 ├─ section#stack      技术栈三卡：Backend / Frontend / AI Engineering
 └─ section#stats      项目数据：衬线大数字 + 元信息标签（4 格）
footer.site-footer     品牌落款 + 三列链接 + 底部版权行
```

技术要点：纯 CSS 无 JS；移动端导航用 checkbox hack 抽屉；`:focus-visible` 焦点环；`prefers-reduced-motion` 降级；断点 1024px / 640px。

---

## 二、Vue 项目接入指引

目标项目结构假设：`hagent-portal/`（Vue 3 + Vite + TypeScript + Pinia + Ant Design Vue 4.2）。

### 1. tokens.css —— 全局引入

将 `tokens.css` 拷贝到 `src/styles/tokens.css`，在入口全局引入：

```ts
// src/main.ts
import { createApp } from 'vue'
import App from './App.vue'
import '@/styles/tokens.css'   // 设计令牌全局注入，必须在组件样式之前

createApp(App).mount('#app')
```

门户页面（暗色）在根容器挂语义层类名：

```vue
<!-- src/App.vue 或门户布局组件 -->
<template>
  <div class="portal-dark">
    <router-view />
  </div>
</template>
```

后台页面则在后台布局根节点挂 `.admin-light`。组件内一律消费语义变量（`var(--bg)` / `var(--text)` / `var(--accent)`…），**不要写死色值**，这样两套主题只换类名即可切换。

### 2. antd-theme.json —— 喂给 a-config-provider

将 `antd-theme.json` 拷贝到 `src/theme/antd-theme.json`，在后台布局入口注入：

```vue
<!-- src/layouts/AdminLayout.vue -->
<script setup lang="ts">
import themeConfig from '@/theme/antd-theme.json'

// 去掉文档注释字段，其余直接作为 theme 传入
const { _comment, ...theme } = themeConfig as Record<string, unknown>
</script>

<template>
  <a-config-provider :theme="theme">
    <router-view />
  </a-config-provider>
</template>
```

注意：
- 配置中 `cssVar: true` 已开启，antd 会输出 CSS 变量，与 tokens.css 可以共存；
- 该配置对应 **浅色后台**，仅包后台布局，不要包门户页面；
- 若 tsconfig 未开 JSON 导入，需设置 `"resolveJsonModule": true`。

### 3. home.html —— 迁移到 src/views/home/index.vue

原型为纯 CSS 无 JS，迁移工作量主要在「拆区块、接数据」：

| 原型区块 | 迁移建议 |
|---------|---------|
| `header.site-nav` | 拆为 `src/components/layout/SiteNav.vue`；移动端抽屉建议将 checkbox hack 换成 `ref<boolean>` 控制（Vue 语义更清晰），样式类名与断点直接复用 |
| `section.hero` | 留在 `src/views/home/index.vue` 顶部；文案可抽为 `home.config.ts` 常量 |
| `section#posts` 精选文章 | 拆为 `src/components/home/PostList.vue`；静态 `<li>` 换成 `v-for` 渲染接口数据（字段：`title / excerpt / date / tag`），日期用 `<time :datetime>` 保留语义 |
| `section#stack` 技术栈 | 拆为 `StackGrid.vue`，卡片数据抽常量数组 `v-for` 渲染 |
| `section#stats` 项目数据 | 拆为 `StatsRow.vue`；数字建议接站点统计接口，初期可写死 |
| `footer.site-footer` | 拆为 `src/components/layout/SiteFooter.vue`，与 SiteNav 一起放门户布局 |

样式迁移原则：
- 原型 `<style>` 中的区块样式按组件边界拆进各 SFC 的 `<style scoped>`；
- **Design Tokens 段不要复制进组件**——已在第 1 步全局引入，直接用 `var(--xxx)`；
- 响应式媒体查询（1024px / 640px）随各自组件样式一起迁移；
- 字体建议改为本地托管或在 `index.html` 保留 Google Fonts `<link>`（Playfair Display / Inter / JetBrains Mono / Noto Sans SC / Noto Serif SC），国内部署可考虑字体子集化。

---

## 三、验收状态

- [x] 原型完整性校验通过（标签闭合、无截断、无占位符、抽屉导航链路正确）
- [x] 版本注释已写入 home.html 头部
- [x] 质量审查 23/25 PASS，两项非阻断项为可接受取舍（见评审记录）
- [x] 单文件双击可运行，无构建依赖
