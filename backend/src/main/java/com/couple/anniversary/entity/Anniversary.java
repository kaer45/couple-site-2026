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
 * 纪念日表实体（表列 anniversary_date，JSON 输出字段为 date）
 */
@Data
@TableName("anniversary")
public class Anniversary {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属情侣关系 ID */
    @TableField("couple_id")
    private Long coupleId;

    /** 纪念日名称，如"第一次旅行" */
    private String name;

    /** 纪念日日期（月-日） */
    @TableField("anniversary_date")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate date;

    /** 是否"在一起"纪念日（自动创建，不可删除） */
    @TableField("is_start")
    private Boolean isStart;

    /** 提前提醒天数（预留） */
    @TableField("remind_days")
    private Integer remindDays;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
