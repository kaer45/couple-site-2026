package com.couple.auth.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 绑定情侣响应（POST /api/auth/bind）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BindResponseVO {

    private Long coupleId;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    /** 伴侣（码主人）信息 */
    private PartnerVO partner;
}
