package com.couple.todo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.couple.common.BusinessException;
import com.couple.todo.dto.TodoRequest;
import com.couple.todo.dto.TodoTodayView;
import com.couple.todo.dto.TodoVO;
import com.couple.todo.entity.Todo;
import com.couple.todo.mapper.TodoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 个人待办服务实现
 */
@Service
@RequiredArgsConstructor
public class TodoServiceImpl implements TodoService {

    private final TodoMapper todoMapper;

    @Override
    public TodoTodayView todayView(Long userId) {
        LocalDate today = LocalDate.now();

        // 未完成：重要置顶，同优先级按创建时间先后
        List<Todo> undone = todoMapper.selectList(new LambdaQueryWrapper<Todo>()
                .eq(Todo::getUserId, userId)
                .eq(Todo::getDone, false)
                .orderByDesc(Todo::getPriority)
                .orderByAsc(Todo::getCreatedAt));

        // 今天完成的：按完成时间倒序
        List<Todo> doneToday = todoMapper.selectList(new LambdaQueryWrapper<Todo>()
                .eq(Todo::getUserId, userId)
                .eq(Todo::getDone, true)
                .ge(Todo::getCompletedAt, today.atStartOfDay())
                .lt(Todo::getCompletedAt, today.plusDays(1).atStartOfDay())
                .orderByDesc(Todo::getCompletedAt));

        List<TodoVO> overdue = new ArrayList<>();
        List<TodoVO> todayList = new ArrayList<>();
        List<TodoVO> upcoming = new ArrayList<>();
        for (Todo t : undone) {
            TodoVO vo = toVO(t, today);
            LocalDate dueDate = t.getDueDate();
            if (dueDate != null && dueDate.isBefore(today)) {
                overdue.add(vo);
            } else if (dueDate != null && dueDate.isEqual(today)) {
                todayList.add(vo);
            } else {
                upcoming.add(vo);
            }
        }

        TodoTodayView view = new TodoTodayView();
        view.setOverdue(overdue);
        view.setToday(todayList);
        view.setUpcoming(upcoming);
        view.setCompleted(doneToday.stream().map(t -> toVO(t, today)).toList());
        return view;
    }

    @Override
    public TodoVO create(Long userId, TodoRequest request) {
        Todo todo = new Todo();
        todo.setUserId(userId);
        todo.setTitle(request.getTitle().trim());
        todo.setDone(Boolean.TRUE.equals(request.getDone()));
        todo.setPriority(Boolean.TRUE.equals(request.getPriority()));
        todo.setDueDate(request.getDueDate());
        if (Boolean.TRUE.equals(todo.getDone())) {
            todo.setCompletedAt(LocalDateTime.now());
        }
        todoMapper.insert(todo);
        return toVO(todo, LocalDate.now());
    }

    @Override
    public TodoVO update(Long userId, Long id, TodoRequest request) {
        Todo todo = requireOwnedTodo(userId, id);

        todo.setTitle(request.getTitle().trim());
        todo.setPriority(Boolean.TRUE.equals(request.getPriority()));
        todo.setDueDate(request.getDueDate());

        boolean done = Boolean.TRUE.equals(request.getDone());
        // done 从 未完成→完成：写完成时间；完成→未完成：清空
        if (done && !Boolean.TRUE.equals(todo.getDone())) {
            todo.setCompletedAt(LocalDateTime.now());
        } else if (!done) {
            todo.setCompletedAt(null);
        }
        todo.setDone(done);

        todoMapper.updateById(todo);
        return toVO(todo, LocalDate.now());
    }

    @Override
    public void delete(Long userId, Long id) {
        requireOwnedTodo(userId, id);
        todoMapper.deleteById(id);
    }

    /** 校验待办存在且属于当前用户（隐私隔离核心） */
    private Todo requireOwnedTodo(Long userId, Long id) {
        Todo todo = todoMapper.selectById(id);
        if (todo == null || !todo.getUserId().equals(userId)) {
            throw new BusinessException(404, "待办不存在");
        }
        return todo;
    }

    private TodoVO toVO(Todo todo, LocalDate today) {
        TodoVO vo = new TodoVO();
        vo.setId(todo.getId());
        vo.setTitle(todo.getTitle());
        vo.setDone(todo.getDone());
        vo.setPriority(todo.getPriority());
        vo.setDueDate(todo.getDueDate());
        vo.setOverdue(todo.getDueDate() != null && todo.getDueDate().isBefore(today));
        vo.setCompletedAt(todo.getCompletedAt());
        vo.setCreatedAt(todo.getCreatedAt());
        return vo;
    }
}