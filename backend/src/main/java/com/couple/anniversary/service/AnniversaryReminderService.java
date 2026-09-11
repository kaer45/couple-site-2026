package com.couple.anniversary.service;

/**
 * 纪念日定时提醒任务
 */
public interface AnniversaryReminderService {

    /**
     * 扫描所有纪念日，命中 remindDays 的发提醒。
     * 供调度层调用。
     */
    void remind();
}