package com.couple.anniversary.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.couple.anniversary.dto.AnniversaryNextVO;
import com.couple.anniversary.dto.AnniversaryRequest;
import com.couple.anniversary.dto.AnniversarySummaryVO;
import com.couple.anniversary.entity.Anniversary;
import com.couple.anniversary.mapper.AnniversaryMapper;
import com.couple.anniversary.service.AnniversaryService;
import com.couple.anniversary.util.AnniversaryDateUtil;
import com.couple.common.BusinessException;
import com.couple.couple.entity.Couple;
import com.couple.couple.mapper.CoupleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/**
 * 纪念日服务实现
 */
@Service
@RequiredArgsConstructor
public class AnniversaryServiceImpl implements AnniversaryService {


    private final AnniversaryMapper anniversaryMapper;
    private final CoupleMapper coupleMapper;

    @Override
    public AnniversarySummaryVO summary(Long coupleId) {
        requireCoupleId(coupleId);
        Couple couple = coupleMapper.selectById(coupleId);
        if (couple == null) {
            throw new BusinessException(404, "情侣关系不存在");
        }

        // 在一起的天数
        long daysTogether = ChronoUnit.DAYS.between(couple.getStartDate(), LocalDate.now());

        // 计算下一次到达日期与剩余天数(包括isStart)
        List<Anniversary> list = list(coupleId);
        List<AnniversaryNextVO> withDaysLeft = list.stream()
                .map(a -> {
                    LocalDate next = AnniversaryDateUtil.nextOccurrence(a.getDate(), LocalDate.now());
                    return new AnniversaryNextVO(a.getId(), a.getName(), a.getDate(),
                            ChronoUnit.DAYS.between(LocalDate.now(), next));
                })
                .toList();

        // nextAnniversary：下一次到达日期最近的（daysLeft 最小且 >= 0）
        AnniversaryNextVO next = withDaysLeft.stream()
                .filter(v -> v.getDaysLeft() >= 0)
                .min(Comparator.comparingLong(AnniversaryNextVO::getDaysLeft))
                .orElse(null);

        // upcoming：只取最近5个，按 daysLeft 升序
        List<AnniversaryNextVO> upcoming = withDaysLeft.stream()
                .filter(v -> v.getDaysLeft() >= 0)
                .sorted(Comparator.comparingLong(AnniversaryNextVO::getDaysLeft))
                .limit(5)
                .toList();

        return new AnniversarySummaryVO(daysTogether, next, upcoming);
    }

    @Override
    public List<Anniversary> list(Long coupleId) {
        requireCoupleId(coupleId);
        return anniversaryMapper.selectList(new LambdaQueryWrapper<Anniversary>()
                .eq(Anniversary::getCoupleId, coupleId)
                .orderByDesc(Anniversary::getCreatedAt));
    }

    @Override
    public Anniversary create(Long coupleId, AnniversaryRequest request) {
        requireCoupleId(coupleId);
        Anniversary anniversary = new Anniversary();
        anniversary.setCoupleId(coupleId);
        anniversary.setName(request.getName().trim());
        anniversary.setDate(request.getDate());
        anniversary.setIsStart(false);
        anniversary.setRemindDays(request.getRemindDays() != null ? request.getRemindDays() : 0);
        anniversaryMapper.insert(anniversary);
        return anniversary;
    }

    @Override
    public Anniversary update(Long coupleId, Long id, AnniversaryRequest request) {
        requireCoupleId(coupleId);
        Anniversary anniversary = requireOwned(coupleId, id);
        anniversary.setName(request.getName().trim());
        anniversary.setDate(request.getDate());
        anniversary.setRemindDays(request.getRemindDays() != null ? request.getRemindDays() : 0);
        anniversaryMapper.updateById(anniversary);
        return anniversary;
    }

    @Override
    public void delete(Long coupleId, Long id) {
        requireCoupleId(coupleId);
        Anniversary anniversary = requireOwned(coupleId, id);
        if (Boolean.TRUE.equals(anniversary.getIsStart())) {
            throw new BusinessException(403, "在一起的那天不可删除");
        }
        anniversaryMapper.deleteById(id);
    }

    /** 查询并校验纪念日属于当前情侣 */
    private Anniversary requireOwned(Long coupleId, Long id) {
        Anniversary anniversary = anniversaryMapper.selectById(id);
        if (anniversary == null || !anniversary.getCoupleId().equals(coupleId)) {
            throw new BusinessException(404, "纪念日不存在");
        }
        return anniversary;
    }

    /** 校验已绑定情侣 */
    private void requireCoupleId(Long coupleId) {
        if (coupleId == null) {
            throw new BusinessException(403, "请先绑定情侣");
        }
    }
}
