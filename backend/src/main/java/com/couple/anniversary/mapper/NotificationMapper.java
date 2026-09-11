package com.couple.anniversary.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.couple.anniversary.entity.Notification;
import org.apache.ibatis.annotations.Mapper;

/**
 * 通知表 Mapper
 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
}