package com.couple.note.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 笔记更新请求：正文来自原始文件，因此只允许改标题
 */
@Data
public class NoteUpdateRequest {

    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题不能超过 200 字")
    private String title;
}
