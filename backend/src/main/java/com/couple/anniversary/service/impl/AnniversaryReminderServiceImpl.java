package com.couple.anniversary.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.couple.anniversary.entity.Anniversary;
import com.couple.anniversary.entity.ReminderLog;
import com.couple.anniversary.mapper.AnniversaryMapper;
import com.couple.anniversary.mapper.ReminderLogMapper;
import com.couple.anniversary.service.AnniversaryReminderService;
import com.couple.anniversary.service.NotificationService;
import com.couple.anniversary.util.AnniversaryDateUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 纪念日服务提醒实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnniversaryReminderServiceImpl implements AnniversaryReminderService {

    private final AnniversaryMapper anniversaryMapper;
    private final ReminderLogMapper reminderLogMapper;
    private final NotificationService notificationService;

    @Override
    public void remind() {
        LocalDate today = LocalDate.now();
        log.info("纪念日提醒任务开始，today={}", today);

        List<Anniversary> list = anniversaryMapper.selectList(null);

        if (list.isEmpty()) {
            log.info("没有需要检查的纪念日");
            return;
        }

        int sent = 0, skipped = 0;
        for (Anniversary a : list) {
            // remindDays 为 null 或 0 都视为当天提醒
            int remindDays = a.getRemindDays() == null ? 0 : a.getRemindDays();

            LocalDate next = AnniversaryDateUtil.nextOccurrence(a.getDate(), today);
            long daysLeft = ChronoUnit.DAYS.between(today, next);

            if (daysLeft != (long) remindDays) {
                continue;
            }

            // 防重：靠 UNIQUE(anniversary_id, remind_date)
            ReminderLog rl = new ReminderLog();
            rl.setAnniversaryId(a.getId());
            rl.setRemindDate(today);
            try {
                reminderLogMapper.insert(rl);
            } catch (DuplicateKeyException e) {
                log.debug("纪念日 {} 今天已提醒过，跳过", a.getId());
                skipped++;
                continue;
            }

            // 插入成功才发通知
            String content = String.format("距离「%s」还有 %d 天", a.getName(), daysLeft);
            notificationService.sendAnniversaryRemind(
                    a.getCoupleId(), "纪念日提醒", content, a.getId());
            sent++;
        }

        log.info("纪念日提醒任务结束，发送 {} 条，跳过 {} 条", sent, skipped);
    }
}