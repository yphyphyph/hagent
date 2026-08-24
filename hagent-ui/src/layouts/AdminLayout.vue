<template>
  <div class="admin-shell">
    <n-layout has-sider class="admin-layout">
      <n-layout-sider
        bordered
        collapse-mode="width"
        :collapsed-width="64"
        :width="220"
        show-trigger
        :collapsed="collapsed"
        @collapse="collapsed = true"
        @expand="collapsed = false"
      >
        <div class="admin-side">
          <router-link to="/admin/chat" class="admin-logo">
            <span class="admin-logo__mark">H</span>
            <span v-if="!collapsed" class="admin-logo__text">HAgent 控制台</span>
          </router-link>
          <n-menu
            :value="activeKey"
            :collapsed="collapsed"
            :collapsed-width="64"
            :options="menuOptions"
            @update:value="onMenuSelect"
          />
        </div>
      </n-layout-sider>

      <n-layout>
        <n-layout-header bordered class="admin-header">
          <div class="admin-header__title">{{ route.meta.title }}</div>
          <div class="admin-header__actions">
            <router-link to="/" class="admin-header__portal">
              <n-button size="small" quaternary>前往前台</n-button>
            </router-link>
            <HsThemeToggle />
            <n-button size="small" quaternary @click="onLogout">退出</n-button>
          </div>
        </n-layout-header>
        <n-layout-content class="admin-content" content-style="padding: 24px;">
          <router-view />
        </n-layout-content>
      </n-layout>
    </n-layout>
  </div>
</template>

<script setup lang="ts">
import { computed, h, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NIcon, NMenu, NLayout, NLayoutSider, NLayoutHeader, NLayoutContent, NButton, type MenuOption } from 'naive-ui'
import {
  MessageOutlined,
  ToolOutlined,
  DatabaseOutlined,
  SettingOutlined,
} from '@vicons/antd'
import HsThemeToggle from '@/components/ui/HsThemeToggle.vue'
import { authApi } from '@/api/auth'

const route = useRoute()
const router = useRouter()
const collapsed = ref(false)

function renderIcon(icon: ReturnType<typeof h>) {
  return () => h(NIcon, null, { default: () => icon })
}

const menuOptions: MenuOption[] = [
  { label: 'AI 对话', key: '/admin/chat', icon: renderIcon(h(MessageOutlined)) },
  { label: 'AI 工具', key: '/admin/tools', icon: renderIcon(h(ToolOutlined)) },
  { label: '知识库', key: '/admin/knowledge', icon: renderIcon(h(DatabaseOutlined)) },
  { label: '设置', key: '/admin/settings', icon: renderIcon(h(SettingOutlined)) },
]

const activeKey = computed(() => route.path)

function onMenuSelect(key: string) {
  router.push(key)
}

async function onLogout() {
  try {
    await authApi.logout()
  } catch {
    // 忽略登出接口错误，本地清理兜底（与前台 PortalLayout 行为一致）
  }
  authApi.clearLocal()
  router.push('/login')
}
</script>

<style scoped>
.admin-shell {
  height: 100vh;
  overflow: hidden;
}
.admin-layout {
  height: 100vh;
}
.admin-side {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.admin-logo {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px;
  border-bottom: 1px solid var(--border);
  color: var(--text-1);
}
.admin-logo__mark {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  background: var(--accent);
  color: #0f172a;
  font-weight: 800;
  border-radius: 7px;
  font-family: var(--font-mono);
}
.admin-logo__text {
  font-weight: 700;
  white-space: nowrap;
}
.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  height: 56px;
}
.admin-header__title {
  font-weight: 600;
}
.admin-header__portal {
  text-decoration: none;
}
.admin-header__actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.admin-content {
  background: var(--bg);
}
</style>
