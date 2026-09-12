import { createApp } from 'vue'
import { createPinia } from 'pinia'
import './styles/tokens.css'
import 'element-plus/dist/index.css'
import './styles/element-plus.css'
import './styles/base.css'
import App from './App.vue'
import { router } from './router'
import { useSessionStore } from './stores/session'

// 导入顺序遵循 UI 规范 4.1：token → 官方组件样式 → 项目映射 → base → 页面。
const app = createApp(App)
app.use(createPinia())
app.use(router)

const session = useSessionStore()
session.bindRedirect((target) => {
  void router.push(target)
})

// 切回标签页时重新验证会话（开发规范 2.4 / 审核 R02）。
document.addEventListener('visibilitychange', () => {
  if (!document.hidden && session.status === 'authenticated') {
    void session.revalidate()
  }
})

app.mount('#app')
