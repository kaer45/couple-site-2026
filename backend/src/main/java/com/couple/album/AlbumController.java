package com.couple.album;

import com.couple.album.dto.AlbumDetailVO;
import com.couple.album.dto.AlbumPhotoRequest;
import com.couple.album.dto.AlbumRequest;
import com.couple.album.dto.AlbumVO;
import com.couple.album.dto.PhotoVO;
import com.couple.common.Result;
import com.couple.config.CoupleUserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 相册模块控制器
 */
@RestController
@RequestMapping("/api/albums")
@RequiredArgsConstructor
public class AlbumController {

    private final AlbumService albumService;

    /** 4.1 相册列表 */
    @GetMapping
    public Result<List<AlbumVO>> list(@AuthenticationPrincipal CoupleUserPrincipal principal) {
        return Result.ok(albumService.list(principal.coupleId()));
    }

    /** 4.2 创建相册 */
    @PostMapping
    public Result<AlbumVO> create(@AuthenticationPrincipal CoupleUserPrincipal principal,
                                  @Valid @RequestBody AlbumRequest request) {
        return Result.ok(albumService.create(principal.coupleId(), request));
    }

    /** 4.3 相册详情 */
    @GetMapping("/{id}")
    public Result<AlbumDetailVO> detail(@AuthenticationPrincipal CoupleUserPrincipal principal,
                                        @PathVariable Long id) {
        return Result.ok(albumService.detail(principal.coupleId(), id));
    }

    /** 4.4 上传照片（JSON 请求体：{ urls, description }） */
    @PostMapping("/{id}/photos")
    public Result<List<PhotoVO>> uploadPhotos(@AuthenticationPrincipal CoupleUserPrincipal principal,
                                              @PathVariable Long id,
                                              @Valid @RequestBody AlbumPhotoRequest request) {
        return Result.ok(albumService.uploadPhotos(principal.coupleId(), id, principal.id(), request));
    }

    /** 4.5 删除照片 */
    @DeleteMapping("/{id}/photos/{photoId}")
    public Result<Void> deletePhoto(@AuthenticationPrincipal CoupleUserPrincipal principal,
                                    @PathVariable Long id,
                                    @PathVariable Long photoId) {
        albumService.deletePhoto(principal.coupleId(), id, photoId, principal.id());
        return Result.ok();
    }

    /** 4.6 删除相册 */
    @DeleteMapping("/{id}")
    public Result<Void> deleteAlbum(@AuthenticationPrincipal CoupleUserPrincipal principal,
                                    @PathVariable Long id) {
        albumService.deleteAlbum(principal.coupleId(), id);
        return Result.ok();
    }
}
