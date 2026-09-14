package com.couple.todo;

import com.couple.todo.dto.TodoRequest;
import com.couple.todo.dto.TodoTodayView;
import com.couple.todo.dto.TodoVO;

/**
 * 个人待办服务（纯个人数据，按 userId 硬隔离）
 */
public interface TodoService {

    /** 今日视图：overdue / today / upcoming / completed 四组 */
    TodoTodayView todayView(Long userId);

    /** 新增待办 */
    TodoVO create(Long userId, TodoRequest request);

    /** 更新待办（全量覆盖，done 变化时联动 completed_at） */
    TodoVO update(Long userId, Long id, TodoRequest request);

    /** 删除待办 */
    void delete(Long userId, Long id);
}