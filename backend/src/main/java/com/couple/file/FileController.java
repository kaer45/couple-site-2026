package com.couple.file;

import com.couple.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

/**
 * 文件上传控制器
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;

    /** 5.1 上传单张图片，返回 { url, filename, size } */
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        String url = fileStorageService.store(file);
        Map<String, Object> data = new HashMap<>();
        data.put("url", url);
        data.put("filename", url.substring(url.lastIndexOf('/') + 1));
        data.put("size", file.getSize());
        return Result.ok(data);
    }
}
