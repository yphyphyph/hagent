# HAgent Design System

> 暗色工具骨架 × 编辑排版灵魂 —— 个人技术网站（门户展示面 + 后台工具面）的统一设计语言。

---

## 1. Visual Theme

**Philosophy**: 像一件被精心排印的工程作品 —— Linear 式的深空暗色提供"专业产品"的骨架，杂志式的衬线排版赋予"创作者"的灵魂。
**Direction**: dark, editorial, precise, restrained
**Personality**: confident, meticulous, geeky-but-tasteful
**Reference**: Linear（色彩与组件基因） × Warm Editorial（排版手法） × Stripe Press（暗色出版质感）

**品牌符号 "H" 使用规范**：
- **Logo**：几何无衬线大写 "H"，横杠处嵌入品牌紫短线（`#5E6AD2`），或以 Playfair Display "H" 用于页脚/文章落款等编辑场景
- **装饰**：超大号极低透明度（opacity 0.03–0.05）"H" 可作为 Hero 区/关于页的水印底纹，严禁用于内容卡片
- **加载态**：紫线扫过 "H" 横杠的 1.2s 循环微动画，替代通用 spinner（仅门户）
- **Favicon**：深底 #08090A + 紫色 H，保证浏览器深色/浅色标签栏均可辨识

---

## 2. Color Palette

### Brand —— HAgent Iris（品牌紫 #5E6AD2 族）

| Token | HEX | Usage |
|-------|-----|-------|
| --brand-50 | #EEF0FB | 后台浅紫底、tag 底色 |
| --brand-100 | #DDE0F7 | hover 浅底 |
| --brand-200 | #BEC4EF | 边框强调 |
| --brand-300 | #9BA3E5 | 次级强调 |
| --brand-400 | #7C85DC | 暗色 hover 态 |
| --brand-500 | #5E6AD2 | **品牌主色**，链接、强调 |
| --brand-600 | #4C56C0 | **antd colorPrimary**，按钮 |
| --brand-700 | #3D44A0 | 按下态 |
| --brand-800 | #2F3378 | 深色强调面 |
| --brand-900 | #222450 | 深紫底纹 |

### Portal Dark（门户语义层 · 暗色）

| Token | HEX / 值 | Usage |
|-------|----------|-------|
| --p-bg | #08090A | 页面底色 |
| --p-surface | #101213 | 卡片/区块 |
| --p-surface-raised | #16181A | 悬浮层、代码岛 |
| --p-code-bg | #0B0D0F | 代码块深色岛（比卡片更深） |
| --p-border | rgba(255,255,255,.08) | 分隔线/描边 |
| --p-border-strong | rgba(255,255,255,.16) | hover/聚焦描边 |
| --p-text | #F7F8F8 | 标题、正文 |
| --p-text-secondary | #8A8F98 | 次级文字、元信息 |
| --p-text-tertiary | #62666D | 占位、弱化信息 |
| --p-accent | #7C85DC | 暗色下链接/强调（brand-400，保证对比度） |
| --p-accent-soft | rgba(94,106,210,.14) | 强调底色、选中态 |

> 对比度校验：#8A8F98 on #08090A ≈ 7.0:1；#7C85DC on #08090A ≈ 5.2:1，均通过 WCAG AA。

### Admin Light（后台语义层 · 浅色）

| Token | HEX | Usage |
|-------|-----|-------|
| --a-bg-layout | #F5F5F7 | 布局底色 |
| --a-bg-container | #FFFFFF | 卡片/表格容器 |
| --a-border | #E5E6EB | 分隔线 |
| --a-text | #1F2329 | 主文字 |
| --a-text-secondary | #646A73 | 次级文字 |
| --a-text-tertiary | #8F959E | 占位/禁用 |

### Semantic（双轨共用）

| Token | HEX | Usage |
|-------|-----|-------|
| --color-success | #4CC38A（暗）/ #16A34A（浅） | 成功 |
| --color-warning | #F5A524（暗）/ #D97706（浅） | 警告 |
| --color-danger | #F2555A（暗）/ #DC2626（浅） | 错误/危险操作 |
| --color-info | --brand-500 | 信息 |

---

## 3. Typography

