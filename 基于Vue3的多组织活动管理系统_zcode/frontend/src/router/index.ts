import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from '../stores/session'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'account-home',
      component: () => import('../features/account/AccountPage.vue'),
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('../features/account/LoginPage.vue'),
      meta: { public: true },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('../features/account/RegisterPage.vue'),
      meta: { public: true },
    },
    {
      path: '/recover',
      name: 'recover',
      component: () => import('../features/account/RecoverPage.vue'),
      meta: { public: true },
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async (to) => {
  const session = useSessionStore()
  if (session.status === 'unknown') {
    try {
      await session.bootstrap()
    } catch {
      // 网络失败不能当作未登录（开发规范 2.4）；页面数据请求会再次反馈错误。
      return true
    }
  }
  if (!to.meta.public && session.status !== 'authenticated') {
    return { path: '/login', query: { next: to.fullPath } }
  }
  if (to.name === 'login' && session.status === 'authenticated') {
    return { path: '/' }
  }
  return true
})
