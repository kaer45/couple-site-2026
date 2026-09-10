package com.couple.anniversary.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 纪念日首页汇总（GET /api/anniversaries/summary）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnniversarySummaryVO {

    /** 在一起的天数 */
    private Long daysTogether;

    /** 最近一个未来到达的纪念日（不含"在一起的那天"），没有则 null */
    private AnniversaryNextVO nextAnniversary;

    /** 未来 180 天内到达的纪念日列表（不含 start），按 daysLeft 升序 */
    private List<AnniversaryNextVO> upcoming;
}
