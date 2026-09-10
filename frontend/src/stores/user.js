/**
 * 用户状态（Pinia store）
 * - token：登录凭证，持久化到 localStorage
 * - userInfo：当前用户信息（含 bound 绑定状态）
 * - partner：伴侣信息
 * - couple：情侣信息（含在一起日期、天数）
 */
import { defineStore } from 'pinia'
import * as authApi from '@/api/auth'

const TOKEN_KEY = 'token'

export const useUserStore = defineStore('user', {
  state: () => ({
    // 初始化时从 localStorage 恢复 token，保证刷新页面仍处于登录态
    token: localStorage.getItem(TOKEN_KEY) || '',
    userInfo: null,
    partner: null,
    couple: null
  }),

  getters: {
    // 是否已绑定情侣
    isBound: (state) => !!state.userInfo?.bound,
    // 展示昵称兜底
    nickname: (state) => state.userInfo?.nickname || '亲爱的'
  },

  actions: {
    // 保存 token（内存 + localStorage 双写）
    setToken(token) {
      this.token = token
      localStorage.setItem(TOKEN_KEY, token)
    },

    // 清除 token
    clearToken() {
      this.token = ''
      localStorage.removeItem(TOKEN_KEY)
    },

    // 登录
    async login(payload) {
      const data = await authApi.login(payload)
      this.setToken(data.token)
      this.userInfo = data.user
      return data
    },

    // 注册（响应含 coupleCode 情侣码，供前端展示）
    async register(payload) {
      const data = await authApi.register(payload)
      this.setToken(data.token)
      this.userInfo = data.user
      return data
    },

    // 绑定情侣
    async bind(payload) {
      const data = await authApi.bind(payload)
      this.partner = data.partner
      this.couple = { id: data.coupleId, startDate: data.startDate }
      if (this.userInfo) {
        this.userInfo.bound = true
      }
      return data
    },

    // 拉取当前用户信息（含伴侣与情侣信息）
    async fetchMe() {
      const data = await authApi.me()
      this.userInfo = data.user
      this.partner = data.partner
      this.couple = data.couple
      return data
    },

    // 退出登录：清空全部状态
    logout() {
      this.clearToken()
      this.userInfo = null
      this.partner = null
      this.couple = null
    },

    // 供 401 拦截器调用：只重置认证相关状态，不清 localStorage 之外的东西
    resetAuth() {
      this.clearToken()
      this.userInfo = null
      this.partner = null
      this.couple = null
    }
  }
})
