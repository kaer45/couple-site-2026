package com.couple.auth.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 情侣关系摘要（me 接口中 couple 字段）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CoupleInfoVO {

    private Long id;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    /** 在一起的天数 */
    private Long daysTogether;
}
