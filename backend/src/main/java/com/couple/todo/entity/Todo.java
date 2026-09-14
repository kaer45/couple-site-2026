package com.couple.todo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 个人待办表实体
 */
@Data
@TableName("user_todo")
public class Todo {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 ID（隐私隔离：仅从 JWT 取，前端禁止传） */
    @TableField("user_id")
    private Long userId;

    /** 待办内容 */
    private String title;

    /** 是否完成(0=未完成,1=已完成) */
    private Boolean done;

    /** 优先级(0=普通,1=重要) */
    private Boolean priority;

    /** 归属日期（空=未排期） */
    @TableField("due_date")
    private LocalDate dueDate;

    /** 完成时间 */
    @TableField("completed_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime completedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}