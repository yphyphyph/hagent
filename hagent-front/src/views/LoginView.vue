<template>
  <div class="login-page">
    <div class="login-page__glow" aria-hidden="true"></div>
    <div class="login-page__grid" aria-hidden="true"></div>

    <div class="login-card">
      <!-- 左侧品牌区 -->
      <div class="login-brand">
        <div class="login-brand__logo">
          <span class="login-brand__mark">H</span>
          <div>
            <div class="login-brand__name">HAgent</div>
            <div class="login-brand__sub mono">AI management console</div>
          </div>
        </div>
        <p class="login-brand__slogan">
          让 AI 成为你的<br />高效作业引擎。
        </p>
        <ul class="login-brand__points">
          <li v-for="p in brandPoints" :key="p">{{ p }}</li>
        </ul>
      </div>

      <!-- 右侧登录表单 -->
      <div class="login-form">
        <h1 class="login-form__title">登录控制台</h1>
        <p class="login-form__desc">欢迎回来，登录管理你的 AI 智能体</p>

        <n-form
          ref="formRef"
          :model="model"
          :rules="rules"
          label-placement="top"
          size="large"
          @keyup.enter="onSubmit"
        >
          <n-form-item label="用户名" path="username">
            <n-input
              v-model:value="model.username"
              placeholder="请输入用户名"
              :input-props="{ autocomplete: 'username' }"
            >
              <template #prefix>
                <n-icon :component="UserOutlined" />
              </template>
            </n-input>
          </n-form-item>
          <n-form-item label="密码" path="password">
            <n-input
              v-model:value="model.password"
              type="password"
              show-password-on="click"
              placeholder="请输入密码"
              :input-props="{ autocomplete: 'current-password' }"
            >
              <template #prefix>
                <n-icon :component="LockOutlined" />
              </template>
            </n-input>
          </n-form-item>

          <n-button
            type="primary"
            block
            size="large"
            :loading="loading"
            class="login-form__submit"
            @click="onSubmit"
          >
            登 录
          </n-button>

          <!-- 返回前台：移至登录按钮左下方，便于用户发现 -->
          <router-link to="/" class="login-form__back" aria-label="返回前台">
            <n-icon :component="ArrowLeftOutlined" :size="14" />
            返回前台
          </router-link>
        </n-form>

        <div class="login-form__foot">
          <span class="login-form__hint">— HAgent —</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  NButton,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  useMessage,
  type FormInst,
  type FormRules,
} from 'naive-ui'
import { UserOutlined, LockOutlined, ArrowLeftOutlined } from '@vicons/antd'
import { authApi } from '@/api/auth'

const route = useRoute()
const router = useRouter()
const message = useMessage()

const brandPoints = ['AI 对话与工具', '知识库 RAG 检索', '多模型智能体编排']

interface LoginModel {
  username: string
  password: string
}

const model = ref<LoginModel>({ username: '', password: '' })
const formRef = ref<FormInst | null>(null)
const loading = ref(false)

const rules: FormRules = {
  username: { required: true, message: '请输入用户名', trigger: ['input', 'blur'] },
  password: { required: true, message: '请输入密码', trigger: ['input', 'blur'] },
}

async function onSubmit() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    const data = await authApi.login(model.value.username, model.value.password)
    message.success(`欢迎回来，${data.username}`)
    const redirect = (route.query.redirect as string) || '/admin/tools'
    router.push(redirect)
  } catch {
    // 错误提示由 request 拦截器统一处理
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-5);
  overflow: hidden;
}
.login-page__glow {
  position: absolute;
  top: -220px;
  left: 50%;
  transform: translateX(-50%);
  width: 720px;
  height: 480px;
  background: radial-gradient(ellipse, var(--accent-soft), transparent 65%);
  pointer-events: none;
}
.login-page__grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(var(--border) 1px, transparent 1px),
    linear-gradient(90deg, var(--border) 1px, transparent 1px);
  background-size: 48px 48px;
  mask-image: radial-gradient(ellipse 70% 55% at 50% 35%, black 25%, transparent 72%);
  pointer-events: none;
}

.login-card {
  position: relative;
  display: grid;
  grid-template-columns: minmax(280px, 5fr) minmax(320px, 6fr);
  width: 100%;
  max-width: 880px;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  overflow: hidden;
  animation: fade-up var(--dur-slow) var(--ease) both;
}

/* ---------- 品牌区 ---------- */
.login-brand {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: var(--space-6);
  padding: var(--space-7) var(--space-6);
  background: var(--bg-hover);
  border-right: 1px solid var(--border);
}
.login-brand__logo {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}
.login-brand__mark {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  background: var(--accent);
  color: #0f172a;
  font-weight: 800;
  font-size: var(--text-lg);
  border-radius: var(--radius-md);
  font-family: var(--font-mono);
  box-shadow: var(--shadow-accent);
}
.login-brand__name {
  font-size: var(--text-lg);
  font-weight: 800;
  letter-spacing: -0.02em;
}
.login-brand__sub {
  font-size: var(--text-xs);
  color: var(--text-3);
}
.login-brand__slogan {
  font-size: var(--text-xl);
  font-weight: 700;
  line-height: 1.4;
  letter-spacing: -0.02em;
}
.login-brand__points {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: 0;
}
.login-brand__points li {
  position: relative;
  padding-left: var(--space-4);
  color: var(--text-2);
  font-size: var(--text-sm);
}
.login-brand__points li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
}

/* ---------- 表单区 ---------- */
.login-form {
  padding: var(--space-7) var(--space-6);
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.login-form__title {
  font-size: var(--text-2xl);
  font-weight: 800;
  letter-spacing: -0.02em;
  margin-bottom: var(--space-1);
}
.login-form__desc {
  color: var(--text-3);
  font-size: var(--text-sm);
  margin-bottom: var(--space-6);
}
.login-form__submit {
  margin-top: var(--space-2);
}
/* 返回前台文本链接：位于登录按钮下方左对齐，保证可发现性 */
.login-form__back {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: 12px;
  font-size: 13px;
  color: var(--text-3);
  text-decoration: none;
  min-height: 32px; /* 无障碍最小点击高度 */
  line-height: 32px;
  transition: color 0.2s var(--ease);
}
.login-form__back:hover {
  color: var(--text-1);
}
.login-form__back:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
  border-radius: 4px;
}
.login-form__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: var(--space-5);
  font-size: var(--text-sm);
}
.login-form__hint {
  color: var(--text-3);
  font-size: var(--text-xs);
}

@media (max-width: 768px) {
  .login-card {
    grid-template-columns: 1fr;
  }
  .login-brand {
    border-right: none;
    border-bottom: 1px solid var(--border);
    padding: var(--space-5);
    gap: var(--space-4);
  }
  .login-brand__slogan {
    font-size: var(--text-md);
  }
  .login-brand__points {
    display: none;
  }
}
</style>
