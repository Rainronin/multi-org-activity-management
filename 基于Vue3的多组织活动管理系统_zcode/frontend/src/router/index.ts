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
      meta: { public: true, title: '登录' },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('../features/account/RegisterPage.vue'),
      meta: { public: true, title: '注册' },
    },
    {
      path: '/recover',
      name: 'recover',
      component: () => import('../features/account/RecoverPage.vue'),
      meta: { public: true, title: '找回账号' },
    },
    {
      path: '/org/:orgId',
      component: () => import('../layouts/OrganizationLayout.vue'),
      children: [
        {
          path: '',
          name: 'org-overview',
          component: () => import('../features/organization/OrgOverviewPage.vue'),
          meta: { title: '组织概览' },
        },
        {
          path: 'members',
          name: 'org-members',
          component: () => import('../features/organization/OrgMembersPage.vue'),
          meta: { title: '成员管理' },
        },
        {
          path: 'activities',
          name: 'org-activities',
          component: () => import('../features/activity/ActivitiesPage.vue'),
          meta: { title: '活动管理' },
        },
        {
          path: 'my-tasks',
          name: 'org-my-tasks',
          component: () => import('../features/activity/MyTasksPage.vue'),
          meta: { title: '我的任务' },
        },
        {
          path: 'activities/:activityId',
          name: 'org-activity-detail',
          component: () => import('../features/activity/ActivityDetailPage.vue'),
          meta: { title: '活动详情' },
        },
      ],
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