### Font Stacks
- **Display（门户大标题）**: `"Playfair Display", "Noto Serif SC", Georgia, serif` —— 杂志感衬线，字重 600–700
- **Body（正文/UI）**: `"Inter", "Noto Sans SC", -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif`
- **Mono（代码/数据）**: `"JetBrains Mono", "SF Mono", Consolas, monospace`

### Scale（门户 · 杂志字阶，大对比）

| Level | Size | Weight | Line-height | Usage |
|-------|------|--------|-------------|-------|
| Display | 56px / 3.5rem | 700 serif | 1.1 | 首页 Hero、关于页 |
| H1 | 40px / 2.5rem | 700 serif | 1.15 | 文章标题 |
| H2 | 28px / 1.75rem | 600 serif | 1.3 | 章节标题 |
| H3 | 20px / 1.25rem | 600 sans | 1.4 | 小节、卡片标题 |
| Article Body | 17–18px | 400 | **1.75** | 文章正文 |
| Body | 16px / 1rem | 400 | 1.6 | UI 正文 |
| Small | 14px / 0.875rem | 400 | 1.5 | 说明、元信息 |
| Micro | 12px / 0.75rem | 500 | 1.4 | 标签、徽章 |
| Code | 14px / 0.875rem | 400 | 1.7 | 代码块 |

**杂志手法在暗色基底的落地**：
- 标题用衬线 + 大留白，字号对比跨度 ≥ 2.5×（Display 56 vs Body 17）
- 文章页阅读宽度 **max-width: 680px**，两端留白形成"书页"感
- 正文 17–18px / 行高 1.75 / 段间距 1.5em，长文阅读不疲劳
- 元信息行（日期/标签/阅读时长）用 12px uppercase 无衬线 + letter-spacing .08em，衬在衬线大标题下方，制造编辑排版张力
- 首段可用 20px 引言字号；**不用**首字下沉（暗色下视觉噪音大）

---

## 4. Component Styles

### Button
- **Primary**：bg `--brand-600`，text #FFF，radius 8px，padding 10px 20px；hover → `--brand-500`；门户暗色下文字纯白
- **Secondary（门户）**：bg transparent，1px solid `--p-border`，text `--p-text`；hover → border `--p-border-strong` + bg rgba(255,255,255,.04)
- **Ghost**：text `--p-accent`；hover → bg `--p-accent-soft`

### Card（门户）
- Background: `var(--p-surface)`；Border: 1px solid `var(--p-border)`
- Border-radius: 12px；Padding: 24px
- Hover：border 变 `--p-border-strong` + 上移 2px（translateY(-2px)），**不加发光阴影**

### Code Block（文章页核心组件）
- Background: `var(--p-code-bg)`（比卡片更深的"深色岛"）
- Border: 1px solid `var(--p-border)`；radius 10px；padding 16px 20px
- 顶部可带 12px 文件名栏（等宽字体 + `--p-text-tertiary`）

### Input
- Height: 40px；Border: 1px solid 语义层 border；radius 8px
- Focus ring: 2px solid `--p-accent-soft`（门户）/ `rgba(76,86,192,.16)`（后台）
- Placeholder: 语义层 tertiary 文字色

### Navigation
- 门户：顶部栏，高 64px，backdrop-filter 毛玻璃 + 底部 1px `--p-border`；active 项文字 `--p-text` + 底部 2px 紫线指示
- 后台：antd 侧边栏 240px（收起 64px）+ 顶栏，全部走 antd token

---

## 5. Layout

### Grid
- 门户容器 max-width: **1120px**；文章页内容列 max-width: **680px**
- 栅格：12 列，gutter 24px（桌面端）
- 大留白纪律：区块间距 ≥ 96px（`--space-4xl`），Hero 上下 ≥ 128px

### Spacing Scale

| Token | Value | Usage |
|-------|-------|-------|
| --space-xs | 4px | 行内 |
| --space-sm | 8px | 紧凑 |
| --space-md | 16px | 默认 |
| --space-lg | 24px | 卡片内边距 |
| --space-xl | 32px | 组件间隔 |
| --space-2xl | 48px | 小节分隔 |
| --space-3xl | 64px | 大节分隔 |
| --space-4xl | 96px | 区块分隔（杂志留白） |

### Radius
--radius-sm 6px（后台/输入） · --radius-md 8px（按钮） · --radius-lg 12px（卡片） · --radius-full 999px（标签/头像）

