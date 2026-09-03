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

    <!-- 分块预览抽屉 -->
    <n-drawer v-model:show="chunkDrawerVisible" :width="720" placement="right" :trap-focus="false">
      <n-drawer-content :title="chunkDrawerTitle" closable :native-scrollbar="false">
        <div class="chunk-drawer">
          <!-- 检索测试区：验证切分质量与双路贡献 -->
          <div class="chunk-search">
            <div class="chunk-search__label">检索测试</div>
            <div class="chunk-search__row">
              <n-input
                v-model:value="chunkQuery"
                placeholder="输入查询词，测试该文件在双路召回中的表现"
                clearable
                size="small"
                @keyup.enter="onChunkSearch"
              />
              <n-button size="small" type="primary" :loading="chunkSearchLoading" @click="onChunkSearch">
                检索测试
              </n-button>
              <n-button size="small" quaternary @click="resetChunkSearch">重置</n-button>
            </div>
            <div v-if="chunkSearchResult.length > 0" class="chunk-search__result">
              <div class="chunk-search__meta">
                <n-tag size="small" :bordered="false" type="info">命中 {{ chunkSearchResult.length }} 条</n-tag>
                <span class="chunk-search__hint">绿色为向量召回，蓝色为关键词召回，紫色为融合后</span>
              </div>
              <div class="chunk-search-list">
                <div v-for="(r, idx) in chunkSearchResult" :key="idx" class="chunk-card chunk-card--search">
                  <div class="chunk-card__head">
                    <span class="chunk-card__idx">#{{ (r as any).chunk_index ?? (r as any).chunkIndex ?? idx }}</span>
                    <n-tag v-if="(r as any).score != null" size="tiny" type="success" :bordered="false">{{ Number((r as any).score).toFixed(4) }}</n-tag>
                  </div>
                  <div class="chunk-card__content">{{ (r as any).content ?? (r as any).text ?? '' }}</div>
                </div>
              </div>
            </div>
          </div>

          <n-divider style="margin: var(--space-4) 0" />

          <!-- 分块列表 -->
          <div class="chunk-list-head">
            <span class="chunk-list-title">分块列表</span>
            <n-tag size="small" :bordered="false" type="info">{{ chunks.length }} 块</n-tag>
            <n-button size="tiny" quaternary :loading="chunksLoading" @click="loadChunks">刷新</n-button>
          </div>

          <n-spin :show="chunksLoading">
            <div v-if="!chunksLoading && chunks.length === 0" class="chunk-empty">
              <n-empty description="暂无分块数据（未向量化或后端未返回）" />
              <n-alert type="info" :show-icon="false" class="chunk-empty__tip">
                已尝试 GET /admin/knowledge/{{ chunkDrawerFile?.id }}/chunks，无则兜底 GET /admin/ai/vector/search?fileId=...
              </n-alert>
            </div>
            <div v-else class="chunk-list">
              <div v-for="(c, idx) in chunks" :key="idx" class="chunk-card">
                <div class="chunk-card__head">
                  <span class="chunk-card__idx">#{{ displayChunkIndex(c, idx) }}</span>
                  <span v-if="displayChunkMeta(c)" class="chunk-card__meta">{{ displayChunkMeta(c) }}</span>
                  <n-tag v-if="(c as any).score != null" size="tiny" :bordered="false" type="success">{{ Number((c as any).score).toFixed(3) }}</n-tag>
                </div>
                <div class="chunk-card__content">{{ displayChunkContent(c) }}</div>
                <div v-if="displayMetadata(c)" class="chunk-card__metadata">
                  <span class="chunk-card__metadata-label">metadata</span>
                  <pre class="chunk-card__metadata-pre">{{ displayMetadata(c) }}</pre>
                </div>
              </div>
            </div>
          </n-spin>

          <n-divider style="margin: var(--space-5) 0" />

          <!-- 检索配置：HybridSearchProperties topK 与权重 -->
          <div class="chunk-config">
            <div class="chunk-config__title">检索参数</div>
            <div class="chunk-config__desc">对应后端 HybridSearchProperties，可即时保存至 system_config（hagent.hybrid.*）</div>
            <div class="chunk-config__grid">
              <div class="chunk-config__item">
                <span class="chunk-config__label">关键词 topK</span>
                <n-input-number v-model:value="hybridForm.topK" :min="1" :max="100" :step="1" size="small" class="chunk-config__input" />
              </div>
              <div class="chunk-config__item">
                <span class="chunk-config__label">向量权重</span>
                <n-input-number v-model:value="hybridForm.weightVector" :min="0" :max="5" :step="0.1" size="small" class="chunk-config__input" />
              </div>
              <div class="chunk-config__item">
                <span class="chunk-config__label">关键词权重</span>
                <n-input-number v-model:value="hybridForm.weightKeyword" :min="0" :max="5" :step="0.1" size="small" class="chunk-config__input" />
              </div>
              <div class="chunk-config__item">
                <span class="chunk-config__label">RRF K</span>
                <n-input-number v-model:value="hybridForm.rrfK" :min="1" :max="100" :step="1" size="small" class="chunk-config__input" />
              </div>
            </div>
            <div class="chunk-config__actions">
              <n-button size="small" :loading="hybridLoading" quaternary @click="loadHybridSettings">刷新配置</n-button>
              <n-button size="small" type="primary" :loading="hybridSaving" @click="saveHybridSettings">保存配置</n-button>
            </div>
            <n-alert v-if="hybridHint" type="info" :show-icon="false" class="chunk-config__hint">{{ hybridHint }}</n-alert>
          </div>
        </div>
      </n-drawer-content>
    </n-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref, type Component } from 'vue'
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NDivider,
  NDrawer,
  NDrawerContent,
  NEmpty,
  NIcon,
  NInput,
  NInputNumber,
  NProgress,
  NRadio,
  NRadioGroup,
  NSpace,
  NSpin,
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
  EyeOutlined,
} from '@vicons/antd'
import { knowledgeApi, type KnowledgeChunk, type KnowledgeFile } from '@/api/knowledge'
import { systemApi } from '@/api/system'

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

