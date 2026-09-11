package com.couple.anniversary.service;

import com.couple.anniversary.dto.AnniversaryRequest;
import com.couple.anniversary.dto.AnniversarySummaryVO;
import com.couple.anniversary.entity.Anniversary;

import java.util.List;

/**
 * 纪念日服务接口
 */
public interface AnniversaryService {

    /** 首页汇总 */
    AnniversarySummaryVO summary(Long coupleId);

    /** 纪念日列表 */
    List<Anniversary> list(Long coupleId);

    /** 新增纪念日 */
    Anniversary create(Long coupleId, AnniversaryRequest request);

    /** 修改纪念日 */
    Anniversary update(Long coupleId, Long id, AnniversaryRequest request);

    /** 删除纪念日（is_start=1 不可删） */
    void delete(Long coupleId, Long id);
}
