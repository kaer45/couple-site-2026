package com.couple.anniversary.task;

import com.couple.anniversary.service.AnniversaryReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 纪念日提醒定时任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnniversaryReminderTask {

    private final AnniversaryReminderService anniversaryReminderService;

    /**
     * 每天 8:00 执行
     * cron: 秒 分 时 日 月 周
     */
    @Scheduled(cron = "0 0 8 * * ?")
    public void run() {
        try {
            anniversaryReminderService.remind();
        } catch (Exception e) {
            // 必须捕获，否则后续调度会被中断
            log.error("纪念日提醒任务执行失败", e);
        }
    }
}