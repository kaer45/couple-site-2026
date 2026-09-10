package com.couple.anniversary;

import com.couple.anniversary.dto.AnniversaryRequest;
import com.couple.anniversary.dto.AnniversarySummaryVO;
import com.couple.anniversary.entity.Anniversary;
import com.couple.common.Result;
import com.couple.config.CoupleUserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 纪念日模块控制器
 */
@RestController
@RequestMapping("/api/anniversaries")
@RequiredArgsConstructor
public class AnniversaryController {

    private final AnniversaryService anniversaryService;

    /** 3.1 首页汇总 */
    @GetMapping("/summary")
    public Result<AnniversarySummaryVO> summary(@AuthenticationPrincipal CoupleUserPrincipal principal) {
        return Result.ok(anniversaryService.summary(principal.coupleId()));
    }

    /** 3.2 纪念日列表 */
    @GetMapping
    public Result<List<Anniversary>> list(@AuthenticationPrincipal CoupleUserPrincipal principal) {
        return Result.ok(anniversaryService.list(principal.coupleId()));
    }

    /** 3.3 新增纪念日 */
    @PostMapping
    public Result<Anniversary> create(@AuthenticationPrincipal CoupleUserPrincipal principal,
                                      @Valid @RequestBody AnniversaryRequest request) {
        return Result.ok(anniversaryService.create(principal.coupleId(), request));
    }

    /** 3.4 修改纪念日 */
    @PutMapping("/{id}")
    public Result<Anniversary> update(@AuthenticationPrincipal CoupleUserPrincipal principal,
                                      @PathVariable Long id,
                                      @Valid @RequestBody AnniversaryRequest request) {
        return Result.ok(anniversaryService.update(principal.coupleId(), id, request));
    }

    /** 3.5 删除纪念日 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@AuthenticationPrincipal CoupleUserPrincipal principal,
                               @PathVariable Long id) {
        anniversaryService.delete(principal.coupleId(), id);
        return Result.ok();
    }
}
