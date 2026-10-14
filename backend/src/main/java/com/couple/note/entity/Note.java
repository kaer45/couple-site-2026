package com.couple.note.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学习笔记实体（个人私有，仅本人可见）
 */
@Data
@TableName("note")
public class Note {

    /** 解析状态：成功 */
    public static final int PARSE_OK = 1;
    /** 解析状态：失败（原文件仍可下载，只是不参与搜索与 AI 检索） */
    public static final int PARSE_FAILED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属用户 ID（隐私隔离：仅从 JWT 取，前端禁止传） */
    @TableField("user_id")
    private Long userId;

    private String title;

    /** 格式：MD / TXT / DOCX */
    private String format;

    /** 原始文件名（下载时用，展示也更友好） */
    @TableField("original_name")
    private String originalName;

    /** 原始文件 URL（/uploads/yyyy/MM/dd/uuid.ext） */
    @TableField("original_url")
    private String originalUrl;

    @TableField("file_size")
    private Long fileSize;

    /** 抽取出的纯文本（供关键词搜索与 AI 向量检索） */
    @TableField("content_text")
    private String contentText;

    /** 解析状态：0=待解析 1=成功 2=失败 */
    @TableField("parse_status")
    private Integer parseStatus;

    @TableField("parse_error")
    private String parseError;

    /** AI 生成的内容摘要（手动触发；展示优先级高于正文节选） */
    @TableField("summary_ai")
    private String summaryAi;

    /** AI 摘要生成时间 */
    @TableField("summary_ai_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime summaryAiAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    /**
     * 列表页正文摘要：**不是表字段**，由查询时的 `LEFT(content_text, N) AS summary` 计算得到。
     * 列表接口刻意不加载完整正文（docx 抽取上限 20 万字），但摘要必须来自正文，
     * 因此让数据库侧截取，避免把整篇正文读进内存。
     */
    @TableField(exist = false)
    private String summary;
}
