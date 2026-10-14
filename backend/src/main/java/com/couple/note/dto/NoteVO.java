package com.couple.note.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 笔记列表项视图对象（不含正文，列表页展示用）
 */
@Data
public class NoteVO {

    private Long id;

    private String title;

    /** MD / TXT / DOCX */
    private String format;

    private String originalName;

    private String originalUrl;

    private Long fileSize;

    /** 解析状态：0=待解析 1=成功 2=失败 */
    private Integer parseStatus;

    private String parseError;

    /**
     * 卡片摘要素材：正文前 ~300 字的**原始节选**（保留换行与 Markdown 语法，未做清洗）。
     * 由前端用 markdown-it 解析后渲染成摘要；无正文时为 null。
     */
    private String summary;

    /** AI 生成的内容摘要（存在时前端优先展示它，而非 summary 节选）；未生成为 null */
    private String summaryAi;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime summaryAiAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
