package com.couple.anniversary.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.couple.anniversary.entity.ReminderLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 纪念日提醒表 Mapper
 */
@Mapper
public interface ReminderLogMapper extends BaseMapper<ReminderLog> {
}