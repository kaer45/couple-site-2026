package com.couple.todo.dto;

import lombok.Data;

import java.util.List;

/**
 * 今日视图聚合：一次拉取即可渲染四分组
 */
@Data
public class TodoTodayView {

    /** 过期未完成（dueDate 早于今天） */
    private List<TodoVO> overdue;

    /** 今天（dueDate 等于今天） */
    private List<TodoVO> today;

    /** 未来排期（dueDate 晚于今天或未排期） */
    private List<TodoVO> upcoming;

    /** 今天已完成（completed_at 在今天） */
    private List<TodoVO> completed;
}