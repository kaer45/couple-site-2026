package com.couple.config;

/**
 * 认证主体：登录用户信息（存入 SecurityContext 的 principal）
 *
 * @param id       用户 ID
 * @param username 登录名
 * @param coupleId 绑定关系 ID（未绑定为 null）
 */
public record CoupleUserPrincipal(Long id, String username, Long coupleId) {
}
