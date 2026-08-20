<template>
  <div class="exec-page">
    <!-- ===== 页头 ===== -->
    <div class="exec-head">
      <div class="exec-head__left">
        <router-link to="/ai/tools" class="exec-head__back">← 返回工具列表</router-link>
        <h2 class="exec-head__title">{{ tool?.name || '工具执行' }}</h2>
        <span class="exec-head__key mono">{{ toolKey }}</span>
      </div>
      <div class="exec-head__status">
        <span class="exec-head__dot" :class="stateClass" aria-hidden="true"></span>
        {{ stateText }}
      </div>
    </div>

    <!-- ===== 左右分栏 ===== -->
    <div class="exec-layout">
      <!-- 左：结果展示区 -->
      <section class="exec-panel exec-result-panel">
        <div class="exec-panel__head">
          <span class="exec-panel__title">执行结果</span>
          <span v-if="execLoading" class="exec-panel__meta mono">运行中…</span>
          <span v-else-if="hasResult" class="exec-panel__meta mono exec-panel__meta--done">完成</span>
        </div>

        <!-- 未执行空态 -->
        <div v-if="!hasResult && !execLoading" class="exec-state">
          <div class="exec-state__icon" aria-hidden="true">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
              <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
            </svg>
          </div>
          <p class="exec-state__title">等待执行</p>
          <p class="exec-state__hint">在右侧填写参数，点击「开始执行」查看结果</p>
        </div>

        <!-- 执行中占位 -->
        <div v-else-if="execLoading && !hasResult" class="exec-state">
          <div class="exec-state__dots"><span></span><span></span><span></span></div>
          <p class="exec-state__hint">AI 正在处理，流式输出中…</p>
        </div>

        <!-- 结果内容 -->
        <div v-else class="exec-result">
          <!-- 结构化结果 -->
          <div v-if="execResult !== null" class="exec-result__structured">
            <div v-for="(value, key) in execResult" :key="key" class="exec-result__row">
              <span class="exec-result__key mono">{{ key }}</span>
              <span class="exec-result__value mono">{{ formatResultValue(value) }}</span>
            </div>
          </div>

          <!-- 流式文本 -->
          <div v-if="execOutput" class="exec-result__text md-body" v-html="renderMarkdown(execOutput)"></div>

          <!-- 引用出处 -->
          <div v-if="execCitations.length" class="exec-result__citations">
            <div class="exec-result__citations-head">引用出处</div>
            <div v-for="c in execCitations" :key="c.index" class="exec-result__citation">
              <span class="exec-result__citation-idx mono">[{{ c.index }}]</span>
              <span class="exec-result__citation-file">{{ c.fileName }}</span>
            </div>
          </div>

          <!-- 错误 -->
          <div v-if="execError" class="exec-result__error">{{ execError }}</div>
        </div>
      </section>

      <!-- 右：输入区 -->
      <section class="exec-panel exec-input-panel">
        <div class="exec-panel__head">
          <span class="exec-panel__title">参数配置</span>
        </div>
        <n-form v-if="tool" :model="execForm" label-placement="top" size="medium">
          <n-form-item
            v-for="p in tool.inputs ?? []"
            :key="p.name"
            :label="p.label || p.name"
            :rule="{ required: p.required, message: `请输入${p.label || p.name}` }"
          >
            <n-input
              v-model:value="execForm[p.name]"
              :type="p.type === 'textarea' ? 'textarea' : 'text'"
              :autosize="p.type === 'textarea' ? { minRows: 4, maxRows: 10 } : undefined"
              :placeholder="p.placeholder || `请输入${p.label || p.name}`"
            />
          </n-form-item>
        </n-form>
        <div v-else class="exec-input-panel__loading">工具加载中…</div>

        <n-button
          type="primary"
          block
          size="large"
          :loading="execLoading"
          :disabled="execLoading"
          class="exec-input-panel__run"
          @click="runExec"
        >
          {{ execLoading ? '执行中…' : '开始执行' }}
        </n-button>
        <p class="exec-input-panel__hint">支持流式输出 · Markdown 渲染 · 结构化结果</p>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NForm, NFormItem, NInput, NTag, useMessage } from 'naive-ui'
