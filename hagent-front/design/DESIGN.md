# HAgent Design System (v2)

> AI-Native · Dark-First —— 深蓝板岩底色上的工程质感。代码暗色 + 运行绿。
> 唯一令牌源：`src/styles/tokens.css`（CSS 变量）+ `src/theme/naive.ts`（Naive UI 镜像覆盖，两者必须同步）。

---

## 1. Visual Theme

**Philosophy**: 像 AI 工程师的工作台 —— 深蓝板岩（非纯黑）提供沉稳底座，运行绿作为唯一"活性"信号，Aurora 光晕只在空态隐约呼吸，进入工作态立即收敛。
**Direction**: dark, precise, alive-in-idle, restrained
**Personality**: confident, geeky-but-tasteful, engineering-first
**Reference**: Linear（组件与层级行为） × Vercel（暗色明度阶梯） × Raycast（命令面板/快捷键文化）

**品牌符号 "H" 使用规范**：
- **Logo**：等宽字体（JetBrains Mono）大写 "H"，放在 `--accent` 圆角方块上；暗色主题下文字用 `#0f172a`，浅色下用 `#fff`
- **加载态**：首屏 Preloader "H" 呼吸动画（App.vue），替代通用 spinner
- **Favicon**：`public/favicon.svg`

---

## 2. Color Palette

### 核心（暗色默认 · 见 tokens.css :root）

| Token | Value | Usage |
|-------|-------|-------|
| --bg | #0f172a | 页面底色（深蓝板岩，禁纯黑） |
| --bg-elevated | #1b2336 | 卡片/输入框/主聊天区 |
| --bg-hover / --bg-active | #232e47 / #2b3752 | 悬停/激活面 |
| --border | rgba(148,163,184,.16) | 分隔线/描边 |
| --border-strong | rgba(148,163,184,.3) | hover/聚焦描边 |
| --text-1 / -2 / -3 | #f8fafc / #94a3b8 / #64748b | 主/次/弱文字 |

### 强调色（`<html data-accent>` 切换，默认 green）

| Accent | 主值 (暗) | 浅色加深 (600 级) | 语义 |
|--------|-----------|-------------------|------|
| green（默认） | #22c55e | #16a34a | 运行/成功/主 CTA |
| blue | #3b82f6 | #2563eb | 备选 |
| purple | #8b5cf6 | #7c3aed | 备选 |
| amber | #f59e0b | #d97706 | 备选 |

配套 token：`--accent-hover` `--accent-soft`（12% 透明底） `--accent-border`（35% 描边）。浅色主题一律加深一级保证对比度。

### 状态色
`--danger #ef4444` · `--warning #f59e0b` · `--info #38bdf8` · `--success #22c55e`

### 硬编码禁令
组件样式禁止出现十六进制色值面（`#fff`/`#1e293b` 等），一律走语义 token；仅状态色常量（如 warn `#b45309` / dark `#fbbf24`）与代码深色岛 `#0b0d0f` 允许出现。

---

## 3. Typography

### Font Stacks
- **Sans（全部 UI/正文/标题）**: `'Inter Variable', 'Inter', -apple-system, 'PingFang SC', 'Microsoft YaHei', sans-serif`
- **Mono（代码/标识/数字）**: `'JetBrains Mono', ui-monospace, Consolas, monospace`

> v2.1 起**衬线展示字体已退役**（v1 的 Playfair/Noto Serif 链路已移除）。标题层级靠字重（800 vs 400）+ 字号对比 + 负字距（-0.02em）表达。

### Scale（tokens.css）

| Level | Size | Usage |
|-------|------|-------|
| --text-4xl | 58px | 欢迎屏 Hero |
| --text-xl | 26px | 大标题 |
| --text-lg | 20px | 页面标题（PageHeader） |
| --text-base | 15px | 正文/消息 |
| --text-sm | 13px | 次级/侧栏 |
| --text-xs | 12px | 元信息/徽章 |
| 代码 | 12px / 1.65 | 代码块 |

---

## 4. Component Styles

### 聊天面（门户 · 核心面）
- **主区背景**：`--bg-elevated`；欢迎态（未选会话）用 `color-mix(--bg-elevated 72%, transparent)` 半透明透出底层 Aurora，进入会话/出现消息后恢复不透明
- **欢迎屏**：badge 胶囊 → Inter 800 大标题（`em` 走 accent 渐变文字）→ 副标题 → hero 输入框（圆角 16px、focus 时 `--accent-border` + 3px `--accent-soft` 光环）→ kbd 快捷键提示 → 推荐问题胶囊（RecommendedQuestions，点击即发送）；各元素 60–80ms 依次 fade-up 入场
- **用户气泡**：`--accent-soft` 底 + `--accent-border` 描边，圆角 `16px 16px 4px 16px`，右侧最大 78% 宽；内嵌文档卡（中性灰图标 + 文件名 + 类型·大小）
- **AI 回复**：去气泡全宽排版；正文 `--text-base`/1.7；工具徽章 mono 胶囊行；思考块（见下）；回答底部复制/重新生成/来源胶囊（source-pill：accent-soft 渐变底、hover 上浮 1px）
- **思考块**：圆角 10px 卡片；流式时标题呼吸渐变（text shimmer）+ 脉冲点；暗色主题加 `--border-strong` 描边 + 左侧 2px `--accent-border` 竖线与主区拉层级
- **代码块（深色岛原则）**：恒定 `#0b0d0f`，比所在卡片更深，顶栏 12px 语言标签 + 复制按钮；明暗主题不变
- **输入栏**：圆角 16px；顶部 18px 渐隐遮罩柔化消息滚入；context 环形用量（70% 黄 / 90% 红）hover 弹 pill、点击弹明细卡

