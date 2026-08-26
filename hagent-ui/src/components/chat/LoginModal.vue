<template>
  <n-modal
    :show="show"
    preset="card"
    title="登录"
    style="width: 420px"
    :mask-closable="false"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <n-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-placement="left"
      label-width="64"
      require-mark-placement="right-hanging"
    >
      <n-form-item label="用户名" path="username">
        <n-input v-model:value="form.username" placeholder="请输入用户名" clearable />
      </n-form-item>
      <n-form-item label="密码" path="password">
        <n-input
          v-model:value="form.password"
          type="password"
          placeholder="请输入密码"
          clearable
          show-password-on="click"
          @keydown.enter.prevent="handleSubmit"
        />
      </n-form-item>
    </n-form>

    <template #footer>
      <div style="display: flex; justify-content: flex-end; gap: 12px">
        <n-button @click="emit('update:show', false)">取消</n-button>
        <n-button type="primary" :loading="loading" @click="handleSubmit">登录</n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { NModal, NForm, NFormItem, NInput, NButton, useMessage, type FormInst, type FormRules } from 'naive-ui'
import { authApi } from '@/api/auth'

const props = defineProps<{
  show: boolean
}>()

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'success'): void
}>()

void props

const message = useMessage()
const formRef = ref<FormInst | null>(null)
const loading = ref(false)

const form = ref({
  username: '',
  password: '',
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: ['input', 'blur'] }],
  password: [{ required: true, message: '请输入密码', trigger: ['input', 'blur'] }],
}

async function handleSubmit() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    await authApi.login(form.value.username.trim(), form.value.password)
    message.success('登录成功')
    emit('success')
    emit('update:show', false)
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '登录失败，请重试'
    message.error(msg)
  } finally {
    loading.value = false
  }
}
</script>
