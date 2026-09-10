package com.couple.auth;

import com.couple.auth.dto.BindRequest;
import com.couple.auth.dto.BindResponseVO;
import com.couple.auth.dto.LoginRequest;
import com.couple.auth.dto.LoginResponse;
import com.couple.auth.dto.MeVO;
import com.couple.auth.dto.RegisterRequest;
import com.couple.auth.dto.UserVO;

/**
 * 认证服务接口
 */
public interface AuthService {

    /** 注册：生成唯一情侣码并返回 token */
    LoginResponse register(RegisterRequest request);

    /** 登录：BCrypt 校验密码 */
    LoginResponse login(LoginRequest request);

    /** 绑定情侣：创建 couple、更新双方、自动创建"在一起的那天"纪念日 */
    BindResponseVO bind(Long currentUserId, BindRequest request);

    /** 当前用户信息 */
    MeVO me(Long currentUserId);

    /** 更新头像，返回最新用户信息 */
    UserVO updateAvatar(Long userId, String avatarUrl);
}
