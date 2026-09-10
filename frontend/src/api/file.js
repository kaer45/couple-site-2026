/**
 * 文件上传模块 API（契约 §5 /api/files）
 */
import request from '@/utils/request'

// 5.1 上传单个图片（multipart/form-data，需登录）→ { url, filename, size }
export function upload(file) {
  const formData = new FormData()
  formData.append('file', file)
  // 不手动设置 Content-Type，交给 axios 自动生成带 boundary 的 multipart 头
  return request.post('/files/upload', formData)
}
