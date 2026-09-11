package com.couple.anniversary.service;

import com.couple.anniversary.dto.NotificationVO;
import com.couple.common.PageResult;

/**
 * 通知服务：发送 + 查询消费（列表/未读/已读）
 */
public interface NotificationService {

    /** 通知类型常量 */
    String TYPE_ANNIVERSARY_REMIND = "ANNIVERSARY_REMIND";

    /**
     * 给情侣双方各发一条站内信
     *
     * @param coupleId  情侣关系ID
     * @param title     标题
     * @param content   内容
     * @param relatedId 关联业务ID（纪念日ID，可空）
     */
    void sendAnniversaryRemind(Long coupleId, String title, String content, Long relatedId);

    /**
     * 分页查询当前用户的通知（时间倒序）
     *
     * @param userId  当前用户ID
     * @param current 页码，从1开始
     * @param size    每页条数
     */
    PageResult<NotificationVO> page(Long userId, long current, long size);

    /**
     * 当前用户未读通知数
     */
    long unreadCount(Long userId);

    /**
     * 将某条通知标记已读（仅限本人；不存在或非本人抛 404）
     *
     * @param userId 当前用户ID
     * @param id     通知ID
     */
    void markRead(Long userId, Long id);

    /**
     * 将当前用户全部未读通知标记已读，返回处理条数
     */
    int readAll(Long userId);
}