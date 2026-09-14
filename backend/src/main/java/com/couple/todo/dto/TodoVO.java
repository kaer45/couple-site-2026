package com.couple.todo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 待办视图对象
 */
@Data
public class TodoVO {

    private Long id;

    private String title;

    private Boolean done;

    private Boolean priority;

    private LocalDate dueDate;

    /** 是否已过期（dueDate 早于今天，前端表格强调展示用） */
    private Boolean overdue;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime completedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}