/**
 * 每日待办模块 API（/api/todos）
 */
import request from '@/utils/request'

// 今日视图：{ overdue, today, upcoming, completed }
export function todayView() {
  return request.get('/todos/today-view')
}

// 新增：{ title, priority?, dueDate? }
export function create(data) {
  return request.post('/todos', data)
}

// 更新（全量）：{ title, priority, dueDate, done }
export function update(id, data) {
  return request.put(`/todos/${id}`, data)
}

// 删除
export function remove(id) {
  return request.delete(`/todos/${id}`)
}