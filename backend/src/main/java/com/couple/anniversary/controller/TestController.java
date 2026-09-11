package com.couple.anniversary.controller;

import com.couple.anniversary.service.AnniversaryReminderService;
import com.couple.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试用接口（仅 dev 环境暴露）
 */
@RestController
@RequestMapping("/api/test")
@Profile("dev")
@RequiredArgsConstructor
public class TestController {

    private final AnniversaryReminderService anniversaryReminderService;

    @PostMapping("/remind/trigger")
    public Result<Void> trigger() {
        anniversaryReminderService.remind();
        return Result.ok();
    }
}