package com.couple.moment;

import com.couple.common.PageResult;
import com.couple.common.Result;
import com.couple.config.CoupleUserPrincipal;
import com.couple.moment.dto.MomentRequest;
import com.couple.moment.dto.MomentVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 动态模块控制器
 */
@RestController
@RequestMapping("/api/moments")
@RequiredArgsConstructor
public class MomentController {

    private final MomentService momentService;

    /** 2.1 发布动态 */
    @PostMapping
    public Result<MomentVO> publish(@AuthenticationPrincipal CoupleUserPrincipal principal,
                                    @Valid @RequestBody MomentRequest request) {
        return Result.ok(momentService.publish(principal, request));
    }

    /** 2.2 时间轴分页（anchorDate: yyyy-MM-dd，可空，用于快速跳到某一天） */
    @GetMapping
    public Result<PageResult<MomentVO>> page(@AuthenticationPrincipal CoupleUserPrincipal principal,
                                             @RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String anchorDate) {
        return Result.ok(momentService.page(principal, current, size, anchorDate));
    }

    /** 2.4 动态日期列表（yyyy-MM-dd 倒序，时间轴快速跳转用） */
    @GetMapping("/dates")
    public Result<List<String>> dates(@AuthenticationPrincipal CoupleUserPrincipal principal) {
        return Result.ok(momentService.dates(principal));
    }

    /** 2.5 动态月份列表（yyyy-MM 倒序，月份级快速定位用） */
    @GetMapping("/months")
    public Result<List<String>> months(@AuthenticationPrincipal CoupleUserPrincipal principal) {
        return Result.ok(momentService.months(principal));
    }

    /** 2.3 删除动态 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@AuthenticationPrincipal CoupleUserPrincipal principal,
                               @PathVariable Long id) {
        momentService.delete(principal, id);
        return Result.ok();
    }
}
