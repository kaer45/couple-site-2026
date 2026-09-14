/**
 * 相册模块 API（契约 §4 /api/albums）
 */
import request from '@/utils/request'

// 4.1 相册列表（需登录、已绑定）→ Album[]
export function list() {
  return request.get('/albums')
}

// 4.2 创建相册：{ name, description }
export function create(data) {
  return request.post('/albums', data)
}

// 4.3 相册详情（需登录、已绑定，仅本情侣）→ { id, name, description, coverUrl, photos }
export function detail(id) {
  return request.get(`/albums/${id}`)
}

// 4.4 上传照片到相册
// 前端流程：先经 /api/files/upload 逐张上传拿到 url 数组，再由 urls+types 绑定到相册
// types 与 urls 一一对应（IMAGE / VIDEO），可省略（缺省按 IMAGE）
export function uploadPhotos(id, urls, types, description) {
  return request.post(`/albums/${id}/photos`, { urls, types, description })
}

// 4.5 手动设置相册封面（先经 /api/files/upload 上传，或直接用相册内已有照片 URL）
export function setCover(id, coverUrl) {
  return request.put(`/albums/${id}/cover`, { coverUrl })
}

// 4.6 删除照片（只能删自己上传的，否则 403）
export function removePhoto(albumId, photoId) {
  return request.delete(`/albums/${albumId}/photos/${photoId}`)
}

// 4.7 删除相册（级联删除其下照片）
export function remove(id) {
  return request.delete(`/albums/${id}`)
}
