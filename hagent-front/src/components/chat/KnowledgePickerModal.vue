<template>
  <!-- 知识库选择器：遮罩层级对齐图片预览（z-index 1000），点遮罩/Esc 关闭 -->
  <div v-if="visible" class="kb-picker-mask" role="dialog" aria-label="从知识库选择文件" @click="emit('close')">
    <div class="kb-picker" @click.stop>
      <div class="kb-picker__head">
        <span class="kb-picker__title">从知识库选择</span>
        <button type="button" class="kb-picker__close" aria-label="关闭" @click="emit('close')">×</button>
      </div>
      <p class="kb-picker__tip">选中的文件将作为文档卡片随消息发送，模型会按文件名检索知识库内容</p>
      <input
        v-model="keyword"
        class="kb-picker__search"
        type="text"
        placeholder="按文件名过滤"
        aria-label="按文件名过滤"
      />
      <div class="kb-picker__list">
        <div v-if="loading" class="kb-picker__empty">知识库加载中…</div>
        <div v-else-if="filteredFiles.length === 0" class="kb-picker__empty">
          {{ keyword ? '没有匹配的文件' : '知识库暂无文件，可先上传' }}
        </div>
        <label
          v-for="f in filteredFiles"
          :key="f.id"
          class="kb-row"
          :class="{ 'kb-row--disabled': !isVectorized(f), 'kb-row--checked': checkedIds.has(f.id ?? -1) }"
        >
          <input
            type="checkbox"
            class="kb-row__check"
            :disabled="!isVectorized(f)"
            :checked="checkedIds.has(f.id ?? -1)"
            @change="toggle(f)"
          />
          <span class="kb-row__name" :title="f.fileName">{{ f.fileName || '未命名文件' }}</span>
          <span class="kb-row__meta">{{ (f.fileExt || '').replace('.', '').toUpperCase() || '文件' }} · {{ formatDocSize(f.fileSize ?? 0) }}</span>
          <span class="kb-row__status" :class="isVectorized(f) ? 'is-ok' : 'is-warn'">
            {{ isVectorized(f) ? '已向量化' : `${statusText(f)}，暂不可选` }}
          </span>
        </label>
      </div>
      <div class="kb-picker__foot">
        <span class="kb-picker__count">已选 {{ checkedIds.size }} 个文件</span>
        <button type="button" class="kb-picker__confirm" :disabled="checkedIds.size === 0" @click="onConfirm">确定</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import type { KnowledgeFile } from '@/api/knowledge'
import { knowledgeApi } from '@/api/knowledge'
import { formatDocSize } from '@/utils/chatAttachments'

const props = defineProps<{
  /** 显隐开关（v-if 渲染遮罩） */
  visible: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'confirm', files: KnowledgeFile[]): void
}>()

const files = ref<KnowledgeFile[]>([])
const loading = ref(false)
const keyword = ref('')
/** 已勾选的文件 id 集合（多选） */
const checkedIds = ref<Set<number>>(new Set())

/* 向量化状态容错（与知识库管理页同口径）：后端枚举 1=待处理 2=处理中 3=已完成 4=失败，desc 恒有值 */
const VECTORIZED_STATUSES = ['vectorized', 'available', 'done', '已向量化', '已就绪', '已完成']

function statusText(f: KnowledgeFile): string {
  return f.vectorStatusDesc || (f.vectorStatus != null ? String(f.vectorStatus) : '待处理')
}

function isVectorized(f: KnowledgeFile): boolean {
  if (f.vectorStatus === 3) return true
  return VECTORIZED_STATUSES.includes(statusText(f))
}

/** 按文件名过滤（大小写不敏感） */
const filteredFiles = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return files.value
  return files.value.filter((f) => (f.fileName || '').toLowerCase().includes(kw))
})

