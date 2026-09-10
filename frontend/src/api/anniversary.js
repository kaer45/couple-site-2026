/**
 * 纪念日模块 API（契约 §3 /api/anniversaries）
 */
import request from '@/utils/request'

// 3.1 首页汇总（需登录、已绑定）→ { daysTogether, nextAnniversary, upcoming }
export function summary() {
  return request.get('/anniversaries/summary')
}

// 3.2 纪念日列表（需登录、已绑定）→ Anniversary[]
export function list() {
  return request.get('/anniversaries')
}

// 3.3 新增纪念日：{ name, date, remindDays }
export function create(data) {
  return request.post('/anniversaries', data)
}

// 3.4 修改纪念日：{ name, date, remindDays }
export function update(id, data) {
  return request.put(`/anniversaries/${id}`, data)
}

// 3.5 删除纪念日（is_start=1 的不可删除，后端 403）
export function remove(id) {
  return request.delete(`/anniversaries/${id}`)
}
