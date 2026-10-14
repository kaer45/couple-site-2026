package com.couple.note;

import com.couple.common.PageResult;
import com.couple.common.Result;
import com.couple.config.CoupleUserPrincipal;
import com.couple.note.dto.NoteDetailVO;
import com.couple.note.dto.NoteUpdateRequest;
import com.couple.note.dto.NoteVO;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 学习笔记控制器
 *
 * 隐私隔离：user_id 一律取 JWT 的 principal.id()，前端永不传 userId
 * 与其他"需已绑定"模块不同，本模块**不要求已绑定情侣**（纯个人数据）
 */
@RestController
@RequestMapping("/api/notes")
@RequiredArgsConstructor
public class NoteController {

    private final NoteService noteService;

    /** 分页列表：?current=1&size=10&keyword=可选 */
    @GetMapping
    public Result<PageResult<NoteVO>> page(@AuthenticationPrincipal CoupleUserPrincipal principal,
                                           @RequestParam(defaultValue = "1") long current,
                                           @RequestParam(defaultValue = "10") long size,
                                           @RequestParam(required = false) String keyword) {
        return Result.ok(noteService.page(principal.id(), current, size, keyword));
    }

    /** 笔记详情（含抽取出的正文） */
    @GetMapping("/{id}")
    public Result<NoteDetailVO> detail(@AuthenticationPrincipal CoupleUserPrincipal principal,
                                       @PathVariable Long id) {
        return Result.ok(noteService.detail(principal.id(), id));
    }

    /**
     * 上传笔记（multipart/form-data）
     * 字段：file（.md/.markdown/.txt/.docx，≤10MB）、title（可选，默认取原文件名）
     */
    @PostMapping
    public Result<NoteVO> upload(@AuthenticationPrincipal CoupleUserPrincipal principal,
                                 @RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "title", required = false) String title) {
        return Result.ok(noteService.upload(principal.id(), file, title));
    }

    /** 修改标题：{ title } */
    @PutMapping("/{id}")
    public Result<NoteVO> update(@AuthenticationPrincipal CoupleUserPrincipal principal,
                                 @PathVariable Long id,
                                 @Valid @RequestBody NoteUpdateRequest request) {
        return Result.ok(noteService.update(principal.id(), id, request));
    }

    /**
     * 手动生成 AI 内容摘要（同步等待，后端超时约 60s）
     * 返回更新后的笔记详情；失败返回 503，且**不会写入任何摘要**
     */
    @PostMapping("/{id}/summary")
    public Result<NoteDetailVO> generateSummary(@AuthenticationPrincipal CoupleUserPrincipal principal,
                                                @PathVariable Long id) {
        return Result.ok(noteService.generateSummary(principal.id(), id));
    }

    /** 删除笔记（连同磁盘文件与向量数据） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@AuthenticationPrincipal CoupleUserPrincipal principal,
                               @PathVariable Long id) {
        noteService.delete(principal.id(), id);
        return Result.ok();
    }
}
