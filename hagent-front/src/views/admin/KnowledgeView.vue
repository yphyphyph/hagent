<template>
  <div class="knowledge-page">
    <!-- 顶部统计 -->
    <div class="kb-stats">
      <n-card v-for="s in kbStats" :key="s.label" class="kb-stat" :bordered="true">
        <div class="kb-stat__icon" :class="`kb-stat__icon--${s.tone}`">
          <n-icon :component="s.icon" />
        </div>
        <n-statistic :value="s.value" :label="s.label" />
      </n-card>
    </div>

    <!-- 上传区 -->
    <n-card class="kb-upload-card" :bordered="true">
      <div class="kb-upload__row">
        <div class="kb-upload__title">上传文档</div>
        <n-radio-group v-model:value="uploadVisibility" size="small">
          <n-radio :value="1">公有（所有用户可检索）</n-radio>
          <n-radio :value="0">私有（仅自己可检索）</n-radio>
        </n-radio-group>
      </div>
      <n-upload
        :custom-request="realUpload"
        accept=".md,.txt,.pdf,.doc,.docx"
        :show-file-list="false"
        :multiple="true"
        :disabled="isUploading"
      >
        <n-upload-dragger class="kb-dragger">
          <div class="kb-dragger__icon">
            <n-icon :component="CloudUploadOutlined" />
          </div>
          <div class="kb-dragger__text">点击或拖拽文件到此处上传</div>
          <div class="kb-dragger__hint">支持 Markdown / TXT / PDF / Word，单文件不超过 10MB</div>
        </n-upload-dragger>
      </n-upload>

      <!-- 上传进度 -->
      <div v-if="isUploading" class="kb-upload-progress">
        <div class="kb-upload-progress__label">
          <span>正在上传 {{ uploadingFileName }}</span>
          <span class="mono">{{ uploadPercent }}%</span>
        </div>
        <n-progress type="line" :percentage="uploadPercent" :height="8" :show-indicator="false" />
      </div>
    </n-card>


    <!-- 文档列表 -->
    <n-card class="kb-table-card" :bordered="true">
      <template #header>
        <div class="kb-table-head">
          <span class="kb-table-title">文档列表</span>
          <n-button
            size="small"
            :type="checkedRowKeys.length > 0 ? 'primary' : 'warning'"
            :loading="bulkRevectorizing"
            :disabled="bulkRevectorizing"
            @click="onBulkRevectorize"
          >
            {{ checkedRowKeys.length > 0 ? `重新向量化已选 (${checkedRowKeys.length})` : `一键重新向量化全部${outdatedCount > 0 ? ` (${outdatedCount} 过期)` : ""}` }}
          </n-button>
        </div>
      </template>

      <n-data-table
        v-model:checked-row-keys="checkedRowKeys"
        :columns="columns"
        :data="documents"
        :loading="docsLoading"
        :row-key="(row: KnowledgeDoc) => row.id"
        :pagination="pagination"
        :bordered="false"
      />
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref, type Component } from 'vue'
import {
  NButton,
  NCard,
  NDataTable,
  NIcon,
  NProgress,
  NRadio,
  NRadioGroup,
  NStatistic,
  NSwitch,
  NTag,
  NUpload,
  NUploadDragger,
  useMessage,
  type DataTableColumns,
  type UploadCustomRequestOptions,
} from 'naive-ui'
import {
  FileTextOutlined,
  ThunderboltOutlined,
  SearchOutlined,
  CloudUploadOutlined,
  DeleteOutlined,
} from '@vicons/antd'
import { knowledgeApi, type KnowledgeFile } from '@/api/knowledge'

const message = useMessage()

/* ---------- 统计 ---------- */
interface KbStat {
  label: string
  value: number
  icon: Component
  tone: 'green' | 'blue' | 'purple'
}

const kbStats = ref<KbStat[]>([])

async function loadStats() {
  try {
    const s = await knowledgeApi.statistics()
    const num = (v: unknown): number => (typeof v === 'number' ? v : Number(v) || 0)
    kbStats.value = [
      { label: '文档数', value: num(s.totalFiles), icon: FileTextOutlined, tone: 'green' },
      { label: '已向量化', value: num(s.completed), icon: ThunderboltOutlined, tone: 'blue' },
      { label: '检索次数', value: num(s.searchCount), icon: SearchOutlined, tone: 'purple' },
    ]
  } catch {
    // 错误已由 request 拦截器统一 toast
    kbStats.value = []
  }
}

/* ---------- 文档数据 ---------- */
interface KnowledgeDoc {
  id: number
  fileName: string
  type: string
  size: string
  status: string
  /** 可见性：0=私有 1=公有 */
  visibility: number
  uploadedAt: string
}

const checkedRowKeys = ref<Array<string | number>>([])
const documents = ref<KnowledgeDoc[]>([])
const docsLoading = ref(false)
const vectorizingId = ref<number | null>(null)
const deletingId = ref<number | null>(null)

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

