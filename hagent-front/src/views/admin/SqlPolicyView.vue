<template>
  <div class="sql-policy-page">
    <n-card class="policy-card" :bordered="true" title="Text-to-SQL 表白名单">
      <template #header-extra>
        <span class="policy-hint">
          仅白名单内的表可被 Text-to-SQL 查询；按用户隔离的表由系统强制行级过滤（SQL 无需手写 user_id）
        </span>
        <n-button size="small" type="primary" @click="openCreate">
          新增表策略
        </n-button>
      </template>

      <n-data-table
        :columns="columns"
        :data="policies"
        :loading="loading"
        :row-key="(row: SqlTablePolicy) => row.id ?? row.tableName"
        :bordered="false"
        :pagination="{ pageSize: 10 }"
      />
    </n-card>

    <!-- 新增/编辑弹窗 -->
    <n-modal
      v-model:show="showModal"
      preset="card"
      :title="editingId ? '编辑表策略' : '新增表策略'"
      class="policy-modal"
    >
      <n-form label-placement="left" label-width="110">
        <n-form-item label="表名" required>
          <n-input v-model:value="form.tableName" placeholder="如 knowledge_file（仅字母数字下划线）" :disabled="!!editingId" />
        </n-form-item>
        <n-form-item label="按用户隔离">
          <n-switch v-model:value="form.userScopedBool" />
          <span class="form-hint">开启后强制注入 user_id = 当前用户，用户只能查到自己的数据</span>
        </n-form-item>
        <n-form-item label="隔离列名">
          <n-input v-model:value="form.userColumn" placeholder="默认 user_id" />
        </n-form-item>
        <n-form-item label="支持公私">
          <n-switch v-model:value="form.visibilityAwareBool" />
          <span class="form-hint">开启后公有记录（visibility=1）对所有用户可见，私有记录仅归属人可见</span>
        </n-form-item>
        <n-form-item label="启用">
          <n-switch v-model:value="form.enabledBool" />
        </n-form-item>
        <n-form-item label="备注">
          <n-input v-model:value="form.remark" type="textarea" :rows="2" placeholder="策略说明（可选）" />
        </n-form-item>
      </n-form>
      <template #footer>
        <div class="modal-actions">
          <n-button @click="showModal = false">取消</n-button>
          <n-button type="primary" :loading="saving" @click="onSave">保存（自动校验表存在性）</n-button>
        </div>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { h, onMounted, ref } from 'vue'
import {
  NButton,
  NCard,
  NDataTable,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NModal,
  NSwitch,
  NTag,
  useMessage,
  type DataTableColumns,
} from 'naive-ui'
import { DeleteOutlined, EditOutlined } from '@vicons/antd'
import { sqlPolicyApi, type SqlTablePolicy } from '@/api/sqlPolicy'

interface PolicyRow extends SqlTablePolicy {
  userScopedBool: boolean
  visibilityAwareBool: boolean
  enabledBool: boolean
}

const message = useMessage()
const policies = ref<PolicyRow[]>([])
const loading = ref(false)
const showModal = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)

const form = ref({
  tableName: '',
  userScopedBool: true,
  userColumn: 'user_id',
  visibilityAwareBool: false,
  enabledBool: true,
  remark: '',
})

function toRow(p: SqlTablePolicy): PolicyRow {
  return {
    ...p,
    userScopedBool: p.userScoped === 1,
    visibilityAwareBool: p.visibilityAware === 1,
    enabledBool: p.enabled === 1,
  }
}

async function load() {
  loading.value = true
  try {
    const list = await sqlPolicyApi.list()
    policies.value = (list ?? []).map(toRow)
  } catch {
    // 已 toast
    policies.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)

function openCreate() {
  editingId.value = null
  form.value = {
    tableName: '',
    userScopedBool: true,
    userColumn: 'user_id',
    visibilityAwareBool: false,
    enabledBool: true,
    remark: '',
  }
  showModal.value = true
}

function openEdit(row: PolicyRow) {
  editingId.value = row.id ?? null
  form.value = {
    tableName: row.tableName,
    userScopedBool: row.userScopedBool,
    userColumn: row.userColumn || 'user_id',
    visibilityAwareBool: row.visibilityAwareBool,
    enabledBool: row.enabledBool,
    remark: row.remark || '',
  }
  showModal.value = true
}

async function onSave() {
  if (!form.value.tableName.trim()) {
    message.warning('请填写表名')
    return
  }
  saving.value = true
  const payload: SqlTablePolicy = {
    tableName: form.value.tableName.trim(),
    userScoped: form.value.userScopedBool ? 1 : 0,
    userColumn: form.value.userColumn.trim() || 'user_id',
    visibilityAware: form.value.visibilityAwareBool ? 1 : 0,
    enabled: form.value.enabledBool ? 1 : 0,
    remark: form.value.remark || undefined,
  }
  try {
    if (editingId.value) {
      await sqlPolicyApi.update(editingId.value, payload)
      message.success('策略已更新')
    } else {
      await sqlPolicyApi.create(payload)
      message.success('策略已新增')
    }
    showModal.value = false
    await load()
  } catch {
    // 已 toast（含表不存在校验错误）
  } finally {
    saving.value = false
  }
}

async function onDelete(row: PolicyRow) {
  try {
    await sqlPolicyApi.remove(row.id!)
    message.success(`已移除表 ${row.tableName} 的查询权限`)
    await load()
  } catch {
    // 已 toast
  }
}

const columns: DataTableColumns<PolicyRow> = [
  { title: '表名', key: 'tableName', className: 'mono' },
  {
    title: '用户隔离',
    key: 'userScoped',
    width: 110,
    render: (row) =>
      h(
        NTag,
        { size: 'small', type: row.userScopedBool ? 'success' : 'default', bordered: false },
        { default: () => (row.userScopedBool ? '按用户' : '全局') },
      ),
  },
  { title: '隔离列', key: 'userColumn', width: 110, className: 'mono' },
  {
    title: '公私支持',
    key: 'visibilityAware',
    width: 100,
    render: (row) =>
      h(
        NTag,
        { size: 'small', type: row.visibilityAwareBool ? 'info' : 'default', bordered: false },
        { default: () => (row.visibilityAwareBool ? '公私' : '无') },
      ),
  },
  {
    title: '状态',
    key: 'enabled',
    width: 90,
    render: (row) =>
      h(
        NTag,
        { size: 'small', type: row.enabledBool ? 'success' : 'warning', bordered: false },
        { default: () => (row.enabledBool ? '启用' : '停用') },
      ),
  },
  { title: '备注', key: 'remark', ellipsis: { tooltip: true } },
  {
    title: '操作',
    key: 'actions',
    width: 130,
    render: (row) =>
      h('span', { style: 'display: flex; gap: 4px;' }, [
        h(
          NButton,
          { size: 'small', quaternary: true, onClick: () => openEdit(row) },
          { icon: () => h(NIcon, null, { default: () => h(EditOutlined) }), default: () => '编辑' },
        ),
        h(
          NButton,
          { size: 'small', quaternary: true, type: 'error', onClick: () => onDelete(row) },
          { icon: () => h(NIcon, null, { default: () => h(DeleteOutlined) }), default: () => '删除' },
        ),
      ]),
  },
]
</script>

<style scoped>
.sql-policy-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.policy-card :deep(.n-card-header) {
  align-items: flex-start;
}
.policy-hint {
  font-size: 12px;
  color: var(--text-secondary, #888);
  margin-right: 12px;
}
.form-hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--text-secondary, #888);
}
.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