import { aiToolApi, type AiToolItem } from '@/api/aiTool'
import { renderMarkdown } from '@/utils/markdown'

const route = useRoute()
const router = useRouter()
const message = useMessage()

const toolKey = computed(() => String(route.params.key ?? ''))

/* ---------- 工具加载 ---------- */
const tool = ref<AiToolItem | null>(null)

onMounted(async () => {
  try {
    const list = await aiToolApi.list()
    tool.value = list.find((t) => t.key === toolKey.value) ?? null
    if (!tool.value) {
      message.warning(`未找到工具：${toolKey.value}`)
      router.replace('/ai/tools')
      return
    }
    // 初始化参数表单
    tool.value.inputs?.forEach((p) => (execForm[p.name] = ''))
  } catch {
    // 已 toast
  }
})

/* ---------- 执行状态 ---------- */
interface ExecForm {
  [key: string]: string
}

interface Citation {
  index: number
  fileName?: string
  snippet?: string
}

const execForm = reactive<ExecForm>({})
const execLoading = ref(false)
const execOutput = ref('')
const execResult = ref<Record<string, unknown> | null>(null)
const execCitations = ref<Citation[]>([])
const execError = ref('')
let execAbort: AbortController | null = null

const hasResult = computed(
  () => !!execOutput.value || execResult.value !== null || execCitations.value.length > 0 || !!execError.value
)

const stateText = computed(() => {
  if (execLoading.value) return '执行中'
  if (execError.value) return '执行失败'
  if (hasResult.value) return '执行完成'
  return '待执行'
})

const stateClass = computed(() => {
  if (execLoading.value) return 'is-running'
  if (execError.value) return 'is-error'
  if (hasResult.value) return 'is-done'
  return 'is-idle'
})

function formatResultValue(v: unknown): string {
  if (v === null || v === undefined) return '—'
  if (typeof v === 'object') return JSON.stringify(v, null, 2)
  return String(v)
}

function runExec() {
  if (!tool.value) return
  execLoading.value = true
  execOutput.value = ''
  execResult.value = null
  execCitations.value = []
  execError.value = ''
  try {
    execAbort = aiToolApi.streamExecute(toolKey.value, { ...execForm }, {
      onChunk: (chunk) => {
        if (chunk.type === 'token') {
          execOutput.value += chunk.content
        } else if (chunk.type === 'result') {
          execResult.value = (chunk.data ?? {}) as Record<string, unknown>
        } else if (chunk.type === 'citations') {
          execCitations.value = (chunk.data ?? []) as Citation[]
        } else if (chunk.type === 'error') {
          execError.value = chunk.content || '执行失败'
        }
      },
      onDone: () => {
        // 幂等保护：后端 report 等工具可能发送多个 [FINAL]，只处理一次
        if (!execLoading.value) return
        execLoading.value = false
        execAbort = null
      },
      onError: (msg) => {
        execLoading.value = false
        execAbort = null
        execError.value = msg
      },
    })
  } catch {
    execLoading.value = false
  }
}

onUnmounted(() => {
  execAbort?.abort()
  execAbort = null
})
</script>

<style scoped>
.exec-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  height: calc(100vh - 56px - 48px);
  min-height: 560px;
}

/* ===== 页头 ===== */
.exec-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  flex-wrap: wrap;
}
.exec-head__left {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}
.exec-head__back {
  color: var(--text-3);
  font-size: var(--text-sm);
}
.exec-head__back:hover {
  color: var(--accent);
}
.exec-head__title {
  font-size: var(--text-lg);
  font-weight: 700;
  letter-spacing: -0.02em;
}
.exec-head__key {
  font-size: var(--text-xs);
  color: var(--text-3);
  background: var(--bg-hover);
  padding: 3px 10px;
  border-radius: var(--radius-full);
  border: 1px solid var(--border);
}
.exec-head__status {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-sm);
  color: var(--text-2);
}
.exec-head__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.exec-head__dot.is-idle {
  background: var(--text-3);
}
.exec-head__dot.is-running {
  background: var(--accent);
  animation: pulse-dot 1.2s ease-in-out infinite;
}
.exec-head__dot.is-done {
  background: var(--success);
}
.exec-head__dot.is-error {
  background: var(--danger);
}

