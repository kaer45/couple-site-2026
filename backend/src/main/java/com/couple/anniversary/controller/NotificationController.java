package com.couple.anniversary.controller;

import com.couple.anniversary.dto.NotificationVO;
import com.couple.anniversary.service.NotificationService;
import com.couple.common.PageResult;
import com.couple.common.Result;
import com.couple.config.CoupleUserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知模块控制器：列表 / 未读数 / 标记已读 / 全部已读
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /** 分页拉取当前用户通知（时间倒序） */
    @GetMapping
    public Result<PageResult<NotificationVO>> page(@AuthenticationPrincipal CoupleUserPrincipal principal,
                                                   @RequestParam(defaultValue = "1") long current,
                                                   @RequestParam(defaultValue = "10") long size) {
        return Result.ok(notificationService.page(principal.id(), current, size));
    }

    /** 未读数（红点） */
    @GetMapping("/unread-count")
    public Result<Long> unreadCount(@AuthenticationPrincipal CoupleUserPrincipal principal) {
        return Result.ok(notificationService.unreadCount(principal.id()));
    }

    /** 标记某条已读 */
    @PutMapping("/{id}/read")
    public Result<Void> markRead(@AuthenticationPrincipal CoupleUserPrincipal principal,
                                 @PathVariable Long id) {
        notificationService.markRead(principal.id(), id);
        return Result.ok();
    }

    /** 全部已读 */
    @PutMapping("/read-all")
    public Result<Void> readAll(@AuthenticationPrincipal CoupleUserPrincipal principal) {
        notificationService.readAll(principal.id());
        return Result.ok();
    }
}