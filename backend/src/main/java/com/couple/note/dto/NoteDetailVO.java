package com.couple.note.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 笔记详情视图对象（含抽取出的完整正文）
 */
@Data
public class NoteDetailVO {

    private Long id;

    private String title;

    private String format;

    private String originalName;

    private String originalUrl;

    private Long fileSize;

    private Integer parseStatus;

    private String parseError;

    /** 抽取出的纯文本正文（解析失败时为 null） */
    private String content;

    /** AI 生成的内容摘要；未生成为 null */
    private String summaryAi;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime summaryAiAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