### 会话侧栏
- 展开 280px / 收起 64px 图标窄栏；时间分组（今天/7 天/30 天/更早）；行操作用 @vicons/antd SVG 图标（重命名/导出/删除两步确认）；演示徽章走 accent token

### 后台（Admin · Naive UI）
- 页头统一用 `src/components/ui/PageHeader.vue`（title + description + #actions 插槽），禁止各页手写 head
- 页面级卡片圆角 `--radius-md`（14px）；表格走 n-data-table；手写色值禁令同上
- 布局：sider 220px（收起 64px）+ 顶栏 56px + 内容区 padding 24/32

### 按钮
- Primary：`--accent` 底；暗色文字 `#0f172a`，浅色 `#fff`；hover 上浮 1px + `--shadow-accent`
- Secondary / Ghost / Danger：见 global.css `.hs-btn--*`

---

## 5. Layout

| 区 | 值 |
|----|----|
| 聊天内容列 | max-width 840px 居中 |
| 会话侧栏 | 280px / 收起 64px |
| 后台 sider | 220px / 收起 64px |
| 顶栏 | 56px，底边 1px `--border` hairline |
| 容器 | --container 1120px（通用） |

间距 4px 基准（--space-1…9），圆角 --radius-sm 8 / md 14 / lg 20 / full 999。

---

## 6. Depth & Elevation & Motion

- 暗色层级优先 1px 边框 + 明度阶梯（#0f172a → #1b2336 → #232e47），投影克制
- Aurora 背景（HsBackground，z-index -2）+ 粒子（HsParticles，z -1）常驻底层；仅欢迎态/登录页可见
- Motion：`--dur-fast 120ms`（hover/焦点）· `--dur 200ms` · `--dur-slow 350ms`（主题/背景过渡）· `--ease cubic-bezier(.4,0,.2,1)`
- 动效仅用 transform/opacity/filter；所有动画尊重 `prefers-reduced-motion`
- 页面切换：View Transitions API（支持时）或 Vue fade+上移 250ms

---

## 7. Cautions

### Never Do
- 禁止衬线字体回归 UI（v1 遗留引用已全部清除）
- 禁止组件内硬编码背景/文字色（token 化，见第 2 节禁令）
- 禁止标题霓虹 glow（text-shadow 大光晕已废弃清理）
- 禁止纯黑 #000 / 纯白 #FFF 大面积使用
- 禁止卡片 hover 加发光阴影（位移 + 边框变化即可）
- 禁止新增 z-index 魔法数字（用 tokens.css 的 --z-* 阶梯）

### Prefer
- 层级 = 边框 + 明度阶梯；强调色每屏只给一处视觉焦点
- 字符图标（＋⌕≡✎↓✕）一律换成 @vicons/antd SVG
- 新后台页面：PageHeader + n-card（radius-md）+ 既有 token，不发明新样式

---

## 8. Responsive Behavior

| 断点 | 行为 |
|------|------|
| < 640px | 欢迎标题 clamp 收缩；to-bottom 下移 |
| < 768px | 登录卡单栏，品牌区折叠 |
| < 900px | 聊天侧栏隐藏（⚠ 已知缺口：移动端暂无会话切换入口，待办） |
| < 1024px | 后台表格横向滚动，不做卡片化 |

触摸目标 ≥ 32px（侧栏行 38px、图标钮 32–40px）。

---

## 9. Changelog & 归档

- **v2.1（本次）**：衬线链路清除（--font-display/Noto Serif 引用移除）；暗色硬编码 token 化；字符图标 → SVG；欢迎屏接入推荐问题 + stagger 入场；欢迎态半透明透出 Aurora；顶栏 hairline + 输入栏渐隐遮罩；思考块暗色对比度；后台 PageHeader 统一 + 卡片圆角收敛；死样式清理
- **v2.0**：AI-Native 绿调体系上线（tokens.css 重写），门户收敛为"对话即产品"单页
- **v1（已归档）**：品牌紫 #5E6AD2 × Playfair 编辑排版。`design/tokens.css` 已指针化归档；`design/antd-theme.json`、`design/prototype/` 为 v1/antd 遗留历史参考，项目实际使用 Naive UI（`src/theme/naive.ts`），后续可清理
