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
import java.util.UUID;

/**
 * 本地文件存储实现：
 * - 保存到 upload.dir 配置目录，按 yyyy/MM/dd 子目录 + UUID 文件名保留扩展名
 * - 校验 contentType 以 image/ 开头、大小 ≤ 10MB
 * - 返回 "/uploads/yyyy/MM/dd/uuid.jpg" 相对 URL
 */
@Service
public class LocalFileStorageServiceImpl implements FileStorageService {

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024; // 10MB

    /** contentType → 扩展名映射 */
    private static final Map<String, String> EXT_MAP = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/gif", ".gif",
            "image/webp", ".webp",
            "image/bmp", ".bmp"
    );

    @Value("${upload.dir}")
    private String uploadDir;

    @Override
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "文件不能为空");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new BusinessException(400, "仅支持上传图片文件");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(400, "图片大小不能超过10MB");
        }

        try {
            String ext = EXT_MAP.getOrDefault(contentType.toLowerCase(Locale.ROOT),
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
