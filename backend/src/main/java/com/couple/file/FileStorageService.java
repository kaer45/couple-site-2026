package com.couple.file;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储服务接口
 */
public interface FileStorageService {

    /**
     * 保存文件并返回相对 URL（形如 /uploads/2025/06/01/abc123.jpg）
     */
    String store(MultipartFile file);

    /**
     * 删除文件（尽力而为）：按相对 URL 删除对应本地文件
     */
    default void delete(String url) {
        // 默认空实现，由具体实现覆盖
    }
}
