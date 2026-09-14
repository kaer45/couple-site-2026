package com.couple.todo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 待办请求体（新增/更新共用）
 * 约定：PUT 为全量覆盖，字段为 null 即置空（done 缺省按 false 处理）
 */
@Data
public class TodoRequest {

    @NotBlank(message = "待办内容不能为空")
    @Size(max = 200, message = "待办内容不能超过 200 字")
    private String title;

    /** 优先级(0=普通,1=重要)，默认 0 */
    private Boolean priority;

    /** 归属日期，null 表示未排期 */
    private LocalDate dueDate;

    /** 是否完成，缺省 false */
    private Boolean done;
}