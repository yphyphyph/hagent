<template>
  <div ref="listRef" class="msg-list" @scroll.passive="onScroll">
    <!-- 欢迎屏：无消息且非加载时 -->
    <div v-if="!loading && messages.length === 0 && showWelcome" class="welcome">
      <span class="welcome__badge">HAgent · Agentic AI</span>
      <h1 class="welcome__title">把问题交给<br /><em>会思考的助手</em></h1>
      <p class="welcome__subtitle">
        知识库问答 · 数据洞察 · 工具编排 —— 每一次回答都有据可循
      </p>

      <!-- hero 附件：图片 chips 条（输入框上方）+ 回形针入口，与 ChatInputBar 同款交互 -->
      <div class="welcome__compose">
        <div v-if="images.length > 0 || docUploads.length > 0 || docs.length > 0" class="attach-chips">
          <div v-for="(img, idx) in images" :key="img.id" class="attach-chip">
            <img class="attach-chip__thumb" :src="img.dataUrl" :alt="img.name" />
            <span class="attach-chip__name" :title="img.name">{{ img.name }}</span>
            <button
              type="button"
              class="attach-chip__remove"
              :aria-label="`移除图片 ${img.name}`"
              @click="removeImage(idx)"
            >×</button>
          </div>
          <div v-for="doc in docUploads" :key="doc.id" class="attach-chip attach-chip--doc" role="status">
            <svg class="attach-chip__doc-icon" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
              <path d="M14 2v6h6" />
            </svg>
            <span class="attach-chip__name" :title="doc.name">{{ doc.name }}</span>
            <span class="attach-chip__progress">{{ doc.percent }}%</span>
          </div>
          <!-- 文档成功态 chip：已入库并向量化，随消息发送；点 × 仅移除本地 chip，知识库条目保留 -->
          <div v-for="(doc, idx) in docs" :key="doc.id" class="attach-chip attach-chip--doc">
            <svg class="attach-chip__doc-icon" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
              <path d="M14 2v6h6" />
            </svg>
            <span class="attach-chip__name" :title="doc.name">{{ doc.name }}</span>
            <button
              type="button"
              class="attach-chip__remove"
              :title="`移除 ${doc.name}（仅移除卡片，知识库文件保留）`"
              :aria-label="`移除文档 ${doc.name}（仅移除卡片，知识库文件保留）`"
              @click="removeDoc(idx)"
            >×</button>
          </div>
        </div>
        <div class="welcome__input-wrap">
          <textarea
            ref="heroInputRef"
            v-model="draft"
            class="welcome-input"
            rows="1"
            placeholder="输入你的问题，Enter 发送…"
            @compositionstart="composing = true"
            @compositionend="composing = false"
            @keydown.enter.exact.prevent="onHeroEnter"
            @input="autoResize"
          ></textarea>
          <!-- 附件入口：点击弹二选一菜单（上传文件 / 从知识库选择），访客菜单仅上传项走登录引导 -->
          <span class="attach-anchor">
            <button
              type="button"
              class="attach-btn"
              :class="{ 'is-loading': docUploading }"
              :title="docUploading ? `正在上传 ${docUploads[0].name} ${docUploads[0].percent}%` : '添加图片或文档'"
              :aria-label="docUploading ? '文档上传中' : '添加图片或文档'"
              :aria-haspopup="true"
              :aria-expanded="attachMenuOpen"
              @click="attachMenuOpen = !attachMenuOpen"
            >
              <svg v-if="!docUploading" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48" />
              </svg>
              <span v-else class="attach-btn__spinner" aria-hidden="true"></span>
            </button>
            <AttachMenu
              v-if="attachMenuOpen"
              :guest="guest"
              @upload="onMenuUpload"
              @knowledge="onMenuKnowledge"
              @close="attachMenuOpen = false"
            />
          </span>
          <!-- 隐藏文件选择器：accept 与 ChatInputBar 一致（图片 / 知识库文档） -->
          <input
            ref="fileInputRef"
            type="file"
            class="attach-input"
            multiple
            accept="image/*,.md,.txt,.pdf,.doc,.docx"
            @change="onFilesPicked"
          />
          <button class="welcome-send" type="button" :disabled="!draft.trim() || imageUploading" :title="imageUploading ? imageUploadStatus : '发送'" @click="submitDraft">
            {{ imageUploading ? '上传中' : '发送' }}
          </button>
        </div>
      </div>

      <p class="welcome__hint"><kbd>Enter</kbd> 发送 · <kbd>Shift</kbd> + <kbd>Enter</kbd> 换行</p>

      <!-- 推荐问题：点击即发送（访客点击走登录引导） -->
      <div class="welcome__questions fade-up">
        <RecommendedQuestions :questions="welcomeQuestions" @select="onPickQuestion" />
      </div>

      <p v-if="guest" class="welcome__guest-tip">
        访客模式：对话不会被保存，<router-link to="/login">登录</router-link>后可保留历史会话
      </p>
    </div>

    <!-- 消息流 -->
    <template v-else>
      <div class="msg-stream">
        <div v-if="loading" class="msg-loading">会话加载中…</div>

        <div
          v-for="m in messages"
          :key="m.id"
          class="msg-row fade-up"
          :class="m.role === 'user' ? 'msg-row--user' : 'msg-row--ai'"
        >
          <!-- 用户消息：右侧气泡（文档卡片行与图片缩略图行在文字上方，文档卡片有 url 时新窗口打开） -->
          <template v-if="m.role === 'user' && (m.content || m.images?.length || m.docs?.length)">
            <div class="user-row">
              <div class="user-bubble">
                <!-- 文档卡片行：对标豆包样式（左彩色图标 + 文件名截断 + 类型·大小副标题） -->
                <div v-if="m.docs?.length" class="user-bubble__docs">
                  <a
                    v-for="(doc, i) in m.docs"
                    :key="i"
                    class="doc-card"
                    :href="doc.url || undefined"
                    :target="doc.url ? '_blank' : undefined"
                    :rel="doc.url ? 'noopener noreferrer' : undefined"
                    :title="doc.url ? `${doc.name}（新窗口打开）` : doc.name"
                  >
                    <span class="doc-card__icon" aria-hidden="true">
                      <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                        <path d="M14 2v6h6" />
                      </svg>
                      <span class="doc-card__ext">{{ docExtLabel(doc.name) }}</span>
                    </span>
                    <span class="doc-card__body">
                      <span class="doc-card__name">{{ doc.name }}</span>
                      <span class="doc-card__meta">{{ docTypeLabel(doc.name) }}<template v-if="doc.size"> · {{ formatDocSize(doc.size) }}</template></span>
                    </span>
                  </a>
                </div>
                <div v-if="m.images?.length" class="user-bubble__images">
                  <img
                    v-for="(img, i) in m.images"
                    :key="i"
                    class="user-bubble__img"
                    :src="img.url"
                    :alt="img.name || '图片'"
                    @click="openImagePreview(img.url)"
                  />
                </div>
                <div v-if="m.content" class="user-bubble__text">{{ m.content }}</div>
              </div>
              <!-- hover 操作区：编辑重发（仅已落库带 DB id 的消息）+ 复制，并排淡入 -->
              <div class="user-actions">
                <button
                  v-if="canEditMessage(m)"
                  class="user-copy user-edit"
                  type="button"
                  title="编辑重发"
                  aria-label="编辑重发"
                  @click="emit('edit', m)"
                >
                  <n-icon :component="EditOutlined" />
                </button>
                <button
                  class="user-copy"
                  :class="{ 'user-copy--done': copiedId === String(m.id) }"
                  type="button"
                  :disabled="!m.content"
                  :title="copiedId === String(m.id) ? '已复制' : '复制'"
                  @click="copyMessageContent(m)"
                >
                  <n-icon :component="copiedId === String(m.id) ? CheckOutlined : CopyOutlined" />
                </button>
              </div>
            </div>
          </template>

          <!-- AI 回复：去气泡全宽排版（思考中的空内容也需立即渲染，避免 7s 空白） -->
          <div v-else-if="m.role === 'ai' && (m.content || m.tools?.length || m.thinkingContent !== undefined || m.sources?.length)" class="ai-msg">
            <!-- 工具徽章行：去重后展示，同名工具合并为一次并追加 xN 计数 -->
            <div v-if="m.tools?.length" class="tool-row">
              <span v-for="badge in dedupedTools(m.tools)" :key="badge.name" class="tool-chip">
                <span class="tool-chip__dot"></span>{{ badge.label }}{{ badge.count > 1 ? ` x${badge.count}` : '' }}
              </span>
            </div>

            <!-- 思考过程折叠块：唯一进度载体，首个 thinking 信号即渲染（标题“思考中…”空内容起步），随 thinking/tool 事件递增标题与内容 -->
            <div v-if="shouldShowThinkingBlock(m)" class="thinking-block">
              <button class="thinking-block__header" type="button" @click="toggleThinking(m)">
                <span class="thinking-block__icon" :class="{ 'is-expanded': isExpanded(m) }">▸</span>
                <!-- 思考标题脉冲点：流式进行时带动画，思考结束时静止 -->
                <span class="thinking-title-dot" :class="{ 'thinking-title-dot--pulse': isThinkingActive(m) }"></span>
                <!-- 思考标题：主标题黑粗（思考中…/思考结束/思考过程），副标题灰色展示阶段，避免重复；思考中时标题带呼吸渐变 -->
                <span
                  class="thinking-block__title thinking-block__title--main"
                  :class="{ 'thinking-block__title--breathing': isThinkingActive(m) }"
                  >{{ getThinkingMainTitle(m) }}</span
                >
                <span v-if="getThinkingStageSubtitle(m)" class="thinking-block__subtitle">{{ getThinkingStageSubtitle(m) }}</span>
                <span class="thinking-block__hint">{{ isExpanded(m) ? '收起' : '展开' }}</span>
              </button>
              <div
                v-if="isExpanded(m)"
                ref="thinkingBodyRefs"
                class="thinking-block__body md-body"
                @click="onContentClick($event)"
              >
                <!-- 首个 thinking 信号时内容为空，显示脉冲点占位，后续增量追加 -->
                <div v-if="m.thinkingContent" v-html="renderMarkdown(m.thinkingContent)"></div>
                <div v-else class="thinking-block__placeholder thinking-block__placeholder--empty">
                  <!-- 空思考内容时的即时反馈：脉冲点 + 文案，避免 7s 空白 -->
                  <span class="thinking-dots"><i></i><i></i><i></i></span>
                </div>
              </div>
            </div>

            <!-- 正文（支持行内引用角标：[1] 点击可打开抽屉） -->
            <div
              class="md-body ai-content"
              v-html="renderWithCitations(m)"
              @click="onContentClick($event)"
            ></div>

            <!-- 溯源聚合胶囊（风格：紧凑 pill 排列于搜索块底部，点击打开抽屉） -->
            <!-- 复制回答 / 重新生成 / 引用胶囊同行：无 sources 时仅渲染复制按钮，保证总有入口 -->
            <div class="source-pill-bar">
              <button
                class="ai-copy"
                :class="{ 'ai-copy--done': copiedId === String(m.id) }"
                type="button"
                :disabled="!m.content || (props.streaming && isLastAiMessage(m))"
                :title="!m.content ? '内容为空' : '复制回答原文'"
                @click="copyMessageContent(m)"
              >
                {{ copiedId === String(m.id) ? '已复制 ✓' : '复制回答' }}
              </button>
              <!-- 重新生成：仅最后一条 AI 回复且非流式时可见（访客/演示会话隐藏，由 useChat 守卫兜底） -->
              <button
                v-if="canRegenerate(m)"
                class="ai-copy regen-btn"
                type="button"
                title="重新生成此回复"
                @click="emit('regenerate')"
              >
                <n-icon :component="RedoOutlined" class="regen-btn__icon" />
                重新生成
              </button>
              <button
                v-if="m.sources?.length"
                class="source-pill"
                type="button"
                :title="`${m.sources.length}个来源，点击查看`"
                @click="openDrawer(m, 0)"
              >
                <!-- 文档图标 -->
                <n-icon :component="FileTextOutlined" class="source-pill__icon" />
                <span class="source-pill__text">{{ m.sources.length }}个来源</span>
                <!-- 优雅箭头（替代字符 >） -->
                <n-icon :component="RightOutlined" class="source-pill__arrow" />
              </button>
            </div>

            <!-- 回答耗时统计（仅展示：毫秒转秒，保留1位小数） -->
            <div v-if="m.elapsedMs !== undefined" class="ai-meta">
              <span>回答耗时 {{ (m.elapsedMs / 1000).toFixed(1) }} 秒</span>
            </div>

          </div>
        </div>

        <!-- 已移除独立“AI 思考中”占位（3 绿点），由上方 thinking-block 统一承载，避免视觉跳变 -->
      </div>
    </template>

    <!-- 知识库选择器（hero 路径）：多选后映射为文档成功态 chips，随消息发送 -->
    <KnowledgePickerModal
      :visible="kbPickerOpen"
      @close="kbPickerOpen = false"
      @confirm="onKbConfirm"
    />

    <!-- 图片放大预览遮罩：点击遮罩或关闭按钮退出 -->
    <div v-if="previewUrl" class="img-preview-mask" role="dialog" aria-label="图片预览" @click="closeImagePreview">
      <img class="img-preview__img" :src="previewUrl" alt="图片预览" @click.stop />
      <button type="button" class="img-preview__close" aria-label="关闭预览" @click.stop="closeImagePreview">×</button>
    </div>

    <!-- 溯源抽屉 -->
    <SourceDrawer
      :visible="drawerVisible"
      :sources="drawerSources"
      :active-index="drawerIndex"
      @close="drawerVisible = false"
      @jump="onJumpToSource"
    />

    <!-- 回到底部：上翻超 80px 且消息较多时出现，点击瞬时回到最新 -->
    <button
      v-if="!stickToBottom && messages.length > 3"
      class="to-bottom"
      type="button"
      title="回到底部"
      aria-label="回到底部"
      @click="scrollToBottom(true)"
    >
      <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
        <path
          d="M4 6l4 4 4-4"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </svg>
    </button>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NIcon, useMessage } from 'naive-ui'