function mapDoc(f: KnowledgeFile): KnowledgeDoc {
  const name = f.fileName || ''
  const ext = name.includes('.') ? name.slice(name.lastIndexOf('.') + 1).toUpperCase() : (f.fileExt || '')
  return {
    id: f.id,
    fileName: name,
    type: ext || '—',
    size: f.fileSize != null ? formatSize(f.fileSize) : '—',
    status: f.vectorStatusDesc || (f.vectorStatus != null ? String(f.vectorStatus) : 'available'),
    visibility: f.visibility ?? 1,
    uploadedAt: f.createdAt || '—',
  }
}

async function loadDocs() {
  docsLoading.value = true
  try {
    const list = await knowledgeApi.list()
    documents.value = list.map(mapDoc)
  } catch {
    // 已 toast
    documents.value = []
  } finally {
    docsLoading.value = false
  }
}

onMounted(() => {
  loadDocs()
  loadStats()
  loadOutdated()
})

const pagination = { pageSize: 10 }

/* 过期向量（模型/维度变更）一键重算 */
const outdatedCount = ref(0)
const bulkRevectorizing = ref(false)

async function loadOutdated() {
  try {
    const r = await knowledgeApi.outdatedStats()
    outdatedCount.value = Number(r.outdatedCount ?? 0)
  } catch {
    outdatedCount.value = 0
  }
}

async function onBulkRevectorize() {
  bulkRevectorizing.value = true
  try {
    const hasChecked = checkedRowKeys.value.length > 0
    const ids = hasChecked ? checkedRowKeys.value.map((v) => Number(v)) : undefined
    const r = await knowledgeApi.revectorizeOutdated(ids)
    const queued = r.queued ?? 0
    const total = r.total ?? 0
    if (hasChecked) {
      message.success(`已选 ${ids!.length} 个，已加入队列：${queued} / ${total} 个`)
    } else {
      message.success(`已加入队列：${queued} / ${total} 个文件（全部过期）`)
    }
    await Promise.all([loadOutdated(), loadStats(), loadDocs()])
    if (queued > 0) checkedRowKeys.value = []
  } catch {
    // 已 toast
  } finally {
    bulkRevectorizing.value = false
  }
}

/* 状态字段容错映射：向量化成功 / 处理中 / 失败等 */
const VECTORIZED_STATUSES = ['vectorized', 'available', 'done', '已向量化', '已就绪', '已完成']
const FAILED_STATUSES = ['failed', 'error', '失败', '异常']

const STATUS_TEXT: Record<string, string> = {
  vectorized: '已向量化',
  available: '已就绪',
  processing: '向量化中',
  pending: '待向量化',
  uploaded: '已上传',
  failed: '失败',
  '已向量化': '已向量化',
  '待向量化': '待向量化',
  '向量化中': '向量化中',
  '已完成': '已向量化',
  '失败': '失败',
}

function isVectorized(status: string): boolean {
  return VECTORIZED_STATUSES.includes(status)
}

function statusText(status: string): string {
  return STATUS_TEXT[status] ?? status
}

function statusTagType(status: string): 'success' | 'warning' | 'error' {
  if (isVectorized(status)) return 'success'
  if (FAILED_STATUSES.includes(status)) return 'error'
  return 'warning'
}

const columns: DataTableColumns<KnowledgeDoc> = [
  { type: "selection" },
  {
    title: '文件名',
    key: 'fileName',
    render: (row) =>
      h('span', { class: 'doc-name' }, [
        h(
          NIcon,
          { style: { color: 'var(--accent)', marginRight: '6px', verticalAlign: '-2px' } },
          { default: () => h(FileTextOutlined) },
        ),
        row.fileName,
      ]),
  },
  { title: '类型', key: 'type', width: 110 },
  { title: '大小', key: 'size', width: 110, className: 'mono' },
  {
    title: '状态',
    key: 'status',
    width: 110,
    render: (row) =>
      h(
        NTag,
        { size: 'small', type: statusTagType(row.status), bordered: false },
        { default: () => statusText(row.status) },
      ),
  },
  {
    title: '可见性',
    key: 'visibility',
    width: 100,
    render: (row) =>
      h(
        NSwitch,
        {
          size: 'small',
          value: row.visibility === 1,
          loading: visibilityUpdatingId.value === row.id,
          onUpdateValue: (v: boolean) => onToggleVisibility(row, v),
        },
        { checked: () => '公有', unchecked: () => '私有' },
      ),
  },
  { title: '上传时间', key: 'uploadedAt', width: 180, className: 'mono' },
  {
    title: '操作',
    key: 'actions',
    width: 170,
    render: (row) => {
      const vectorizeBtn = isVectorized(row.status)
        ? null
        : h(
            NButton,
            {
              size: 'small',
              type: 'primary',
              secondary: true,
              loading: vectorizingId.value === row.id,
              onClick: () => onVectorize(row),
            },
            { icon: () => h(NIcon, null, { default: () => h(ThunderboltOutlined) }), default: () => '向量化' },
          )
      const deleteBtn = h(
        NButton,
        {
          size: 'small',
          quaternary: true,
          type: 'error',
          loading: deletingId.value === row.id,
          onClick: () => onDelete(row),
        },
        { icon: () => h(NIcon, null, { default: () => h(DeleteOutlined) }), default: () => '删除' },
      )
      return h('span', { style: 'display: flex; gap: 4px;' }, [vectorizeBtn, deleteBtn])
    },
  },
]

