/**
 * 学习笔记模块 API（/api/notes）
 * 笔记为**个人私有**数据：后端只按 JWT 里的 userId 隔离，前端不传 userId
 */
import request from '@/utils/request'

// 分页列表：{ current, size, keyword? }
export function page(params) {
  return request.get('/notes', { params })
}

// 详情（含抽取出的纯文本正文）
export function detail(id) {
  return request.get(`/notes/${id}`)
}

// 上传：multipart/form-data（file 必传，title 可选，为空取原文件名）
export function upload(file, title) {
  const form = new FormData()
  form.append('file', file)
  if (title) form.append('title', title)
  return request.post('/notes', form)
}

// 修改标题（正文来自原始文件，不单独编辑）
export function update(id, data) {
  return request.put(`/notes/${id}`, data)
}

/**
 * 手动生成 AI 内容摘要。
 * 后端是同步等待 Python（最长约 60s），所以这里单独放宽 axios 超时，
 * 否则会先被默认的 15s 掐断。
 */
export function generateSummary(id) {
  return request.post(`/notes/${id}/summary`, null, { timeout: 90000 })
}

// 删除（连同磁盘文件与向量数据）
export function remove(id) {
  return request.delete(`/notes/${id}`)
}
