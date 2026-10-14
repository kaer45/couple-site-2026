package com.couple.note;

import lombok.Getter;

import java.util.Locale;

/**
 * 支持的笔记格式
 * - MD / TXT：纯文本，按字符集解码即可
 * - DOCX：ZIP + XML 容器，需要解包抽取正文
 */
@Getter
public enum NoteFormat {

    MD(".md"),
    TXT(".txt"),
    DOCX(".docx");

    /** 存储用的标准扩展名 */
    private final String extension;

    NoteFormat(String extension) {
        this.extension = extension;
    }

    /**
     * 由原始文件名推断格式（含 .markdown 别名）；不支持时返回 null
     */
    public static NoteFormat fromFilename(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return null;
        }
        String ext = filename.substring(dot).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case ".md", ".markdown" -> MD;
            case ".txt" -> TXT;
            case ".docx" -> DOCX;
            default -> null;
        };
    }
}