/* 过期向量一键重算 */
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

/* 状态字段容错映射 */
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
  { type: 'selection' },
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
  { title: '类型', key: 'type', width: 90 },
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
  { title: '上传时间', key: 'uploadedAt', width: 170, className: 'mono' },
  {
    title: '操作',
    key: 'actions',
    width: 260,
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
      const chunkBtn = h(
        NButton,
        {
          size: 'small',
          secondary: true,
          onClick: () => openChunkDrawer(row),
        },
        { icon: () => h(NIcon, null, { default: () => h(EyeOutlined) }), default: () => '查看分块' },
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
      return h('span', { style: 'display:flex;gap:4px;flex-wrap:wrap;' }, [vectorizeBtn, chunkBtn, deleteBtn])
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
const uploadVisibility = ref<number>(1)

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
      setTimeout(() => {
        if (activeUploads.value === 0) {
          uploadPercent.value = 0
          uploadingFileName.value = ''
        }
      }, 600)
    }
  }
}

/* ---------- 分块预览抽屉 ---------- */
const chunkDrawerVisible = ref(false)
const chunkDrawerFile = ref<KnowledgeDoc | null>(null)
const chunks = ref<KnowledgeChunk[]>([])
const chunksLoading = ref(false)
const chunkQuery = ref('')
const chunkSearchResult = ref<KnowledgeChunk[]>([])
const chunkSearchLoading = ref(false)

const chunkDrawerTitle = computed(() => {
  if (!chunkDrawerFile.value) return '查看分块'
  return `查看分块 · ${chunkDrawerFile.value.fileName}`
})

function displayChunkIndex(c: KnowledgeChunk, fallback: number): string {
  const v = (c as any).chunk_index ?? (c as any).chunkIndex ?? (c as any).index
  if (v != null) return String(v)
  return String(fallback)
}

function displayChunkContent(c: KnowledgeChunk): string {
  const v = (c as any).content ?? (c as any).text ?? (c as any).snippet ?? ''
  return String(v)
}

function displayChunkMeta(c: KnowledgeChunk): string {
  const m = (c as any).metadata
  if (m && typeof m === 'object') {
    const keys = Object.keys(m as Record<string, unknown>)
    if (keys.length === 0) return ''
    // 展示简要：file_id / source 等
    const brief = keys.slice(0, 3).join(' · ')
    return brief
  }
  return ''
}

