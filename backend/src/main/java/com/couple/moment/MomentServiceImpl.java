package com.couple.moment;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.couple.common.BusinessException;
import com.couple.common.PageResult;
import com.couple.config.CoupleUserPrincipal;
import com.couple.moment.dto.MomentRequest;
import com.couple.moment.dto.MomentVO;
import com.couple.moment.entity.Moment;
import com.couple.moment.mapper.MomentMapper;
import com.couple.user.entity.User;
import com.couple.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 动态服务实现
 */
@Service
@RequiredArgsConstructor
public class MomentServiceImpl implements MomentService {

    private final MomentMapper momentMapper;
    private final UserMapper userMapper;

    @Override
    public MomentVO publish(CoupleUserPrincipal principal, MomentRequest request) {
        Long coupleId = requireCoupleId(principal);

        Moment moment = new Moment();
        moment.setCoupleId(coupleId);
        moment.setUserId(principal.id());
        moment.setContent(request.getContent());
        moment.setImages(request.getImages());
        moment.setLocation(request.getLocation());
        momentMapper.insert(moment);

        return toVO(moment, userMapper.selectById(principal.id()));
    }

    @Override
    public PageResult<MomentVO> page(CoupleUserPrincipal principal, long current, long size, String anchorDate) {
        Long coupleId = requireCoupleId(principal);

        LambdaQueryWrapper<Moment> wrapper = new LambdaQueryWrapper<Moment>()
                .eq(Moment::getCoupleId, coupleId);
        // 快速跳转：只返回 anchorDate 当天及更早的动态（created_at <= 当天 23:59:59）
        if (StringUtils.hasText(anchorDate)) {
            try {
                LocalDate date = LocalDate.parse(anchorDate);
                wrapper.le(Moment::getCreatedAt, date.atTime(LocalTime.MAX));
            } catch (DateTimeParseException e) {
                throw new BusinessException(400, "日期格式应为 yyyy-MM-dd");
            }
        }
        wrapper.orderByDesc(Moment::getCreatedAt).orderByDesc(Moment::getId);

        IPage<Moment> page = momentMapper.selectPage(new Page<>(current, size), wrapper);

        // 批量查询发布者信息，组装 nickname/avatar（records 为空时跳过查询）
        List<Long> userIds = page.getRecords().stream().map(Moment::getUserId).distinct().toList();
        Map<Long, User> userMap = userIds.isEmpty()
                ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));

        List<MomentVO> records = page.getRecords().stream()
                .map(m -> toVO(m, userMap.get(m.getUserId())))
                .toList();
        return PageResult.of(page, records);
    }

    @Override
    public List<String> dates(CoupleUserPrincipal principal) {
        Long coupleId = requireCoupleId(principal);
        return momentMapper.selectDistinctDates(coupleId);
    }

    @Override
    public List<String> months(CoupleUserPrincipal principal) {
        Long coupleId = requireCoupleId(principal);
        return momentMapper.selectDistinctMonths(coupleId);
    }

    @Override
    public void delete(CoupleUserPrincipal principal, Long id) {
        Moment moment = momentMapper.selectById(id);
        if (moment == null) {
            throw new BusinessException(404, "动态不存在");
        }
        if (!moment.getUserId().equals(principal.id())) {
            throw new BusinessException(403, "只能删除自己发布的动态");
        }
        momentMapper.deleteById(id);
    }

    /** 组装 VO；user 可能为 null（用户已删除），昵称头像置空 */
    private MomentVO toVO(Moment moment, User user) {
        MomentVO vo = new MomentVO();
        vo.setId(moment.getId());
        vo.setUserId(moment.getUserId());
        vo.setNickname(user != null ? user.getNickname() : null);
        vo.setAvatar(user != null ? user.getAvatar() : null);
        vo.setContent(moment.getContent());
        vo.setImages(moment.getImages());
        vo.setLocation(moment.getLocation());
        vo.setCreatedAt(moment.getCreatedAt());
        return vo;
    }

    /** 校验已绑定情侣，返回 coupleId */
    private Long requireCoupleId(CoupleUserPrincipal principal) {
        if (principal.coupleId() == null) {
            throw new BusinessException(403, "请先绑定情侣");
        }
        return principal.coupleId();
    }
}
