package com.couple.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录/注册响应：token + 用户信息 + 情侣码（仅注册时返回）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private String token;

    private UserVO user;

    /** 注册成功时返回的情侣码，登录时为空 */
    private String coupleCode;
}
