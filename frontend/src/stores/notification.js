/**
 * 通知未读数状态（Pinia store）
 * - unread：顶部铃铛红点数，供 Layout 与通知页共享
 * - refresh：从后端拉一次未读数
 * - decrement：本地 -1（某条标记已读后），下限 0
 * - reset：登出 / 401 时清零
 */
import { defineStore } from 'pinia'
import * as notificationApi from '@/api/notification'

export const useNotificationStore = defineStore('notification', {
  state: () => ({
    unread: 0
  }),

  actions: {
    async refresh() {
      try {
        this.unread = await notificationApi.unreadCount()
      } catch (e) {
        /* 错误提示已由拦截器统一处理 */
      }
    },

    decrement() {
      if (this.unread > 0) {
        this.unread -= 1
      }
    },

    reset() {
      this.unread = 0
    }
  }
})