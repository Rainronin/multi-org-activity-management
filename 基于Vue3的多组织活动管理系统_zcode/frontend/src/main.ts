import { createApp, nextTick } from 'vue'
import { createPinia } from 'pinia'
import { provideGlobalConfig } from 'element-plus'
// 未显式配置时组件库回落到英文（Select 占位、Pagination 等内置文案）；统一为简体中文。
import zhCn from 'element-plus/es/locale/lang/zh-cn'
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
// 在根实例上提供语言包，弹层与懒加载组件同样生效。
provideGlobalConfig({ locale: zhCn }, app, true)

const session = useSessionStore()
session.bindRedirect((target) => {
  const current = router.currentRoute.value
  const generation = session.capture()
  const destination = target === '/login' && current.path !== '/login'
    ? { path: target, query: { next: current.fullPath } }
    : target
  // 先卸载旧会话页面及草稿守卫，再引导登录；迟到引导不能打断新会话。
  void nextTick(() => {
    if (generation === session.generation && session.status === 'guest') {
      return router.push(destination)
    }
  })
})

// 切回标签页时重新验证会话（开发规范 2.4 / 审核 R02）。
document.addEventListener('visibilitychange', () => {
  if (!document.hidden && session.status === 'authenticated') {
    void session.revalidate()
  }
})

app.mount('#app')
