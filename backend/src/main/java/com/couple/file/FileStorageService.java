package com.couple.file;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储服务接口
 */
public interface FileStorageService {

    /** 笔记文档大小上限（10MB）。调用方应据此**提前**拦截，避免把超大文件读进内存 */
    long MAX_DOCUMENT_SIZE = 10L * 1024 * 1024;

    /**
     * 保存文件并返回相对 URL（形如 /uploads/2025/06/01/abc123.jpg）
     */
    String store(MultipartFile file);

    /**
     * 保存笔记类文档并返回相对 URL。
     * 与 store() 的区别：按**扩展名白名单**（.md/.markdown/.txt/.docx）校验，
     * 大小上限 {@link #MAX_DOCUMENT_SIZE}，并对 .docx 校验 ZIP 魔数
     * （不信任客户端传来的 contentType）
     *
     * <p>入参是**已经读出来的字节**，而不是 MultipartFile：调用方通常需要同一份内容
     * 再做解析，而 {@code MultipartFile#transferTo} 写完会删除容器的临时上传文件，
     * 若先写后读必然失败。
     *
     * @param bytes            文件内容
     * @param originalFilename 原始文件名（用于取扩展名与页面展示）
     */
    String storeDocument(byte[] bytes, String originalFilename);

    /**
     * 删除文件（尽力而为）：按相对 URL 删除对应本地文件
     */
    default void delete(String url) {
        // 默认空实现，由具体实现覆盖
    }
}