import { FileTextOutlined, RightOutlined, CopyOutlined, CheckOutlined, EditOutlined, RedoOutlined } from '@vicons/antd'
import { renderMarkdown } from '@/utils/markdown'
import type { UiChatMessage, ChatSource } from '@/composables/useChat'
import type { ChatAttachment } from '@/api/chat'
import type { KnowledgeFile } from '@/api/knowledge'
import { useChatAttachments } from '@/composables/useChatAttachments'
import { docTypeLabel, docExtLabel, formatDocSize } from '@/utils/chatAttachments'
import AttachMenu from '@/components/chat/AttachMenu.vue'
import KnowledgePickerModal from '@/components/chat/KnowledgePickerModal.vue'
import SourceDrawer from '@/components/chat/SourceDrawer.vue'
import RecommendedQuestions from '@/components/chat/RecommendedQuestions.vue'

const props = withDefaults(
  defineProps<{
    messages: UiChatMessage[]
    loading?: boolean
    thinking?: boolean
    stageText?: string
    streaming?: boolean
    guest?: boolean
    showWelcome?: boolean
    /** 动态思考标题（来自 useChat.thinkingTitle，8 阶段之一） */
    thinkingTitle?: string
  }>(),
  { loading: false, thinking: false, stageText: '', streaming: false, guest: false, thinkingTitle: '', showWelcome: true }
)

