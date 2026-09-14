package com.couple.todo;

import com.couple.common.Result;
import com.couple.config.CoupleUserPrincipal;
import com.couple.todo.dto.TodoRequest;
import com.couple.todo.dto.TodoTodayView;
import com.couple.todo.dto.TodoVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人待办控制器
 * 注意：user_id 一律取 JWT 的 principal.id()，前端永不传 userId
 */
@RestController
@RequestMapping("/api/todos")
@RequiredArgsConstructor
public class TodoController {

    private final TodoService todoService;

    /** 今日视图（四分组） */
    @GetMapping("/today-view")
    public Result<TodoTodayView> todayView(@AuthenticationPrincipal CoupleUserPrincipal principal) {
        return Result.ok(todoService.todayView(principal.id()));
    }

    /** 新增待办：{ title, priority?, dueDate? } */
    @PostMapping
    public Result<TodoVO> create(@AuthenticationPrincipal CoupleUserPrincipal principal,
                                 @Valid @RequestBody TodoRequest request) {
        return Result.ok(todoService.create(principal.id(), request));
    }

    /** 更新待办（全量）：{ title, priority, dueDate, done } */
    @PutMapping("/{id}")
    public Result<TodoVO> update(@AuthenticationPrincipal CoupleUserPrincipal principal,
                                 @PathVariable Long id,
                                 @Valid @RequestBody TodoRequest request) {
        return Result.ok(todoService.update(principal.id(), id, request));
    }

    /** 删除待办 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@AuthenticationPrincipal CoupleUserPrincipal principal,
                               @PathVariable Long id) {
        todoService.delete(principal.id(), id);
        return Result.ok();
    }
}