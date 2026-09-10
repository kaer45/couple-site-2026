/**
 * 动态模块 API（契约 §2 /api/moments）
 */
import request from '@/utils/request'

// 2.1 发布动态（需登录、已绑定）
export function create(data) {
  return request.post('/moments', data)
}

// 2.2 时间轴分页（需登录、已绑定），参数：{ current, size, anchorDate? }
//     anchorDate: yyyy-MM-dd，非空时只返回该日期当天及更早的动态（快速跳转用）
export function list(params) {
  return request.get('/moments', { params })
}

// 2.4 动态日期列表（yyyy-MM-dd 倒序，时间轴快速跳转栏用）
export function dates() {
  return request.get('/moments/dates')
}

// 2.5 动态月份列表（yyyy-MM 倒序，月份级快速定位用）
export function months() {
  return request.get('/moments/months')
}

// 2.3 删除动态（只能删自己发布的，否则 403）
export function remove(id) {
  return request.delete(`/moments/${id}`)
}
