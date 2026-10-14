package com.couple.file;

import com.couple.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 本地文件存储实现：
 * - 保存到 upload.dir 配置目录，按 yyyy/MM/dd 子目录 + UUID 文件名保留扩展名
 * - store()：媒体文件，校验 contentType 以 image/ 或 video/ 开头、大小 ≤ 200MB（视频场景）
 * - storeDocument()：笔记文档，按扩展名白名单 + 魔数校验、大小 ≤ 10MB
 * - 返回 "/uploads/yyyy/MM/dd/uuid.jpg" 相对 URL
 */
@Service
public class LocalFileStorageServiceImpl implements FileStorageService {

    private static final long MAX_FILE_SIZE = 200L * 1024 * 1024; // 200MB
    /** 笔记文档扩展名白名单（不信任客户端的 contentType；大小上限见 FileStorageService.MAX_DOCUMENT_SIZE） */
    private static final Set<String> DOC_EXTENSIONS = Set.of(".md", ".markdown", ".txt", ".docx");

    /** contentType → 扩展名映射 */
    private static final Map<String, String> EXT_MAP = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/gif", ".gif",
            "image/webp", ".webp",
            "image/bmp", ".bmp",
            "video/mp4", ".mp4",
            "video/webm", ".webm",
            "video/quicktime", ".mov"
    );

    @Value("${upload.dir}")
    private String uploadDir;

    @Override
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "文件不能为空");
        }
        String contentType = file.getContentType();
        String lowerType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (!lowerType.startsWith("image/") && !lowerType.startsWith("video/")) {
            throw new BusinessException(400, "仅支持上传图片或视频文件");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(400, "文件大小不能超过200MB");
        }

        try {
            String ext = EXT_MAP.getOrDefault(lowerType,
                    extFromOriginalName(file.getOriginalFilename()));
            String subDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            Path dir = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(subDir);
            Files.createDirectories(dir);

            String filename = UUID.randomUUID().toString().replace("-", "") + ext;
            file.transferTo(dir.resolve(filename).toFile());
            return "/uploads/" + subDir + "/" + filename;
        } catch (IOException e) {
            throw new BusinessException(500, "文件保存失败");
        }
    }

    @Override
    public String storeDocument(byte[] bytes, String originalFilename) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(400, "文件不能为空");
        }
        String ext = docExtension(originalFilename);
        if (ext == null) {
            throw new BusinessException(400, "仅支持 .md / .markdown / .txt / .docx 格式的笔记文件");
        }
        if (bytes.length > MAX_DOCUMENT_SIZE) {
            throw new BusinessException(400, "笔记文件不能超过10MB");
        }
        // .docx 本质是 ZIP 容器：校验魔数，避免把别的文件改个后缀传上来
        if (".docx".equals(ext) && !isZip(bytes)) {
            throw new BusinessException(400, "该文件不是有效的 .docx 文档");
        }

        try {
            String subDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            Path dir = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(subDir);
            Files.createDirectories(dir);

            String filename = UUID.randomUUID().toString().replace("-", "") + ext;
            Files.write(dir.resolve(filename), bytes);
            return "/uploads/" + subDir + "/" + filename;
        } catch (IOException e) {
            throw new BusinessException(500, "文件保存失败");
        }
    }

    /** 从原始文件名取文档扩展名；不在白名单内返回 null（不做 .jpg 兜底，避免误收任意文件） */
    private String docExtension(String originalName) {
        if (originalName == null) {
            return null;
        }
        int dot = originalName.lastIndexOf('.');
        if (dot < 0 || dot == originalName.length() - 1) {
            return null;
        }
        String ext = originalName.substring(dot).toLowerCase(Locale.ROOT);
        return DOC_EXTENSIONS.contains(ext) ? ext : null;
    }

    /** 判断是否为 ZIP 魔数（PK\x03\x04）；docx 等 OOXML 都是 ZIP 容器 */
    private boolean isZip(byte[] bytes) {
        return bytes.length >= 4
                && bytes[0] == 0x50 && bytes[1] == 0x4B
                && bytes[2] == 0x03 && bytes[3] == 0x04;
    }

    @Override
    public void delete(String url) {
        if (url == null || !url.startsWith("/uploads/")) {
            return;
        }
        try {
            String relative = url.substring("/uploads/".length());
            Path path = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(relative);
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 尽力而为：删除失败不阻断业务
        }
    }

    /** 从原始文件名提取扩展名，失败则回退 .jpg */
    private String extFromOriginalName(String originalName) {
        if (originalName != null) {
            int dot = originalName.lastIndexOf('.');
            if (dot >= 0 && dot < originalName.length() - 1) {
                String ext = originalName.substring(dot).toLowerCase(Locale.ROOT);
                if (ext.length() <= 5 && ext.matches("\\.[a-z0-9]+")) {
                    return ext;
                }
            }
        }
        return ".jpg";
    }
}
