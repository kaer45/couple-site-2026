package com.couple.note;

import com.couple.common.PageResult;
import com.couple.note.dto.NoteDetailVO;
import com.couple.note.dto.NoteUpdateRequest;
import com.couple.note.dto.NoteVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 学习笔记服务（个人私有：所有方法都以 userId 为隔离边界）
 */
public interface NoteService {

    /**
     * 分页查询当前用户的笔记；keyword 非空时按标题/正文模糊匹配
     */
    PageResult<NoteVO> page(Long userId, long current, long size, String keyword);

    /**
     * 笔记详情（含抽取出的正文）
     */
    NoteDetailVO detail(Long userId, Long id);

    /**
     * 上传并解析一篇笔记（.md / .markdown / .txt / .docx）
     *
     * @param title 可选，为空时取原文件名（去扩展名）
     */
    NoteVO upload(Long userId, MultipartFile file, String title);

    /**
     * 修改标题（正文来自原始文件，不单独编辑）
     */
    NoteVO update(Long userId, Long id, NoteUpdateRequest request);

    /**
     * 手动触发 AI 生成内容摘要并落库。
     * 生成失败抛 503 且**不写库**（避免把降级文案当成摘要存下来）。
     */
    NoteDetailVO generateSummary(Long userId, Long id);

    /**
     * 删除笔记（同时删除磁盘文件与向量数据）
     */
    void delete(Long userId, Long id);
}
