/**
 * 聊天模块 API（REST 部分，实时收发走 WebSocket）
 */
import request from '@/utils/request'

// 历史消息：?beforeId=&size= （beforeId 为空取最新 size 条，返回按 id 倒序）
export function history(params) {
  return request.get('/chat/messages', { params })
}

// 增量消息：?afterId=（断线重连/新消息拉取，按 id 正序）
export function after(afterId) {
  return request.get('/chat/messages/after', { params: { afterId } })
}

// 上报已读位点
export function markRead(lastReadMessageId) {
  return request.patch('/chat/read', { lastReadMessageId })
}

// 未读数（对方发给我、未读的消息数）
export function unreadCount() {
  return request.get('/chat/unread-count')
}