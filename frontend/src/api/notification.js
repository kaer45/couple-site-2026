/**
 * 通知模块 API
 */
import request from '@/utils/request'

// 分页拉通知列表 → PageResult<NotificationVO>
export function page({ current = 1, size = 10 } = {}) {
  return request.get('/notifications', { params: { current, size } })
}

// 未读数（红点）→ Long
export function unreadCount() {
  return request.get('/notifications/unread-count')
}

// 标记某条已读
export function markRead(id) {
  return request.put(`/notifications/${id}/read`)
}

// 全部已读
export function readAll() {
  return request.put('/notifications/read-all')
}