/** 打开时拉取全量列表（文件数少，一次 100 够用；失败 toast 由拦截器统一提示） */
async function load() {
  loading.value = true
  checkedIds.value = new Set()
  keyword.value = ''
  try {
    files.value = await knowledgeApi.list({ pageSize: 100 })
  } catch {
    files.value = []
  } finally {
    loading.value = false
  }
}

watch(
  () => props.visible,
  (v) => {
    if (v) void load()
  }
)

function toggle(f: KnowledgeFile) {
  const id = f.id ?? -1
  const next = new Set(checkedIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  checkedIds.value = next
}

/** 确定：把勾选的文件对象回传父组件（映射为 doc chips 由 composable 完成） */
function onConfirm() {
  const selected = files.value.filter((f) => checkedIds.value.has(f.id ?? -1))
  if (selected.length === 0) return
  emit('confirm', selected)
}

/** Esc 关闭 */
function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && props.visible) emit('close')
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onUnmounted(() => document.removeEventListener('keydown', onKeydown))
</script>

<style scoped>
.kb-picker-mask {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--overlay);
  padding: var(--space-6);
}
.kb-picker {
  display: flex;
  flex-direction: column;
  width: min(520px, 92vw);
  max-height: 72vh;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-lg);
  padding: var(--space-4);
}
.kb-picker__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.kb-picker__title {
  font-size: var(--text-md);
  font-weight: 600;
  color: var(--text-1);
}
.kb-picker__close {
  border: none;
  background: transparent;
  color: var(--text-3);
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
  padding: 2px 6px;
  border-radius: 6px;
}
.kb-picker__close:hover {
  background: var(--bg-hover);
  color: var(--text-1);
}
.kb-picker__tip {
  margin-top: var(--space-1);
  font-size: var(--text-xs);
  color: var(--text-3);
}
.kb-picker__search {
  margin-top: var(--space-3);
  width: 100%;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg);
  color: var(--text-1);
  font-size: var(--text-sm);
  font-family: inherit;
  padding: var(--space-2) var(--space-3);
  outline: none;
  transition: border-color var(--dur-fast) var(--ease);
}
.kb-picker__search:focus {
  border-color: var(--accent-border);
}
.kb-picker__search::placeholder {
  color: var(--text-3);
}
.kb-picker__list {
  flex: 1;
  min-height: 120px;
  overflow-y: auto;
  margin-top: var(--space-3);
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
.kb-picker__empty {
  text-align: center;
  color: var(--text-3);
  font-size: var(--text-sm);
  padding: var(--space-6) 0;
}
.kb-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-2);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease);
}
.kb-row:hover {
  background: var(--bg-hover);
}
.kb-row--disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.kb-row--disabled:hover {
  background: transparent;
}
.kb-row__check {
  flex-shrink: 0;
  accent-color: var(--accent);
  cursor: pointer;
}
.kb-row--disabled .kb-row__check {
  cursor: not-allowed;
}
.kb-row__name {
  flex: 1;
  min-width: 0;
  font-size: var(--text-sm);
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kb-row__meta {
  flex-shrink: 0;
  font-size: var(--text-xs);
  color: var(--text-3);
  white-space: nowrap;
}
.kb-row__status {
  flex-shrink: 0;
  font-size: var(--text-xs);
  white-space: nowrap;
}
.kb-row__status.is-ok {
  color: var(--success);
}
.kb-row__status.is-warn {
  color: var(--warning);
}
.kb-picker__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: var(--space-3);
  padding-top: var(--space-3);
  border-top: 1px solid var(--border);
}
.kb-picker__count {
  font-size: var(--text-xs);
  color: var(--text-3);
  user-select: none;
}
.kb-picker__confirm {
  border: none;
  border-radius: var(--radius-sm);
  background: var(--accent);
  color: #fff;
  font-size: var(--text-sm);
  font-weight: 600;
  padding: var(--space-2) var(--space-4);
  cursor: pointer;
  transition: opacity var(--dur-fast) var(--ease);
}
[data-theme='dark'] .kb-picker__confirm {
  color: #0f172a;
}
.kb-picker__confirm:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
</style>