/* ===== 左右分栏 ===== */
.exec-layout {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 1fr 360px;
  gap: var(--space-5);
  /* 两栏拉伸至整行高度，底部对齐 */
  align-items: stretch;
}

.exec-panel {
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  overflow: hidden;
}
.exec-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-3) var(--space-4);
  border-bottom: 1px solid var(--border);
  background: var(--bg-hover);
}
.exec-panel__title {
  font-weight: 600;
  font-size: var(--text-sm);
}
.exec-panel__meta {
  font-size: var(--text-xs);
  color: var(--text-3);
}
.exec-panel__meta--done {
  color: var(--success);
}

/* ---------- 左：结果区 ---------- */
.exec-result-panel {
  /* 铺满网格行高，与右栏底部对齐 */
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.exec-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
  padding: var(--space-8) var(--space-5);
  color: var(--text-3);
}
.exec-state__icon {
  width: 64px;
  height: 64px;
  display: grid;
  place-items: center;
  border-radius: var(--radius-lg);
  background: var(--bg-hover);
  border: 1px solid var(--border);
  color: var(--text-3);
}
.exec-state__title {
  font-weight: 600;
  font-size: var(--text-md);
  color: var(--text-2);
}
.exec-state__hint {
  font-size: var(--text-sm);
  text-align: center;
}
.exec-state__dots {
  display: flex;
  gap: 6px;
}
.exec-state__dots span {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--accent);
  animation: pulse-dot 1.2s ease-in-out infinite;
}
.exec-state__dots span:nth-child(2) { animation-delay: 0.15s; }
.exec-state__dots span:nth-child(3) { animation-delay: 0.3s; }

.exec-result {
  padding: var(--space-4);
  flex: 1;
  min-height: 0;
  overflow: auto;
}
.exec-result__structured {
  margin-bottom: var(--space-4);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  overflow: hidden;
}
.exec-result__row {
  display: flex;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px dashed var(--border);
  background: var(--bg);
}
.exec-result__row:last-child {
  border-bottom: none;
}
.exec-result__key {
  flex-shrink: 0;
  min-width: 140px;
  color: var(--accent);
  font-size: var(--text-xs);
}
.exec-result__value {
  color: var(--text-1);
  font-size: var(--text-xs);
  word-break: break-all;
  white-space: pre-wrap;
}
.exec-result__text {
  font-size: var(--text-sm);
  line-height: 1.8;
  word-break: break-word;
}
.exec-result__citations {
  margin-top: var(--space-4);
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg);
}
.exec-result__citations-head {
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--text-2);
  margin-bottom: var(--space-2);
}
.exec-result__citation {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 3px 0;
  font-size: var(--text-xs);
}
.exec-result__citation-idx {
  color: var(--accent);
}
.exec-result__citation-file {
  color: var(--text-2);
}
.exec-result__error {
  margin-top: var(--space-3);
  padding: var(--space-3);
  border-radius: var(--radius-md);
  color: var(--danger);
  font-size: var(--text-sm);
  background: rgba(239, 68, 68, 0.08);
  border: 1px solid rgba(239, 68, 68, 0.3);
}

/* ---------- 右：输入区 ---------- */
.exec-input-panel {
  position: sticky;
  top: 80px;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
/* 输入表单内容区：与面板边框保持间距，输入框不再贴边 */
.exec-input-panel :deep(.n-form) {
  padding: var(--space-4);
}
.exec-input-panel__run {
  margin: 0 var(--space-4);
  width: calc(100% - var(--space-6));
}
/* hint 贴住面板底部 */
.exec-input-panel__hint {
  margin: auto var(--space-4) var(--space-4);
  font-size: var(--text-xs);
  color: var(--text-3);
  text-align: center;
}
.exec-input-panel__loading {
  padding: var(--space-5);
  color: var(--text-3);
  font-size: var(--text-sm);
  text-align: center;
}

@media (max-width: 960px) {
  .exec-page {
    height: auto;
  }
  .exec-layout {
    grid-template-columns: 1fr;
    align-items: stretch;
  }
  .exec-input-panel {
    position: static;
    order: -1;
    height: auto;
  }
  .exec-result-panel {
    height: auto;
    min-height: 420px;
  }
}
</style>