const emit = defineEmits<{
  (e: 'send', text: string, attachments?: ChatAttachment[]): void
  (e: 'regenerate'): void
  (e: 'edit', message: UiChatMessage): void
  (e: 'login-request'): void
}>()

/** 重新生成按钮可见性：仅最后一条 AI 回复、非流式、非访客（访客/演示会话由 useChat 守卫兜底拒绝） */
function canRegenerate(m: UiChatMessage): boolean {
  return isLastAiMessage(m) && !props.streaming && !props.thinking && !props.guest
}

/** 编辑按钮可见性：仅带后端 DB id（number）的 USER 消息可编辑，新生成的内存消息需刷新后才有 id */
function canEditMessage(m: UiChatMessage): boolean {
  return !props.guest && typeof m.id === 'number'
}

/* ---------- 草稿与发送（欢迎屏 hero 输入框） ---------- */
const draft = ref('')
const heroInputRef = ref<HTMLTextAreaElement | null>(null)
const composing = ref(false)

/* ---------- hero 附件：与 ChatInputBar 共用 useChatAttachments（图片 chips 发送前上传 OSS / 文档直接上传知识库，成功态 chip 随消息发送） ---------- */
const { images, docs, docUploads, docUploading, imageUploading, imageUploadStatus, fileInputRef, onFilesPicked, removeImage, removeDoc, addKnowledgeDocs, clearAttachments, uploadAndBuildAttachments } = useChatAttachments({
  isGuest: () => props.guest,
  onGuestAttach: () => emit('login-request'),
})

/* ---------- 附件来源菜单（hero）：上传文件 / 从知识库选择 ---------- */
const attachMenuOpen = ref(false)
const kbPickerOpen = ref(false)

/** 菜单「上传文件」：访客走登录引导（与 ChatInputBar 同语义），已登录打开文件选择器 */
function onMenuUpload() {
  attachMenuOpen.value = false
  if (props.guest) {
    emit('login-request')
    return
  }
  fileInputRef.value?.click()
}

/** 菜单「从知识库选择」：打开选择器 modal（访客不可见该菜单项） */
function onMenuKnowledge() {
  attachMenuOpen.value = false
  kbPickerOpen.value = true
}

/** 选择器确定：选中文件映射为文档成功态 chips（url 取 fileUrl，不再重复上传） */
function onKbConfirm(files: KnowledgeFile[]) {
  kbPickerOpen.value = false
  addKnowledgeDocs(files)
}

/** 发送（hero）：有图片 chips 时先并发上传 OSS（按钮 loading「图片上传中 x/N」），文档 chips 已在选中时上传知识库；失败中止发送保留 chips 重试 */
async function submitDraft() {
  const text = draft.value.trim()
  if (!text || composing.value || imageUploading.value) return
  let attachments: ChatAttachment[] | undefined
  try {
    attachments = await uploadAndBuildAttachments()
  } catch {
    // 失败 toast 已由 request 拦截器统一提示；草稿与 chips 保留，支持重试
    return
  }
  // 图片与文档 chips 随消息发送（契约同 ChatInputBar）；清空时机由父组件在 chat.send 成功后调 clearHeroCompose
  emit('send', text, attachments)
}

/** 发送成功后由父组件调用：清空 hero 草稿与图片 chips（失败时保留，支持重试） */
function clearHeroCompose() {
  draft.value = ''
  clearAttachments()
  const el = heroInputRef.value
  if (el) el.style.height = 'auto'
}

function onHeroEnter() {
  if (composing.value) return
  submitDraft()
}

/* ---------- 欢迎屏推荐问题：点击即发送（访客与 hero 输入同一登录门控语义） ---------- */
const welcomeQuestions = [
  '根据知识库总结 HAgent 的架构设计',
  '检索知识库：HAgent 如何部署与配置？',
  '统计最近 7 天系统操作日志的趋势',
]
function onPickQuestion(text: string) {
  if (props.guest) {
    emit('login-request')
    return
  }
  emit('send', text)
}

