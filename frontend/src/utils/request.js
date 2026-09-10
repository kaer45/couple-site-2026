/**
 * axios 请求封装
 * - baseURL: '/api'（开发环境由 Vite 代理转发到后端 http://localhost:8080）
 * - 请求拦截：从 localStorage 取 token，自动携带 Authorization: Bearer <token>
 * - 响应拦截：
 *   - 业务码 code === 0 → 直接返回 res.data
 *   - 业务码非 0 → ElMessage.error 提示并 reject
 *   - HTTP 401 → 清除 token 并跳转登录页
 *   - 其他 HTTP 错误 → 提示 error.response?.data?.message
 */
import axios from 'axios'
import { ElMessage } from 'element-plus'

const request = axios.create({
  baseURL: '/api',
  timeout: 15000
})

// 请求拦截器：注入 token
request.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截器：统一处理业务码与 HTTP 错误
request.interceptors.response.use(
  (response) => {
    const res = response.data
    // 契约统一响应体：{ code, message, data }
    if (res && res.code === 0) {
      return res.data
    }
    ElMessage.error(res?.message || '请求失败，请稍后重试')
    return Promise.reject(new Error(res?.message || '请求失败'))
  },
  async (error) => {
    const status = error.response?.status
    const message = error.response?.data?.message || error.message || '网络异常，请稍后重试'

    if (status === 401) {
      // 未登录 / Token 失效：清除本地 token 并跳转登录页
      localStorage.removeItem('token')
      ElMessage.error(message || '登录已过期，请重新登录')
      try {
        // 动态引入 store 并重置用户态（避免与 router/store 形成静态循环依赖）
        const { useUserStore } = await import('@/stores/user')
        useUserStore().resetAuth()
      } catch (e) {
        /* store 未初始化时忽略 */
      }
      try {
        const { default: router } = await import('@/router')
        if (router.currentRoute.value.path !== '/login') {
          router.push({ path: '/login' })
        }
      } catch (e) {
        /* 忽略 */
      }
    } else {
      // 其他 HTTP 错误：优先取后端返回的 message
      ElMessage.error(message)
    }
    return Promise.reject(error)
  }
)

export default request
