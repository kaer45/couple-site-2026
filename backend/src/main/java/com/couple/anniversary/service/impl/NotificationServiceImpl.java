package com.couple.anniversary.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.couple.anniversary.dto.NotificationVO;
import com.couple.anniversary.entity.Notification;
import com.couple.anniversary.mapper.NotificationMapper;
import com.couple.anniversary.service.NotificationService;
import com.couple.common.BusinessException;
import com.couple.common.PageResult;
import com.couple.user.entity.User;
import com.couple.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 纪念日发送通知提醒实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationMapper notificationMapper;
    private final UserMapper userMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void sendAnniversaryRemind(Long coupleId, String title, String content, Long relatedId) {
        List<User> users = userMapper.selectList(
                new LambdaQueryWrapper<User>().eq(User::getCoupleId, coupleId));

        if (users.isEmpty()) {
            log.warn("coupleId={} 下没有用户，跳过通知", coupleId);
            return;
        }

        for (User u : users) {
            Notification n = new Notification();
            n.setUserId(u.getId());
            n.setCoupleId(coupleId);
            n.setType(TYPE_ANNIVERSARY_REMIND);
            n.setTitle(title);
            n.setContent(content);
            n.setRelatedId(relatedId);
            n.setIsRead(false);
            notificationMapper.insert(n);
        }

        log.info("站内信发送完成 coupleId={}, 接收人={}人", coupleId, users.size());
    }

    @Override
    public PageResult<NotificationVO> page(Long userId, long current, long size) {
        Page<Notification> page = new Page<>(current, size);
        Page<Notification> result = notificationMapper.selectPage(page,
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUserId, userId)
                        .orderByDesc(Notification::getCreatedAt));
        return PageResult.of(result, result.getRecords().stream().map(NotificationVO::of).toList());
    }

    @Override
    public long unreadCount(Long userId) {
        return notificationMapper.selectCount(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUserId, userId)
                        .eq(Notification::getIsRead, false));
    }

    @Override
    public void markRead(Long userId, Long id) {
        int rows = notificationMapper.update(null,
                new LambdaUpdateWrapper<Notification>()
                        .eq(Notification::getId, id)
                        .eq(Notification::getUserId, userId)
                        .set(Notification::getIsRead, true));
        if (rows == 0) {
            throw new BusinessException(404, "通知不存在");
        }
    }

    @Override
    public int readAll(Long userId) {
        return notificationMapper.update(null,
                new LambdaUpdateWrapper<Notification>()
                        .eq(Notification::getUserId, userId)
                        .eq(Notification::getIsRead, false)
                        .set(Notification::getIsRead, true));
    }
}