/** textarea 高度自适应 */
function autoResize(e: Event) {
  const el = e.target as HTMLTextAreaElement
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 160)}px`
}

/* ---------- 思考过程折叠状态（默认展开） ---------- */
// 以消息 id 为 key 的展开映射；优先使用消息自身的 thinkingExpanded 字段，兼容本地状态
const expandedMap = reactive<Record<string, boolean>>({})
// 思考块内容容器引用（用于增量滚动）
const thinkingBodyRefs = ref<HTMLElement[]>([])

/** 判断是否被屏蔽的阶段关键词（执行查询内部阶段不展示，生成查询已纳入 8 阶段） */
const BLOCKED_STAGE_KEYWORDS = ['执行查询']
function isBlockedStage(text: string): boolean {
  return BLOCKED_STAGE_KEYWORDS.some((k) => text.includes(k))
}

// 8 阶段动态标题（与 useChat 及后端保持一致）
const THINKING_TITLES = [
  '思考中',
  '正在理解问题',
  '正在分析问题',
  '制定解题策略',
  '检索知识库',
  '生成查询 SQL',
  '构建输出结构',
  '最终润色',
] as const
const THINK_DONE_TITLE = '思考结束'
const STAGE_TO_TITLE: Record<string, string> = {
  '思考中': THINKING_TITLES[0],
  '正在理解问题': THINKING_TITLES[1],
  '正在理解问题...': THINKING_TITLES[1],
  '正在分析问题': THINKING_TITLES[2],
  '正在分析问题...': THINKING_TITLES[2],
  '制定解题策略': THINKING_TITLES[3],
  '检索知识库': THINKING_TITLES[4],
  '生成查询 SQL': THINKING_TITLES[5],
  '构建输出结构': THINKING_TITLES[6],
  '正在组织答案': THINKING_TITLES[6],
  '正在组织答案…': THINKING_TITLES[6],
  '最终润色': THINKING_TITLES[7],
  '思考结束': THINK_DONE_TITLE,
}
/** 将 stage 文案归一为 8 阶段标题（用于标题动态切换兜底） */
function normalizeStageToTitle(stage: string): string | null {
  if (!stage) return null
  const t = stage.trim()
  if (STAGE_TO_TITLE[t]) return STAGE_TO_TITLE[t]
  if (t.includes('理解')) return THINKING_TITLES[1]
  if (t.includes('分析')) return THINKING_TITLES[2]
  if (t.includes('策略')) return THINKING_TITLES[3]
  if (t.includes('检索')) return THINKING_TITLES[4]
  if (t.includes('SQL') || t.includes('查询')) return THINKING_TITLES[5]
  if (t.includes('结构') || t.includes('组织')) return THINKING_TITLES[6]
  if (t.includes('润色')) return THINKING_TITLES[7]
  if (t.includes('结束')) return THINK_DONE_TITLE
  return t
}
/** 获取当前消息的思考块动态标题（单折叠块标题随流式阶段切换，保留用于兼容旧逻辑与脉冲判断） */
function getThinkingTitle(m: UiChatMessage): string {
  // 流式进行中的最后一条 AI 消息：优先使用父组件传入的 thinkingTitle（useChat 驱动的 8 阶段标题）
  if (isLastAiMessage(m) && (props.streaming || props.thinking)) {
    if (props.thinkingTitle && props.thinkingTitle.trim()) return props.thinkingTitle
    if (props.stageText) {
      const normalized = normalizeStageToTitle(props.stageText)
      if (normalized) return normalized
    }
    return THINKING_TITLES[0]
  }
  // 流式结束后：若存在思考内容则显示“思考结束”，便于区分已完成状态
  if (m.thinkingContent && m.thinkingContent.length > 0) {
    if (isLastAiMessage(m) && !props.streaming && !props.thinking) return THINK_DONE_TITLE
    return THINK_DONE_TITLE
  }
  return THINKING_TITLES[0]
}

/** 获取思考块主标题：流式时为“思考中…”，结束后为“思考结束”，历史消息为“思考过程” */
function getThinkingMainTitle(m: UiChatMessage): string {
  // 流式活跃中的最后一条 AI 消息：优先使用 thinkingTitle 驱动的 8 阶段首项“思考中…”，保证首信号即有标题
  if (isLastAiMessage(m) && (props.streaming || props.thinking)) {
    // 若上层已传入 thinkingTitle 且处于初始阶段，直接返回“思考中…”
    if (props.thinkingTitle && props.thinkingTitle.trim()) {
      const t = props.thinkingTitle.trim()
      // 初始阶段保持“思考中…”不被副标题重复覆盖
      if (t === THINKING_TITLES[0] || t === '思考中' || t === '思考中…') return THINKING_TITLES[0]
      // 后续阶段标题由副标题承载，主标题仍保持“思考中…”作为前缀，避免标题跳变
      return THINKING_TITLES[0]
    }
    return THINKING_TITLES[0]
  }
  // 有思考内容且流式已结束：最后一条显示“思考结束”，历史消息显示“思考过程”
  if (m.thinkingContent && m.thinkingContent.length > 0) {
    if (isLastAiMessage(m) && !props.streaming && !props.thinking) return THINK_DONE_TITLE
    return '思考过程'
  }
  // 无思考内容但处于流式占位阶段
  if (isLastAiMessage(m) && (props.streaming || props.thinking)) return THINKING_TITLES[0]
  return '思考过程'
}

/** 获取思考块阶段副标题：仅流式活跃时展示阶段，结束后不展示，避免与主标题重复 */
function getThinkingStageSubtitle(m: UiChatMessage): string | null {
  if (!isLastAiMessage(m)) return null
  if (!props.streaming && !props.thinking) return null
  // 屏蔽阶段不展示
  const rawStage = props.thinkingTitle?.trim() ? props.thinkingTitle : props.stageText
  if (!rawStage) return null
  if (isBlockedStage(rawStage)) return null
  const normalized = normalizeStageToTitle(rawStage)
  if (!normalized) return null
  // 主标题已为“思考中”，过滤通用“思考中…”避免重复
  if (normalized === THINKING_TITLES[0]) return null
  if (normalized === THINK_DONE_TITLE) return null
  if (normalized === '思考中' || normalized === '思考中…') return null
  return normalized
}

function isExpanded(m: UiChatMessage): boolean {
  const key = String(m.id)
  // 若消息自带 thinkingExpanded 则以其为准，否则查本地 map；默认展开
  if (typeof m.thinkingExpanded === 'boolean') return m.thinkingExpanded
  if (key in expandedMap) return !!expandedMap[key]
  return true
}

function toggleThinking(m: UiChatMessage): void {
  const key = String(m.id)
  // 同步写回消息对象与本地映射，保持响应式
  const next = !isExpanded(m)
  // 优先写回消息字段（useChat 提供的可变对象）
  ;(m as UiChatMessage).thinkingExpanded = next
  expandedMap[key] = next
}

/* ---------- 思考块显隐与阶段副标题（单一收敛载体） ---------- */
function isLastAiMessage(m: UiChatMessage): boolean {
  for (let i = props.messages.length - 1; i >= 0; i--) {
    if (props.messages[i].role === 'ai') return String(props.messages[i].id) === String(m.id)
  }
  return false
}
function showStageSubtitle(m: UiChatMessage): boolean {
  if (!props.stageText) return false
  if (isBlockedStage(props.stageText)) return false
  if (!isLastAiMessage(m)) return false
  // 标题已由 thinkingTitle 动态驱动时，不再重复显示副标题，避免标题与副标题同文
  if (props.thinkingTitle && props.thinkingTitle.trim()) {
    const titleNorm = normalizeStageToTitle(props.thinkingTitle)
    const stageNorm = normalizeStageToTitle(props.stageText)
    if (titleNorm && stageNorm && titleNorm === stageNorm) return false
    // 若标题处于 8 阶段序列中，整体隐藏副标题，标题即为唯一载体
    if ((THINKING_TITLES as readonly string[]).includes(props.thinkingTitle) || props.thinkingTitle === THINK_DONE_TITLE) return false
  }
  return !!(props.streaming || props.thinking)
}
function shouldShowThinkingBlock(m: UiChatMessage): boolean {
  if (m.thinkingContent && m.thinkingContent.length > 0) return true
  // 流式进行中的最后一条 AI 消息，即使尚无 thinkingContent，也展示思考块（以阶段副标题为初始状态）
  if (isLastAiMessage(m) && (props.thinking || props.streaming)) return true
  return false
}

/** 判断思考标题是否处于活跃流式状态（用于脉冲点动画：streaming 时脉冲，思考结束时静止） */
function isThinkingActive(m: UiChatMessage): boolean {
  const main = getThinkingMainTitle(m)
  // 仅主标题为“思考中…”且流式进行时脉冲，思考结束/思考过程静止
  if (main !== THINKING_TITLES[0]) return false
  // 仅最后一条 AI 消息且处于流式状态时脉冲
  return !!props.streaming && isLastAiMessage(m)
}

/* 首次收到 thinking 首片时自动展开；流式结束后自动收起，满足“默认展开、完成后收起” */
watch(
  () => props.messages.map((m) => (m.thinkingContent ?? '').length).join(','),
  (curr, prev) => {
    if (!prev) return
    const currLens = curr.split(',').map((v) => Number(v))
    const prevLens = prev.split(',').map((v) => Number(v))
    props.messages.forEach((m, idx) => {
      const cl = currLens[idx] ?? 0
      const pl = prevLens[idx] ?? 0
      if (cl > 0 && pl === 0) {
        const key = String(m.id)
        ;(m as UiChatMessage).thinkingExpanded = true
        expandedMap[key] = true
      }
    })
  },
  { flush: 'post' }
)
watch(
  () => props.streaming,
  (streaming, prev) => {
    if (prev && !streaming) {
      const last = [...props.messages].reverse().find((m) => m.role === 'ai' && m.thinkingContent)
      if (last) {
        const key = String(last.id)
        setTimeout(() => {
          ;(last as UiChatMessage).thinkingExpanded = false
          expandedMap[key] = false
        }, 800)
      }
    }
  },
  { flush: 'post' }
)
// thinking 标志结束时亦收起（兼容无 streaming 的场景）
watch(
  () => props.thinking,
  (thinking, prev) => {
    if (prev && !thinking && !props.streaming) {
      const last = [...props.messages].reverse().find((m) => m.role === 'ai' && m.thinkingContent)
      if (last) {
        const key = String(last.id)
        setTimeout(() => {
          ;(last as UiChatMessage).thinkingExpanded = false
          expandedMap[key] = false
        }, 800)
      }
    }
  },
  { flush: 'post' }
)

/* ---------- 工具徽章文案映射 ---------- */
const TOOL_LABELS: Record<string, string> = {
  searchKnowledge: '检索知识库',
  queryBusinessData: '生成查询 SQL',
  analyze_image: '分析图片',
}
function toolLabel(name: string): string {
  return TOOL_LABELS[name] ?? name
}

// 去重并计数：按首次出现顺序合并同名工具，返回用于徽章渲染的列表
function dedupedTools(tools?: string[]): Array<{ name: string; count: number; label: string }> {
  if (!tools?.length) return []
  const counter = new Map<string, number>()
  const order: string[] = []
  for (const t of tools) {
    if (!counter.has(t)) {
      counter.set(t, 1)
      order.push(t)
    } else {
      counter.set(t, (counter.get(t) ?? 0) + 1)
    }
  }
  return order.map((name) => ({
    name,
    count: counter.get(name) ?? 1,
    label: toolLabel(name),
  }))
}

/* ---------- 代码块复制与行内引用点击（事件委托） ---------- */
async function onContentClick(e: MouseEvent) {
  const target = e.target as HTMLElement
  // 行内引用角标点击：优先处理
  const cite = target.closest('.source-badge-inline') as HTMLElement | null
  if (cite) {
    const idx = Number(cite.dataset.index ?? '-1')
    const msgId = cite.dataset.msg ?? ''
    if (!Number.isNaN(idx) && idx >= 0) {
      // 通过消息 id 查找对应的消息对象
      const msg = props.messages.find((m) => String(m.id) === msgId)
      if (msg) openDrawer(msg, idx)
      else {
        // 兜底：若找不到则用当前抽屉源直接跳转
        if (drawerSources.value.length > idx) {
          drawerIndex.value = idx
          drawerVisible.value = true
        }
      }
    }
    return
  }
  const btn = target.closest('.code-copy') as HTMLButtonElement | null
  if (!btn) return
  const code = btn.closest('.code-block')?.querySelector('code')
  if (!code) return
  try {
    await navigator.clipboard.writeText(code.textContent ?? '')
    btn.textContent = '已复制'
    btn.classList.add('code-copy--done')
    setTimeout(() => {
      btn.textContent = '复制'
      btn.classList.remove('code-copy--done')
    }, 1500)
  } catch {
    btn.textContent = '复制失败'
    setTimeout(() => (btn.textContent = '复制'), 1500)
  }
}

/* ---------- 回答/气泡复制（复用 .code-copy 成功/失败范式） ---------- */
const copiedId = ref<string | null>(null)
let copiedTimer: ReturnType<typeof setTimeout> | null = null

function markCopied(m: UiChatMessage): void {
  copiedId.value = String(m.id)
  if (copiedTimer) clearTimeout(copiedTimer)
  copiedTimer = setTimeout(() => {
    copiedId.value = null
  }, 2000)
}

/** 非安全上下文兜底：textarea + execCommand */
async function fallbackCopy(text: string): Promise<boolean> {
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.setAttribute('readonly', '')
    ta.style.position = 'fixed'
    ta.style.top = '-9999px'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}

/** 复制消息原文：空内容直接返回不抛错；clipboard 抛错时走兜底 */
async function copyMessageContent(m: UiChatMessage): Promise<void> {
  const text = m.content ?? ''
  if (!text) return
  try {
    if (!navigator.clipboard?.writeText) throw new Error('clipboard unavailable')
    await navigator.clipboard.writeText(text)
    markCopied(m)
  } catch {
    const ok = await fallbackCopy(text)
    if (ok) markCopied(m)
    else naiveMessage.error('复制失败')
  }
}

/* ---------- 溯源引用：渲染与抽屉 ---------- */
const router = useRouter()
const naiveMessage = useMessage()
const drawerVisible = ref(false)
const drawerSources = ref<ChatSource[]>([])
const drawerIndex = ref(0)

function fileNameOf(s: ChatSource): string {
  return String(s.file_name ?? s.fileName ?? '')
}

function sourceTitle(s: ChatSource): string {
  const name = fileNameOf(s)
  const snippet = String(s.snippet ?? s.content ?? '')
  return name ? `${name} — ${snippet.slice(0, 80)}` : snippet.slice(0, 80)
}

function citationKey(s: ChatSource, idx: number): string {
  return `${String(s.file_id ?? '')}-${String(s.chunk_id ?? '')}-${idx}`
}

// 行内引用渲染：先用 marked 渲染，再将文本中的 [1]/[2] 转为可点击角标（保留 markdown 结构）
function renderWithCitations(m: UiChatMessage): string {
  const base = renderMarkdown(m.content ?? '')
  if (!m.sources?.length) return base
  // 若正文已包含 [n] 文本标记，将其替换为带 data-index 的可点击 span，便于事件委托打开抽屉
  // 正则匹配 [1] [2] ... 且 n 在 1..sources.length 范围内
  let html = base
  // 仅当存在 [数字] 模式时才处理，避免无意义替换
  if (/\[(\d+)\]/.test(m.content ?? '')) {
    html = html.replace(/\[(\d+)\]/g, (match, numStr) => {
      const n = Number(numStr)
      if (Number.isNaN(n) || n < 1 || n > (m.sources?.length ?? 0)) return match
      const idx = n - 1
      return `<button class="source-badge-inline" type="button" data-index="${idx}" data-msg="${String(m.id)}" title="${escapeAttr(sourceTitle(m.sources![idx]))}">[${n}]</button>`
    })
  }
  return html
}

// 属性转义（抽屉标题用）
function escapeAttr(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function openDrawer(m: UiChatMessage, idx: number) {
  drawerSources.value = m.sources ?? []
  drawerIndex.value = idx
  drawerVisible.value = true
}

// 跳转至原文：优先跳转知识库页面，携带 file_id，便于高亮定位
function onJumpToSource(source: ChatSource, _idx: number) {
  const fid = source.file_id
  // 复制片段后提示（由抽屉内按钮处理复制），此处仅处理跳转
  try {
    if (fid != null && String(fid).trim()) {
      router.push({ path: '/admin/knowledge', query: { file_id: String(fid) } }).catch(() => {
        // 路由不存在时降级提示
        naiveMessage.info(`文件 ${fileNameOf(source) || fid} 原文查看（file_id=${fid}）`)
      })
    } else {
      const name = fileNameOf(source)
      if (name) {
        router.push({ path: '/admin/knowledge', query: { file_name: name } }).catch(() => {
          naiveMessage.info(`文件 ${name} 原文查看`)
        })
      } else {
        naiveMessage.info('该来源暂无可跳转的原文标识')
      }
    }
  } catch {
    naiveMessage.info('跳转失败，请在知识库中手动检索文件')
  }
}

/* ---------- 用户气泡图片放大预览 ---------- */
const previewUrl = ref('')
function openImagePreview(url: string) {
  previewUrl.value = url
}
function closeImagePreview() {
  previewUrl.value = ''
}

/* ---------- 自动贴底 ---------- */
const listRef = ref<HTMLElement | null>(null)
const stickToBottom = ref(true)

function onScroll() {
  const el = listRef.value
  if (!el) return
  stickToBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 80
}

function scrollToBottom(force = false) {
  if (!force && !stickToBottom.value) return
  nextTick(() => {
    const el = listRef.value
    if (!el) return
    // 强制回底走瞬时滚动，避免与流式增量平滑滚动打架
    if (force) {
      const prev = el.style.scrollBehavior
      el.style.scrollBehavior = 'auto'
      el.scrollTop = el.scrollHeight
      el.style.scrollBehavior = prev
    } else {
      el.scrollTop = el.scrollHeight
    }
  })
}

watch(
  // 消息条数或任一消息内容/思考内容/溯源变化时尝试贴底（用户上滚时由 stickToBottom 暂停）
  () => [
    props.messages.length,
    props.messages.map((m) => `${m.content.length}:${(m.thinkingContent ?? '').length}:${m.thinkingExpanded ? 1 : 0}:${m.sources?.length ?? 0}`).join(','),
    props.thinking,
  ],
  () => scrollToBottom(),
  // 使用 flush:'post' 确保 DOM 更新后立即滚动，immediate 保证首片思考到达时即触发贴底
  { flush: 'post', immediate: true }
)

// 思考内容增量监听：v-if 块展开时自动滚动思考容器到底部，确保增量逐片可见
// 关键修复：设置 flush:'post' + immediate:true，避免 Vue 将多片 thinking chunk 合并为一次 nextTick 才滚动
watch(
  () => props.messages.map((m) => m.thinkingContent ?? '').join('|'),
  () => {
    // 仅当存在展开的思考块时滚动
    const hasExpandedThinking = props.messages.some((m) => !!m.thinkingContent && isExpanded(m))
    if (!hasExpandedThinking) return
    nextTick(() => {
      // 滚动思考内容容器到底部（逐片增量均触发）
      for (const el of thinkingBodyRefs.value) {
        if (el) el.scrollTop = el.scrollHeight
      }
      // 同时保持整体消息列表贴底
      scrollToBottom()
    })
  },
  { flush: 'post', immediate: true }
)

// 额外监听：thinking 长度细粒度变化（避免 join 字符串批量合并，强制每片都触发滚动）
watch(
  () => props.messages.map((m) => (m.thinkingContent ?? '').length).join(','),
  () => {
    const hasExpandedThinking = props.messages.some((m) => !!m.thinkingContent && isExpanded(m))
    if (!hasExpandedThinking) return
    nextTick(() => {
      for (const el of thinkingBodyRefs.value) {
        if (el) el.scrollTop = el.scrollHeight
      }
      scrollToBottom()
    })
  },
  { flush: 'post', immediate: true }
)

onMounted(() => scrollToBottom(true))

defineExpose({ scrollToBottom, clearHeroCompose })
</script>

<style scoped>
.msg-list {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  scroll-behavior: smooth;
  /* flex 纵向布局：欢迎屏 margin:auto 垂直居中，消息流正常排列 */
  display: flex;
  flex-direction: column;
}
.msg-list .msg-stream {
  width: 100%;
}

/* ---------- 回到底部悬浮按钮（底部居中白圆，压输入框上沿骑缝） ---------- */
.to-bottom {
  position: sticky;
  bottom: 10px;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  margin: 0 auto -46px;
  padding: 0;
  border: 1px solid #e2e8f0;
  border-radius: 50%;
  background: #fff;
  color: var(--text-1, #0f172a);
  cursor: pointer;
  box-shadow: 0 4px 16px rgba(15, 23, 42, 0.12);
  transition: transform var(--dur-fast) var(--ease), box-shadow var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.to-bottom svg {
  vertical-align: middle;
}
.to-bottom:hover {
  transform: translateY(1px);
  box-shadow: 0 8px 24px rgba(2, 6, 23, 0.22), 0 2px 6px rgba(2, 6, 23, 0.14);
}
.to-bottom:active {
  transform: translateY(1px) scale(0.96);
}
.to-bottom:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}
[data-theme='dark'] .to-bottom {
  background: var(--bg-elevated);
  border-color: var(--border-strong);
  color: #e2e8f0;
  box-shadow: 0 4px 18px rgba(0, 0, 0, 0.45), 0 1px 4px rgba(0, 0, 0, 0.4);
}
@media (max-width: 640px) {
  .to-bottom {
    bottom: 8px;
    margin-bottom: -44px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .msg-list {
    scroll-behavior: auto;
  }
  .to-bottom {
    transition: none;
  }
}

/* ---------- 欢迎屏 ---------- */
.welcome {
  max-width: 840px;
  width: 100%;
  margin: auto;
  padding: var(--space-5) var(--space-5) var(--space-6);
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
}
.welcome__badge {
  font-size: var(--text-xs);
  letter-spacing: 0.12em;
  color: var(--accent);
  background: var(--accent-soft);
  border: 1px solid var(--accent-border);
  border-radius: 999px;
  padding: 4px 14px;
}
.welcome__title {
  font-size: clamp(34px, 6vw, 52px);
  font-weight: 800;
  letter-spacing: -0.02em;
  line-height: 1.15;
  margin: var(--space-5) 0 0;
  color: var(--text-1);
}
.welcome__title em {
  font-style: normal;
  background: linear-gradient(120deg, var(--accent), var(--accent-strong, var(--accent)));
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.welcome__subtitle {
  margin-top: var(--space-3);
  color: var(--text-2);
  font-size: var(--text-base);
}
/* hero 附件容器：chips 条 + 输入框纵向排列，宽度与输入框一致 */
.welcome__compose {
  width: 100%;
  margin-top: var(--space-5);
  display: flex;
  flex-direction: column;
}
.welcome__input-wrap {
  width: 100%;
  display: flex;
  align-items: center;
  gap: var(--space-2);
  background: var(--bg-elevated);
  border: 1px solid var(--border-strong);
  border-radius: 16px;
  padding: var(--space-3);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.08);
  transition: border-color var(--dur-fast) var(--ease), box-shadow var(--dur-fast) var(--ease);
  text-align: left;
}
.welcome__input-wrap:focus-within {
  border-color: var(--accent-border);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.08), 0 0 0 3px var(--accent-soft);
}
.welcome-input {
  flex: 1;
  resize: none;
  border: none;
  outline: none;
  background: transparent;
  color: var(--text-1);
  font-size: var(--text-base);
  line-height: 1.6;
  font-family: inherit;
  max-height: 160px;
}
.welcome-send {
  flex-shrink: 0;
  height: 36px;
  padding: 0 18px;
  border-radius: 10px;
  border: none;
  background: var(--accent);
  color: #fff;
  font-weight: 600;
  cursor: pointer;
  transition: opacity var(--dur-fast) var(--ease), transform var(--dur-fast) var(--ease);
}
[data-theme='dark'] .welcome-send {
  color: #0f172a;
}
.welcome-send:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
.welcome-send:not(:disabled):hover {
  transform: translateY(-1px);
}
.welcome__hint {
  margin-top: var(--space-5);
  font-size: var(--text-xs);
  letter-spacing: 0.08em;
  color: var(--text-3);
}
.welcome__guest-tip {
  margin-top: var(--space-6);
  font-size: var(--text-xs);
  color: var(--text-3);
}
.welcome__guest-tip a {
  color: var(--accent);
}
.welcome__hint kbd {
  font-family: var(--font-mono);
  font-size: 10px;
  background: var(--bg-hover);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 1px 6px;
}
.welcome__questions {
  margin-top: var(--space-4);
}

/* ---------- 欢迎屏入场编排：badge → 标题 → 副标题 → 输入区 → 提示 → 推荐问题依次上浮 ---------- */
.welcome__badge {
  animation: fade-up 0.45s var(--ease) both;
}
.welcome__title {
  animation: fade-up 0.45s var(--ease) 0.06s both;
}
.welcome__subtitle {
  animation: fade-up 0.45s var(--ease) 0.12s both;
}
.welcome__compose {
  animation: fade-up 0.45s var(--ease) 0.18s both;
}
.welcome__hint {
  animation: fade-up 0.45s var(--ease) 0.26s both;
}
.welcome__questions {
  animation-delay: 0.32s;
}
.welcome__guest-tip {
  animation: fade-up 0.45s var(--ease) 0.38s both;
}

/* ---------- hero 附件：chip 条与回形针入口（样式与 ChatInputBar 同款，scoped 样式无法跨组件共享故按 token 复制） ---------- */
.attach-chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
}
.attach-chip {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  max-width: 220px;
  padding: var(--space-1) var(--space-2);
  background: var(--bg-hover);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: var(--text-xs);
  color: var(--text-1);
}
.attach-chip__thumb {
  width: 28px;
  height: 28px;
  object-fit: cover;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border);
  flex-shrink: 0;
}
.attach-chip__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  user-select: none;
}
.attach-chip--doc {
  color: var(--text-2);
}
.attach-chip__doc-icon {
  flex-shrink: 0;
  color: var(--text-3);
}
.attach-chip__progress {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
  flex-shrink: 0;
  user-select: none;
}
.attach-chip__remove {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  border: none;
  border-radius: var(--radius-full);
  background: transparent;
  color: var(--text-3);
  font-size: 13px;
  line-height: 1;
  cursor: pointer;
  flex-shrink: 0;
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}
.attach-chip__remove:hover {
  background: var(--bg-active);
  color: var(--danger);
}
/* 回形针入口：与 ChatInputBar 同款图标按钮，位于发送键左侧 */
.attach-anchor {
  position: relative;
  display: inline-flex;
  flex-shrink: 0;
}
.attach-btn {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border: none;
  border-radius: var(--radius-full);
  background: transparent;
  color: var(--text-2);
  cursor: pointer;
  outline: none;
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}
.attach-btn:hover {
  background: var(--bg-hover);
  color: var(--text-1);
}
.attach-btn.is-loading {
  cursor: progress;
  color: var(--accent);
}
/* 上传中 spinner：仅 transform 动画 */
.attach-btn__spinner {
  width: 14px;
  height: 14px;
  border: 2px solid var(--accent-soft);
  border-top-color: var(--accent);
  border-radius: var(--radius-full);
  animation: attach-spin 0.8s linear infinite;
}
@keyframes attach-spin {
  to {
    transform: rotate(360deg);
  }
}
/* 隐藏文件选择器（经回形针按钮触发） */
.attach-input {
  display: none;
}
@media (prefers-reduced-motion: reduce) {
  .attach-btn__spinner {
    animation: none;
  }
}

/* ---------- 消息流 ---------- */
.msg-stream {
  max-width: 840px;
  margin: 0 auto;
  padding: var(--space-6) var(--space-5) var(--space-8);
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
}
.msg-loading {
  text-align: center;
  color: var(--text-3);
  font-size: var(--text-sm);
  padding: var(--space-8) 0;
}

.msg-row--user {
  display: flex;
  justify-content: flex-end;
}
/* 用户行包裹层：相对定位承载右下角绝对定位复制按钮，不挤占气泡布局 */
.user-row {
  position: relative;
  max-width: 78%;
}
.user-bubble {
  max-width: 100%;
  padding: 10px 16px;
  background: var(--accent-soft);
  border: 1px solid var(--accent-border);
  color: var(--text-1);
  border-radius: 16px 16px 4px 16px;
  word-break: break-word;
  line-height: 1.7;
  font-size: var(--text-base);
}
/* 气泡内缩略图行：文字上方，统一最高 160px 等比，点击放大预览 */
.user-bubble__images {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}
.user-bubble__images + .user-bubble__text {
  margin-top: var(--space-2);
}
.user-bubble__img {
  display: block;
  max-height: 160px;
  max-width: 220px;
  object-fit: cover;
  border-radius: var(--radius-sm);
  border: 1px solid var(--accent-border);
  cursor: zoom-in;
  transition: transform var(--dur-fast) var(--ease);
}
.user-bubble__img:hover {
  transform: scale(1.02);
}
/* ---------- 气泡内文档卡片行：对标豆包样式（圆角浅底卡片、左彩色图标、文件名截断、类型·大小副标题） ---------- */
.user-bubble__docs {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.user-bubble__docs + .user-bubble__images,
.user-bubble__docs + .user-bubble__text {
  margin-top: var(--space-2);
}
.doc-card {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 200px;
  max-width: 320px;
  padding: var(--space-2) var(--space-3);
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  text-decoration: none;
  color: inherit;
  transition: border-color var(--dur-fast) var(--ease), transform var(--dur-fast) var(--ease);
}
/* 有 url 的卡片可跳转：hover 轻抬升提示可点击 */
a.doc-card[href]:hover {
  border-color: var(--border-strong);
  transform: translateY(-1px);
}
/* 左图标：中性文件轮廓（--text-3 单色，不按类型染色——文件类型千千万颜色映射表永远不全），下方小号扩展名标注 */
.doc-card__icon {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  width: 36px;
  height: 36px;
  border-radius: var(--radius-sm);
  background: var(--bg-hover);
  color: var(--text-3);
  flex-shrink: 0;
}
.doc-card__ext {
  max-width: 32px;
  font-size: 8px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: 0.02em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.doc-card__body {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.doc-card__name {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.doc-card__meta {
  font-size: var(--text-xs);
  color: var(--text-3);
  white-space: nowrap;
}
.user-bubble__text {
  white-space: pre-wrap;
}
/* 图片放大预览遮罩：全屏半透明黑底居中大图 */
.img-preview-mask {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(15, 23, 42, 0.72);
  padding: var(--space-6);
}
.img-preview__img {
  max-width: min(90vw, 960px);
  max-height: 86vh;
  object-fit: contain;
  border-radius: var(--radius-sm);
  box-shadow: 0 12px 48px rgba(0, 0, 0, 0.4);
}
.img-preview__close {
  position: absolute;
  top: var(--space-4);
  right: var(--space-4);
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border: 1px solid rgba(255, 255, 255, 0.3);
  border-radius: var(--radius-full);
  background: rgba(15, 23, 42, 0.5);
  color: #fff;
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease);
}
.img-preview__close:hover {
  background: rgba(15, 23, 42, 0.75);
}
/* 用户气泡操作区（编辑+复制）：右下角绝对定位，平时隐藏 hover 淡入，不占布局 */
.user-actions {
  position: absolute;
  right: 2px;
  top: calc(100% + 4px);
  display: flex;
  align-items: center;
  gap: 6px;
  opacity: 0;
  transition: opacity var(--dur-fast) var(--ease);
}
.user-row:hover .user-actions,
.user-row:focus-within .user-actions {
  opacity: 1;
}
/* 复制后保持操作区可见，让“已复制”状态可感知 */
.user-row:has(.user-copy--done) .user-actions {
  opacity: 1;
}
.user-copy {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: var(--bg-elevated);
  color: var(--text-3);
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
  transition: color var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease);
}
.user-copy:hover {
  color: var(--accent);
  border-color: var(--accent-border);
}
.user-copy:disabled {
  cursor: not-allowed;
  opacity: 0;
}
.user-copy:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}
.user-copy--done {
  color: var(--accent);
  border-color: var(--accent-border);
}
/* 触屏无 hover 时常显弱化操作区 */
@media (hover: none) {
  .user-actions {
    opacity: 0.45;
  }
  .user-copy:disabled {
    opacity: 0;
  }
}
[data-theme='dark'] .user-copy {
  background: rgba(255, 255, 255, 0.06);
}

/* AI 回复：去气泡全宽 */
.msg-row--ai {
  animation-delay: 40ms;
}
.ai-msg {
  min-width: 0;
}
.ai-content {
  color: var(--text-1);
  font-size: var(--text-base);
}

/* 回答耗时统计（底部小字，弱化展示） */
.ai-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: var(--space-3);
  padding-left: 2px;
  font-size: var(--text-xs);
  color: var(--text-3);
  opacity: 0.75;
  user-select: none;
}
.ai-meta::before {
  content: '';
  width: 1px;
  height: 10px;
  border-radius: 1px;
  background: var(--border);
}
.ai-meta span {
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.02em;
}

/* 工具徽章行 */
.tool-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}
.tool-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-xs);
  font-family: var(--font-mono);
  color: var(--text-2);
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 3px 12px;
}
.tool-chip__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
}

/* ---------- 思考过程折叠块 ---------- */
.thinking-block {
  margin-bottom: var(--space-3);
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--bg-elevated, var(--bg-soft, #f8fafc));
  overflow: hidden;
}
.thinking-block__header {
  width: 100%;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border: none;
  background: transparent;
  color: var(--text-2);
  font-size: var(--text-xs);
  font-weight: 600;
  letter-spacing: 0.04em;
  cursor: pointer;
  text-align: left;
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}
.thinking-block__header:hover {
  background: var(--accent-soft, rgba(0, 0, 0, 0.04));
  color: var(--text-1);
}
.thinking-block__icon {
  display: inline-block;
  font-size: 12px;
  line-height: 1;
  transition: transform 0.18s var(--ease);
}
.thinking-block__icon.is-expanded {
  transform: rotate(90deg);
}
/* 思考标题前的绿色脉冲点：静态为绿点，流式时带缩放+透明度脉冲动画 */
.thinking-title-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #22c55e;
  flex-shrink: 0;
}
.thinking-title-dot--pulse {
  animation: thinking-pulse 1.2s ease-in-out infinite;
}
@keyframes thinking-pulse {
  0% {
    transform: scale(0.8);
    opacity: 0.6;
  }
  50% {
    transform: scale(1.2);
    opacity: 1;
  }
  100% {
    transform: scale(0.8);
    opacity: 0.6;
  }
}
.thinking-block__title {
  flex-shrink: 0;
}
/* 主标题黑色加粗，副标题灰色细体，避免重复导致的双黑粗标题 */
.thinking-block__title--main {
  color: var(--text-1);
  font-weight: 700;
  font-size: var(--text-xs);
  letter-spacing: 0.04em;
}
/* 思考中标题呼吸渐变：仅在流式活跃时生效，静态时保持原有黑色 */
.thinking-block__title--breathing {
  background: linear-gradient(90deg, var(--text-1) 0%, var(--accent) 45%, var(--text-1) 85%);
  background-size: 200% 100%;
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  animation: thinking-shimmer 2s ease-in-out infinite;
}
/* 呼吸渐变关键帧：背景位置往返移动形成微光扫过效果 */
@keyframes thinking-shimmer {
  0% {
    background-position: 100% 0;
    opacity: 0.92;
  }
  50% {
    background-position: 0% 0;
    opacity: 1;
  }
  100% {
    background-position: 100% 0;
    opacity: 0.92;
  }
}
.thinking-block__subtitle {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  color: var(--text-2);
  font-weight: 400;
  letter-spacing: 0.02em;
  opacity: 0.9;
}
.thinking-block__subtitle::before {
  content: '—';
  margin: 0 6px;
  color: var(--text-3);
}
.thinking-block__hint {
  margin-left: auto;
  font-size: 11px;
  color: var(--text-3);
  font-weight: 400;
}
.thinking-block__placeholder {
  color: var(--text-3);
  font-size: var(--text-xs);
  font-style: italic;
}
.thinking-block__placeholder--empty {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 22px;
  padding: 6px 0;
  font-style: normal;
}
/* 空思考内容的脉冲点（立即反馈，避免空白） */
.thinking-dots {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.thinking-dots i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-3, #94a3b8);
  animation: thinking-dots-pulse 1.2s ease-in-out infinite;
}
.thinking-dots i:nth-child(2) {
  animation-delay: 0.2s;
}
.thinking-dots i:nth-child(3) {
  animation-delay: 0.4s;
}
@keyframes thinking-dots-pulse {
  0%,
  80%,
  100% {
    opacity: 0.35;
    transform: scale(0.75);
  }
  40% {
    opacity: 1;
    transform: scale(1);
  }
}
.thinking-block__body {
  padding: 10px 14px 14px;
  border-top: 1px dashed var(--border);
  color: var(--text-2);
  font-size: var(--text-sm);
  line-height: 1.7;
  background: var(--bg, #fff);
  max-height: 320px;
  overflow-y: auto;
  white-space: normal;
  word-break: break-word;
}
[data-theme='dark'] .thinking-block {
  background: rgba(255, 255, 255, 0.05);
  border-color: var(--border-strong);
  /* 左侧 accent 竖线：暗色下与主区拉开层级 */
  border-left: 2px solid var(--accent-border);
}
[data-theme='dark'] .thinking-block__body {
  background: rgba(255, 255, 255, 0.02);
}

/* 已移除独立“AI 思考中”占位样式（3 绿点），由 thinking-block 统一承载进度，保留占位避免旧快照报错 */
.thinking,
.thinking-dot,
.thinking-label {
  display: none;
}

.fade-up {
  animation: fade-up 0.35s var(--ease) both;
}

/* ---------- 溯源聚合胶囊（风格紧凑 pill · 精致化） ---------- */
.source-pill-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: var(--space-3);
}
.source-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 30px;
  padding: 0 14px 0 12px;
  border-radius: var(--radius-full);
  border: 1px solid var(--accent-border);
  /* 细腻渐变：accent-soft 向 bg-elevated 过渡，呼应 tokens --accent 体系 */
  background: linear-gradient(135deg, var(--accent-soft) 0%, var(--bg-elevated) 72%);
  color: var(--text-2);
  font-size: 12.5px;
  line-height: 1;
  font-weight: 600;
  letter-spacing: 0.03em;
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
  box-shadow: 0 1px 6px rgba(2, 6, 23, 0.06);
  transition:
    background var(--dur-fast) var(--ease),
    color var(--dur-fast) var(--ease),
    border-color var(--dur-fast) var(--ease),
    box-shadow var(--dur-fast) var(--ease),
    transform var(--dur-fast) var(--ease);
}
.source-pill:hover {
  background: linear-gradient(135deg, var(--accent-soft) 0%, var(--bg-hover) 72%);
  border-color: var(--accent-border);
  color: var(--accent);
  box-shadow: 0 4px 16px rgba(2, 6, 23, 0.1), 0 1px 4px rgba(2, 6, 23, 0.06);
  transform: translateY(-1px) scale(1.02);
}
.source-pill:active {
  transform: translateY(0) scale(0.98);
  box-shadow: 0 1px 4px rgba(2, 6, 23, 0.06);
}
.source-pill:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}
/* 图标：文档感，默认跟随文字色，悬停时随 pill 变为 accent */
.source-pill__icon {
  font-size: 14px;
  color: var(--text-3);
  transition: color var(--dur-fast) var(--ease), transform var(--dur-fast) var(--ease);
  flex-shrink: 0;
}
.source-pill:hover .source-pill__icon {
  color: var(--accent);
}
.source-pill__text {
  font-variant-numeric: tabular-nums;
}
/* 箭头：优雅 chevron，悬停时右移微动效 */
.source-pill__arrow {
  font-size: 10px;
  color: var(--text-3);
  margin-left: 1px;
  opacity: 0.9;
  transition: color var(--dur-fast) var(--ease), transform var(--dur-fast) var(--ease), opacity var(--dur-fast) var(--ease);
  flex-shrink: 0;
}
.source-pill:hover .source-pill__arrow {
  color: var(--accent);
  transform: translateX(2px);
  opacity: 1;
}
/* AI 回答复制按钮：幽灵风格 12px，与引用胶囊同行并排 */
.ai-copy {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 30px;
  padding: 0 12px;
  border-radius: var(--radius-full);
  border: 1px solid var(--border);
  background: transparent;
  color: var(--text-3);
  font-size: 12px;
  line-height: 1;
  font-weight: 500;
  letter-spacing: 0.03em;
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
  transition:
    color var(--dur-fast) var(--ease),
    border-color var(--dur-fast) var(--ease),
    background var(--dur-fast) var(--ease);
}
.ai-copy:hover:not(:disabled) {
  color: var(--accent);
  border-color: var(--accent-border);
  background: var(--accent-soft);
}
.ai-copy:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.ai-copy:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}
.ai-copy--done {
  color: var(--accent);
  border-color: var(--accent-border);
}
/* 重新生成按钮图标：与复制回答同风格 pill 内联小图标 */
.regen-btn__icon {
  font-size: 13px;
  margin-right: 4px;
}
[data-theme='dark'] .ai-copy:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.06);
}
/* 浅色主题：加深边框对比，阴影更柔和 */
[data-theme='light'] .source-pill {
  box-shadow: 0 1px 8px rgba(15, 23, 42, 0.06);
}
[data-theme='light'] .source-pill:hover {
  box-shadow: 0 6px 20px rgba(15, 23, 42, 0.08), 0 1px 4px rgba(15, 23, 42, 0.06);
}

/* 行内角标（正文中的 [1] 替换） */
:deep(.source-badge-inline) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 4px;
  margin: 0 2px;
  border-radius: 999px;
  border: 1px solid var(--accent-border);
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 11px;
  font-family: var(--font-mono);
  font-weight: 700;
  line-height: 1;
  vertical-align: super;
  cursor: pointer;
  transition: all var(--dur-fast) var(--ease);
}
:deep(.source-badge-inline:hover) {
  background: var(--accent);
  color: #0f172a;
}
[data-theme='light'] :deep(.source-badge-inline:hover) {
  color: #fff;
}
</style>