---

## 6. Depth & Elevation

暗色模式下**优先用边框与明度差表达层级，慎用投影**。

| Level | 门户（暗） | 后台（浅） | Usage |
|-------|-----------|-----------|-------|
| Flat | none | none | 默认面 |
| Raised | 0 1px 2px rgba(0,0,0,.4) | 0 1px 3px rgba(0,0,0,.08) | 卡片、下拉 |
| Floating | 0 8px 24px rgba(0,0,0,.5) | 0 4px 12px rgba(0,0,0,.10) | 弹层、Popover |
| Overlay | 0 16px 48px rgba(0,0,0,.6) | 0 8px 24px rgba(0,0,0,.14) | Modal |

### Z-index
Base 0 · Dropdown 100 · Sticky 200 · Modal 300 · Toast 400

### Motion
| Token | Value | Usage |
|-------|-------|-------|
| --duration-fast | 120ms | hover、焦点 |
| --duration-base | 200ms | 展开/收起、卡片位移 |
| --duration-slow | 320ms | 页面转场、大元素入场 |
| --ease-out | cubic-bezier(.16,1,.3,1) | 默认缓动 |

---

## 7. Cautions

### Never Do
- 禁止大面积霓虹渐变、网格线背景、"赛博蓝光效"——这是极客暗色俗套三件套
- 禁止暗色模式下用纯黑 #000 或纯白 #FFF 文字（用 #08090A / #F7F8F8）
- 禁止正文衬线字体用于 UI 控件、表格、按钮
- 禁止门户使用天蓝 #0EA5E9（旧资产已废弃）
- 禁止卡片 hover 加紫色发光阴影

### Prefer
- 层级用 1px 边框 + 明度阶梯（#08090A → #101213 → #16181A）表达
- 强调色只给"一处视觉焦点/屏"：一个主 CTA 或一个高亮数据
- 杂志感靠**字号对比 + 留白**实现，不靠装饰元素堆砌

---

## 8. Responsive Behavior

### Breakpoints
| Name | Width | Behavior |
|------|-------|----------|
| Mobile | < 640px | 单列；Display 缩至 36px；导航折叠为抽屉 |
| Tablet | 640–1024px | 双列；文章列保持 680px 居中 |
| Desktop | > 1024px | 完整 1120px 容器 + 大留白 |

### Adaptation Rules
- 文章页任何断点下阅读列不超过 680px，优先保阅读体验
- 移动端区块留白从 96px 降为 48px（减半纪律）
- 后台表格在 < 1024px 允许横向滚动，不做卡片化改造
- 触摸目标 ≥ 44px

---

## 9. Agent Prompt Guide

### Key Instructions
- 生成门户页面时：先套 `.portal-dark` 语义变量，排版优先杂志手法（衬线大标题、680px 阅读列、96px 区块留白）
- 生成后台页面时：禁止手写颜色/圆角，一律引用 antd 组件 + antd-theme.json token
- 品牌紫双轨分工：门户链接/强调用 `--brand-400`（#7C85DC，暗底对比度），后台按钮/主色用 `--brand-600`（#4C56C0）
- 代码块必须比所在卡片**更深**（深色岛原则）
- 任何渐变仅限 `--brand-500 → --brand-700` 的同色系微渐变，且面积不超过屏幕 20%

### Quick CSS Snippet
```css
/* 最小可用令牌集，完整版见 tokens.css */
:root {
  --brand-400: #7C85DC; --brand-500: #5E6AD2; --brand-600: #4C56C0;
  --font-display: "Playfair Display", "Noto Serif SC", Georgia, serif;
  --font-body: "Inter", "Noto Sans SC", -apple-system, sans-serif;
  --font-mono: "JetBrains Mono", Consolas, monospace;
}
.portal-dark {
  --bg: #08090A; --surface: #101213; --code-bg: #0B0D0F;
  --border: rgba(255,255,255,.08);
  --text: #F7F8F8; --text-2: #8A8F98; --accent: #7C85DC;
}
.admin-light {
  --bg: #F5F5F7; --surface: #FFFFFF; --border: #E5E6EB;
  --text: #1F2329; --text-2: #646A73; --accent: #4C56C0;
}
```
