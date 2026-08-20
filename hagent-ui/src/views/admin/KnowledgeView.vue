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
      <div class="kb-upload__title">上传文档</div>
      <n-upload
        :custom-request="realUpload"
        accept=".md,.txt,.pdf,.doc,.docx"
        :show-file-list="false"
        :multiple="true"
      >
        <n-upload-dragger class="kb-dragger">
          <div class="kb-dragger__icon">
            <n-icon :component="CloudUploadOutlined" />
          </div>
          <div class="kb-dragger__text">点击或拖拽文件到此处上传</div>
          <div class="kb-dragger__hint">支持 Markdown / TXT / PDF / Word，单文件不超过 10MB</div>
        </n-upload-dragger>
      </n-upload>
    </n-card>

    <!-- 文档列表 -->
    <n-card class="kb-table-card" :bordered="true" title="文档列表">
      <n-data-table
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
import { h, onMounted, ref, type Component } from 'vue'
import {
  NButton,
  NCard,
  NDataTable,
  NIcon,
  NStatistic,
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
      { label: '已向量化', value: num(s.vectorized), icon: ThunderboltOutlined, tone: 'blue' },
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
  uploadedAt: string
}

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
})

const pagination = { pageSize: 10 }

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

/* ---------- 真实上传 ---------- */
async function realUpload({ file, onFinish, onError }: UploadCustomRequestOptions) {
  const raw = file.file
  if (!raw) {
    onError()
    return
  }
  try {
    await knowledgeApi.upload(raw)
    onFinish()
    message.success(`「${file.name}」上传成功`)
    await Promise.all([loadDocs(), loadStats()])
  } catch {
    onError()
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
  border-radius: var(--radius-lg);
  background: var(--bg-elevated);
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

/* ---------- 表格 ---------- */
.kb-table-card {
  border-radius: var(--radius-lg);
  background: var(--bg-elevated);
}

@media (max-width: 900px) {
  .kb-stats {
    grid-template-columns: 1fr;
  }
}
</style>
