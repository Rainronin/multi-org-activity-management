import { createApp } from 'vue'
import './styles/tokens.css'
import 'element-plus/dist/index.css'
import './styles/element-plus.css'
import './styles/base.css'
import App from './App.vue'

// 导入顺序遵循 UI 规范 4.1：token → 官方组件样式 → 项目映射 → base → 页面。
// 路由与 Pinia 随 P1 首个实际页面接入。
createApp(App).mount('#app')