function displayMetadata(c: KnowledgeChunk): string {
  const m = (c as any).metadata
  if (!m) return ''
  try {
    if (typeof m === 'string') return m
    return JSON.stringify(m, null, 2)
  } catch {
    return String(m)
  }
}

function openChunkDrawer(row: KnowledgeDoc) {
  chunkDrawerFile.value = row
  chunkDrawerVisible.value = true
  chunkQuery.value = ''
  chunkSearchResult.value = []
  hybridHint.value = ''
  loadChunks()
  loadHybridSettings()
}

async function loadChunks() {
  if (!chunkDrawerFile.value) return
  chunksLoading.value = true
  try {
    const list = await knowledgeApi.chunks(chunkDrawerFile.value.id)
    chunks.value = Array.isArray(list) ? list : []
    if (chunks.value.length === 0) {
      // 显式提示后端返回空
    }
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '加载分块失败'
    message.warning(msg)
    chunks.value = []
  } finally {
    chunksLoading.value = false
  }
}

async function onChunkSearch() {
  const q = chunkQuery.value.trim()
  if (!q) {
    message.warning('请输入检索关键词')
    return
  }
  if (!chunkDrawerFile.value) return
  chunkSearchLoading.value = true
  try {
    // 优先用知识库搜索（后端为混合检索），透传 fileId 过滤以验证该文件贡献
    // knowledgeApi.search 目前仅支持 query，若后端支持 fileId 则兜底用 vectorSearchByFile
    let result: KnowledgeChunk[] = []
    try {
      const raw = await knowledgeApi.search(q, 8)
      result = (raw as unknown as KnowledgeChunk[]) ?? []
      // 若返回包含 file_id，按当前 fileId 过滤以突出本文件贡献
      const fid = String(chunkDrawerFile.value.id)
      const filtered = result.filter((it: any) => {
        const f = it.file_id ?? it.fileId ?? it.fileIdStr ?? (it.metadata as any)?.file_id ?? (it.metadata as any)?.fileId
        return f == null || String(f) === fid
      })
      // 若过滤后为空，保留原结果以便对比双路
      chunkSearchResult.value = filtered.length > 0 ? filtered : result
    } catch {
      // 兜底：按 fileId 的向量检索
      const fb = await knowledgeApi.vectorSearchByFile(chunkDrawerFile.value.id, q, 8)
      chunkSearchResult.value = Array.isArray(fb) ? fb : []
    }
    if (chunkSearchResult.value.length === 0) {
      message.info('该查询在当前文件中未命中，可尝试更短关键词以验证 tsvector 贡献')
    }
  } catch {
    // 已 toast
    chunkSearchResult.value = []
  } finally {
    chunkSearchLoading.value = false
  }
}

function resetChunkSearch() {
  chunkQuery.value = ''
  chunkSearchResult.value = []
}

/* ---------- 检索配置 ---------- */
const hybridForm = reactive({
  topK: 20,
  weightVector: 1,
  weightKeyword: 1,
  rrfK: 20,
})
const hybridLoading = ref(false)
const hybridSaving = ref(false)
const hybridHint = ref('')

function pickHybridNumber(settings: Record<string, unknown>, keys: string[], fallback: number): number {
  for (const k of keys) {
    const v = settings[k]
    const n = typeof v === 'number' ? v : Number(v)
    if (v !== undefined && v !== null && v !== '' && !Number.isNaN(n)) return n
  }
  return fallback
}

async function loadHybridSettings() {
  hybridLoading.value = true
  try {
    const s = await systemApi.getSettings()
    const map = s as Record<string, unknown>
    hybridForm.topK = pickHybridNumber(map, ['hagent.hybrid.keyword.top-k', 'hagent.hybrid.keyword.topK', 'hagent.hybrid.keyword.top_k'], 20)
    hybridForm.weightVector = pickHybridNumber(map, ['hagent.hybrid.weights.vector', 'hagent.hybrid.weight.vector'], 1)
    hybridForm.weightKeyword = pickHybridNumber(map, ['hagent.hybrid.weights.keyword', 'hagent.hybrid.weight.keyword'], 1)
    hybridForm.rrfK = pickHybridNumber(map, ['hagent.hybrid.rrf-k', 'hagent.hybrid.rrfK', 'hagent.hybrid.rrf_k'], 20)
  } catch {
    // 保持默认值
  } finally {
    hybridLoading.value = false
  }
}