async function onVectorize(row: KnowledgeDoc) {
  vectorizingId.value = row.id
  try {
    await knowledgeApi.vectorize(row.id)
    message.success(`「${row.fileName}」向量化完成`)
    await Promise.all([loadDocs(), loadStats()])
  } catch {
    // 已 toast
  } finally {
    vectorizingId.value = null
  }
}

async function onDelete(row: KnowledgeDoc) {
  deletingId.value = row.id
  try {
    await knowledgeApi.remove(row.id)
    message.success(`已删除「${row.fileName}」`)
    await Promise.all([loadDocs(), loadStats()])
  } catch {
    // 已 toast
  } finally {
    deletingId.value = null
  }
}

/* ---------- 可见性切换 ---------- */
const visibilityUpdatingId = ref<number | null>(null)

async function onToggleVisibility(row: KnowledgeDoc, visible: boolean) {
  const target = visible ? 1 : 0
  if (row.visibility === target) return
  visibilityUpdatingId.value = row.id
  try {
    await knowledgeApi.updateVisibility(row.id, target)
    row.visibility = target
    message.success(
      target === 1
        ? `「${row.fileName}」已设为公有，即时生效`
        : `「${row.fileName}」已设为私有，即时生效`,
    )
  } catch {
    // 已 toast
  } finally {
    visibilityUpdatingId.value = null
  }
}

/* ---------- 上传进度 ---------- */
const activeUploads = ref(0)
const uploadPercent = ref(0)
const uploadingFileName = ref('')
const isUploading = computed(() => activeUploads.value > 0)

/* ---------- 上传可见性选择 ---------- */
const uploadVisibility = ref<number>(1)

/* ---------- 真实上传 ---------- */
async function realUpload({ file, onFinish, onError }: UploadCustomRequestOptions) {
  const raw = file.file
  if (!raw) {
    onError()
    return
  }
  activeUploads.value++
  uploadPercent.value = 0
  uploadingFileName.value = file.name
  try {
    await knowledgeApi.upload(
      raw,
      (percent) => {
        uploadingFileName.value = file.name
        uploadPercent.value = percent
      },
      uploadVisibility.value,
    )
    onFinish()
    message.success(`「${file.name}」上传成功`)
    await Promise.all([loadDocs(), loadStats()])
  } catch {
    onError()
  } finally {
    activeUploads.value--
    if (activeUploads.value === 0) {
      // 全部上传结束后延迟重置，避免进度条瞬间消失
      setTimeout(() => {
        if (activeUploads.value === 0) {
          uploadPercent.value = 0
          uploadingFileName.value = ''
        }
      }, 600)
    }
  }
}
</script>

<style scoped>
.knowledge-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

/* ---------- 统计 ---------- */
.kb-stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-4);
}
.kb-stat {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  border-radius: var(--radius-lg);
  background: var(--bg-elevated);
}
.kb-stat__icon {
  flex-shrink: 0;
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  font-size: var(--text-lg);
  border-radius: var(--radius-md);
  color: var(--accent);
  background: var(--accent-soft);
}
.kb-stat__icon--blue {
  color: var(--info);
  background: rgba(56, 189, 248, 0.12);
}
.kb-stat__icon--purple {
  color: #a78bfa;
  background: rgba(139, 92, 246, 0.12);
}

/* ---------- 上传 ---------- */
.kb-upload-card {
  width: 100%;
  border-radius: var(--radius-lg);
  background: var(--bg-elevated);
}
.kb-upload__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.kb-upload__title {
  font-weight: 600;
  margin-bottom: var(--space-3);
}
.kb-dragger {
  padding: var(--space-6) 0;
}
.kb-dragger__icon {
  font-size: 34px;
  color: var(--accent);
  margin-bottom: var(--space-2);
}
.kb-dragger__text {
  font-weight: 600;
  margin-bottom: var(--space-1);
}
.kb-dragger__hint {
  font-size: var(--text-xs);
  color: var(--text-3);
}
.kb-upload-progress {
  margin-top: var(--space-4);
}
.kb-upload-progress__label {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: var(--text-sm);
  color: var(--text-2);
  margin-bottom: var(--space-2);
}

/* ---------- 表格 ---------- */
.kb-table-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}
.kb-table-title {
  font-weight: 600;
}
.kb-outdated-alert__body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  flex-wrap: wrap;
}
.kb-table-card {
  width: 100%;
  border-radius: var(--radius-lg);
  background: var(--bg-elevated);
}

@media (max-width: 900px) {
  .kb-stats {
    grid-template-columns: 1fr;
  }
}
</style>
