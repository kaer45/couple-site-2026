package com.couple.anniversary.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 纪念日提醒记录表实体（防重）
 */
@Data
@TableName("reminder_log")
public class ReminderLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 纪念日ID（关联 anniversary.id） */
    @TableField("anniversary_id")
    private Long anniversaryId;

    /** 提醒发生的日期（不是纪念日日期） */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @TableField("remind_date")
    private LocalDate remindDate;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("created_at")
    private LocalDateTime createdAt;
}