async function saveHybridSettings() {
  hybridSaving.value = true
  try {
    await systemApi.updateSettings({
      'hagent.hybrid.keyword.top-k': String(hybridForm.topK),
      'hagent.hybrid.weights.vector': String(hybridForm.weightVector),
      'hagent.hybrid.weights.keyword': String(hybridForm.weightKeyword),
      'hagent.hybrid.rrf-k': String(hybridForm.rrfK),
    } as Record<string, unknown>)
    hybridHint.value = `已保存：topK=${hybridForm.topK}，向量权重=${hybridForm.weightVector}，关键词权重=${hybridForm.weightKeyword}，RRF K=${hybridForm.rrfK}`
    message.success('检索参数已保存')
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '保存失败'
    // 若后端白名单拦截 hagent.*，提示但不阻断
    hybridHint.value = msg.includes('忽略') || msg.includes('不允许') ? '后端暂未放行 hagent.hybrid 前缀，已尝试保存' : msg
  } finally {
    hybridSaving.value = false
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
.kb-table-card {
  width: 100%;
  border-radius: var(--radius-lg);
  background: var(--bg-elevated);
}

/* ---------- 分块抽屉 ---------- */
.chunk-drawer {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.chunk-search__label {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text-1);
  margin-bottom: var(--space-2);
}
.chunk-search__row {
  display: flex;
  gap: var(--space-2);
  align-items: center;
}
.chunk-search__meta {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-3);
  flex-wrap: wrap;
}
.chunk-search__hint {
  font-size: var(--text-xs);
  color: var(--text-3);
}
.chunk-search-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin-top: var(--space-2);
}
.chunk-list-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.chunk-list-title {
  font-weight: 600;
  font-size: var(--text-sm);
  color: var(--text-1);
}
.chunk-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.chunk-empty {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-4) 0;
}
.chunk-empty__tip {
  font-size: var(--text-xs);
}
.chunk-card {
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-elevated);
}
.chunk-card--search {
  border-color: var(--accent-border);
  background: var(--bg-hover);
}
.chunk-card__head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
}
.chunk-card__idx {
  font-size: var(--text-xs);
  font-weight: 700;
  color: var(--accent);
  background: var(--accent-soft);
  border: 1px solid var(--accent-border);
  border-radius: var(--radius-full);
  padding: 2px 8px;
}
.chunk-card__meta {
  font-size: var(--text-xs);
  color: var(--text-3);
  margin-left: auto;
}
.chunk-card__content {
  font-size: var(--text-sm);
  line-height: 1.7;
  color: var(--text-1);
  white-space: pre-wrap;
  word-break: break-word;
}
.chunk-card__metadata {
  margin-top: var(--space-2);
  padding-top: var(--space-2);
  border-top: 1px dashed var(--border);
}
.chunk-card__metadata-label {
  font-size: var(--text-xs);
  color: var(--text-3);
}
.chunk-card__metadata-pre {
  margin: var(--space-1) 0 0;
  padding: var(--space-2) var(--space-3);
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 1.6;
  color: var(--text-2);
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 160px;
  overflow: auto;
}

/* 检索配置 */
.chunk-config {
  padding: var(--space-4);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-hover);
}
.chunk-config__title {
  font-weight: 700;
  font-size: var(--text-sm);
  color: var(--text-1);
}
.chunk-config__desc {
  font-size: var(--text-xs);
  color: var(--text-3);
  margin-top: var(--space-1);
  margin-bottom: var(--space-3);
}
.chunk-config__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-3);
}
.chunk-config__item {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
.chunk-config__label {
  font-size: var(--text-xs);
  color: var(--text-2);
}
.chunk-config__input {
  width: 100%;
}
.chunk-config__actions {
  display: flex;
  gap: var(--space-2);
  justify-content: flex-end;
  margin-top: var(--space-4);
}
.chunk-config__hint {
  margin-top: var(--space-3);
  font-size: var(--text-xs);
}

@media (max-width: 900px) {
  .kb-stats {
    grid-template-columns: 1fr;
  }
  .chunk-config__grid {
    grid-template-columns: 1fr;
  }
}
</style>
