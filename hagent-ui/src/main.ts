import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
/* 设计字体：Inter Variable（西文）+ JetBrains Mono（代码/数字），未加载时回退系统字体 */
import '@fontsource-variable/inter'
import '@fontsource/jetbrains-mono/400.css'
import '@fontsource/jetbrains-mono/500.css'
import '@fontsource/jetbrains-mono/700.css'
import './styles/tokens.css'
import './styles/global.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
