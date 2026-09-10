package com.couple.anniversary.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/**
 * 新增/修改纪念日请求
 */
@Data
public class AnniversaryRequest {

    @NotBlank(message = "纪念日名称不能为空")
    private String name;

    @NotNull(message = "纪念日日期不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate date;

    /** 提前提醒天数（选填，默认 0） */
    private Integer remindDays;
}
