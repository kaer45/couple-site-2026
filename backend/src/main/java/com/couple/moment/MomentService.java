package com.couple.moment;

import com.couple.common.PageResult;
import com.couple.config.CoupleUserPrincipal;
import com.couple.moment.dto.MomentRequest;
import com.couple.moment.dto.MomentVO;

import java.util.List;

/**
 * 动态服务接口
 */
public interface MomentService {

    /** 发布动态（需已绑定情侣） */
    MomentVO publish(CoupleUserPrincipal principal, MomentRequest request);

    /**
     * 时间轴分页（本情侣关系内，created_at 倒序）
     *
     * @param anchorDate 快速跳转锚点日期（yyyy-MM-dd，可空）；
     *                   非空时只返回该日期当天及更早的动态，用于"跳到某一天"
     */
    PageResult<MomentVO> page(CoupleUserPrincipal principal, long current, long size, String anchorDate);

    /** 动态发布日期列表（yyyy-MM-dd 倒序），用于时间轴快速跳转 */
    List<String> dates(CoupleUserPrincipal principal);

    /** 动态发布月份列表（yyyy-MM 倒序），用于月份级快速定位 */
    List<String> months(CoupleUserPrincipal principal);

    /** 删除动态（只能删自己发布的） */
    void delete(CoupleUserPrincipal principal, Long id);
}
