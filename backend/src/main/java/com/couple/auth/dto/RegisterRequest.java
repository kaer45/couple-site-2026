package com.couple.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求
 */
@Data
public class RegisterRequest {

    /** 用户名：3-20 位字母数字下划线 */
    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_]{3,20}$", message = "用户名需为3-20位字母数字下划线")
    private String username;

    /** 密码：6-32 位 */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码需为6-32位")
    private String password;

    /** 昵称：1-20 位 */
    @NotBlank(message = "昵称不能为空")
    @Size(min = 1, max = 20, message = "昵称需为1-20位")
    private String nickname;
}
