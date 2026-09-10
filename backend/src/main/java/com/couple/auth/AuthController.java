package com.couple.auth;

import com.couple.auth.dto.AvatarRequest;
import com.couple.auth.dto.BindRequest;
import com.couple.auth.dto.BindResponseVO;
import com.couple.auth.dto.LoginRequest;
import com.couple.auth.dto.LoginResponse;
import com.couple.auth.dto.MeVO;
import com.couple.auth.dto.RegisterRequest;
import com.couple.auth.dto.UserVO;
import com.couple.common.Result;
import com.couple.config.CoupleUserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证模块控制器
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 1.1 注册（公开） */
    @PostMapping("/register")
    public Result<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok(authService.register(request));
    }

    /** 1.2 登录（公开） */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    /** 1.3 绑定情侣（需登录） */
    @PostMapping("/bind")
    public Result<BindResponseVO> bind(@AuthenticationPrincipal CoupleUserPrincipal principal,
                                       @Valid @RequestBody BindRequest request) {
        return Result.ok(authService.bind(principal.id(), request));
    }

    /** 1.4 当前用户信息（需登录） */
    @GetMapping("/me")
    public Result<MeVO> me(@AuthenticationPrincipal CoupleUserPrincipal principal) {
        return Result.ok(authService.me(principal.id()));
    }

    /** 1.5 更新头像（需登录） */
    @PutMapping("/avatar")
    public Result<UserVO> updateAvatar(@AuthenticationPrincipal CoupleUserPrincipal principal,
                                       @Valid @RequestBody AvatarRequest request) {
        return Result.ok(authService.updateAvatar(principal.id(), request.getAvatarUrl()));
    }
}
