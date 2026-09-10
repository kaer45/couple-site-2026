package com.couple.anniversary.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 纪念日摘要（summary 接口中 nextAnniversary / upcoming 元素）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnniversaryNextVO {

    private Long id;

    private String name;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate date;

    /** 距下一次到达的天数 */
    private Long daysLeft;
}
