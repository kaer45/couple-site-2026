/**
 * 路由配置 + 全局前置守卫
 * - 需登录页面：Home(/)、纪念日、相册、相册详情、绑定页
 * - 守卫逻辑：
 *   1. 无 token 访问需登录页 → 跳 /login（携带 redirect 参数）
 *   2. 有 token 访问 /login、/register、/bind，若已绑定 → 跳 /
 */
import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/Register.vue'),
    meta: { title: '注册' }
  },
  {
    path: '/bind',
    name: 'bind',
    component: () => import('@/views/Bind.vue'),
    meta: { title: '绑定情侣', requiresAuth: true }
  },
  {
    path: '/',
    component: () => import('@/layout/Layout.vue'),
    children: [
      {
        path: '',
        name: 'home',
        component: () => import('@/views/Home.vue'),
        meta: { title: '首页时间轴', requiresAuth: true }
      },
      {
        path: 'anniversaries',
        name: 'anniversaries',
        component: () => import('@/views/Anniversary.vue'),
        meta: { title: '纪念日', requiresAuth: true }
      },
      {
        path: 'albums',
        name: 'albums',
        component: () => import('@/views/Album.vue'),
        meta: { title: '相册', requiresAuth: true }
      },
      {
        path: 'albums/:id',
        name: 'album-detail',
        component: () => import('@/views/AlbumDetail.vue'),
        meta: { title: '相册详情', requiresAuth: true }
      },
      {
        path: 'notifications',
        name: 'notifications',
        component: () => import('@/views/Notification.vue'),
        meta: { title: '通知', requiresAuth: true }
      }
    ]
  },
  // 兜底：未知路径回首页
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局前置守卫
router.beforeEach(async (to) => {
  const userStore = useUserStore()
  const needAuth = to.matched.some((record) => record.meta.requiresAuth)

  // 1. 未登录访问需登录页面 → 去登录
  if (needAuth && !userStore.token) {
    ElMessage.warning('请先登录')
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 2. 已登录访问 登录/注册/绑定 页：若已绑定 → 回首页
  if (userStore.token && ['/login', '/register', '/bind'].includes(to.path)) {
    // 若尚未拉取用户信息（如刚刷新页面），先拉取一次以判断绑定状态
    if (!userStore.userInfo) {
      try {
        await userStore.fetchMe()
      } catch (e) {
        /* token 失效时 401 拦截器会清 token 并跳登录，此处静默 */
      }
    }
    if (userStore.isBound) {
      return '/'
    }
  }

  // 设置浏览器标题
  if (to.meta.title) {
    document.title = `${to.meta.title} - 我们的小窝`
  }
})

export default router
