package com.couple.auth.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;

/**
 * 绑定情侣请求
 */
@Data
public class BindRequest {

    /** 伴侣注册时得到的情侣码 */
    @NotBlank(message = "情侣码不能为空")
    private String coupleCode;

    /** 在一起的那天（选填，默认当天） */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;
}
