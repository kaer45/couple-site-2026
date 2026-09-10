/**
 * 认证模块 API（契约 §1 /api/auth）
 */
import request from '@/utils/request'

// 1.1 注册（公开）→ { token, coupleCode, user }
export function register(data) {
  return request.post('/auth/register', data)
}

// 1.2 登录（公开）→ { token, user }
export function login(data) {
  return request.post('/auth/login', data)
}

// 1.3 绑定情侣（需登录、未绑定）→ { coupleId, startDate, partner }
export function bind(data) {
  return request.post('/auth/bind', data)
}

// 1.4 当前用户信息（需登录）→ { user, partner, couple }
export function me() {
  return request.get('/auth/me')
}

// 1.5 更新头像（需登录）：{ avatarUrl } → UserVO
export function updateAvatar(data) {
  return request.put('/auth/avatar', data)
}
