package com.couple.album;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.couple.album.dto.AlbumDetailVO;
import com.couple.album.dto.AlbumPhotoRequest;
import com.couple.album.dto.AlbumRequest;
import com.couple.album.dto.AlbumVO;
import com.couple.album.dto.PhotoVO;
import com.couple.album.entity.Album;
import com.couple.album.entity.Photo;
import com.couple.album.mapper.AlbumMapper;
import com.couple.album.mapper.PhotoMapper;
import com.couple.common.BusinessException;
import com.couple.file.FileStorageService;
import com.couple.user.entity.User;
import com.couple.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 相册服务实现
 */
@Service
@RequiredArgsConstructor
public class AlbumServiceImpl implements AlbumService {

    private final AlbumMapper albumMapper;
    private final PhotoMapper photoMapper;
    private final UserMapper userMapper;
    private final FileStorageService fileStorageService;

    @Override
    public List<AlbumVO> list(Long coupleId) {
        requireCoupleId(coupleId);
        List<Album> albums = albumMapper.selectList(new LambdaQueryWrapper<Album>()
                .eq(Album::getCoupleId, coupleId)
                .orderByDesc(Album::getCreatedAt));
        return albums.stream().map(this::toAlbumVO).toList();
    }

    @Override
    public AlbumVO create(Long coupleId, AlbumRequest request) {
        requireCoupleId(coupleId);
        Album album = new Album();
        album.setCoupleId(coupleId);
        album.setName(request.getName().trim());
        album.setDescription(request.getDescription());
        albumMapper.insert(album);

        AlbumVO vo = toAlbumVO(album);
        vo.setPhotoCount(0L);
        return vo;
    }

    @Override
    public AlbumDetailVO detail(Long coupleId, Long albumId) {
        requireCoupleId(coupleId);
        Album album = requireOwnedAlbum(coupleId, albumId);

        List<Photo> photos = photoMapper.selectList(new LambdaQueryWrapper<Photo>()
                .eq(Photo::getAlbumId, albumId)
                .orderByDesc(Photo::getCreatedAt));

        // 批量查询上传者，组装昵称（photos 为空时跳过查询）
        List<Long> userIds = photos.stream().map(Photo::getUserId).distinct().toList();
        Map<Long, User> userMap = userIds.isEmpty()
                ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));

        AlbumDetailVO vo = new AlbumDetailVO();
        vo.setId(album.getId());
        vo.setName(album.getName());
        vo.setDescription(album.getDescription());
        vo.setCoverUrl(album.getCoverUrl());
        vo.setCreatedAt(album.getCreatedAt());
        vo.setPhotos(photos.stream().map(p -> toPhotoVO(p, userMap.get(p.getUserId()))).toList());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<PhotoVO> uploadPhotos(Long coupleId, Long albumId, Long userId, AlbumPhotoRequest request) {
        requireCoupleId(coupleId);
        Album album = requireOwnedAlbum(coupleId, albumId);

        List<String> urls = request.getUrls();
        if (urls == null || urls.isEmpty()) {
            throw new BusinessException(400, "urls 不能为空");
        }

        User uploader = userMapper.selectById(userId);
        List<PhotoVO> result = new ArrayList<>();
        for (String url : urls) {
            Photo photo = new Photo();
            photo.setAlbumId(albumId);
            photo.setUserId(userId);
            photo.setUrl(url);
            photo.setDescription(request.getDescription());
            photoMapper.insert(photo);
            result.add(toPhotoVO(photo, uploader));
        }

        // 上传后更新相册封面为最新一张
        Photo latest = latestPhoto(albumId);
        album.setCoverUrl(latest != null ? latest.getUrl() : null);
        albumMapper.updateById(album);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePhoto(Long coupleId, Long albumId, Long photoId, Long userId) {
        requireCoupleId(coupleId);
        requireOwnedAlbum(coupleId, albumId);

        Photo photo = photoMapper.selectById(photoId);
        if (photo == null || !photo.getAlbumId().equals(albumId)) {
            throw new BusinessException(404, "照片不存在");
        }
        if (!photo.getUserId().equals(userId)) {
            throw new BusinessException(403, "只能删除自己上传的照片");
        }

        // 删除本地文件（尽力而为）
        fileStorageService.delete(photo.getUrl());
        photoMapper.deleteById(photoId);

        // 若删除的是封面，回退为最新一张
        Album album = albumMapper.selectById(albumId);
        if (album != null && photo.getUrl().equals(album.getCoverUrl())) {
            Photo latest = latestPhoto(albumId);
            album.setCoverUrl(latest != null ? latest.getUrl() : null);
            albumMapper.updateById(album);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAlbum(Long coupleId, Long albumId) {
        requireCoupleId(coupleId);
        Album album = requireOwnedAlbum(coupleId, albumId);

        // 先删照片（含本地文件，尽力而为），再删相册
        List<Photo> photos = photoMapper.selectList(new LambdaQueryWrapper<Photo>()
                .eq(Photo::getAlbumId, albumId));
        photos.forEach(p -> fileStorageService.delete(p.getUrl()));
        photoMapper.delete(new LambdaQueryWrapper<Photo>().eq(Photo::getAlbumId, albumId));
        albumMapper.deleteById(albumId);
    }

    /** 组装 AlbumVO（含照片数与封面） */
    private AlbumVO toAlbumVO(Album album) {
        Long photoCount = photoMapper.selectCount(new LambdaQueryWrapper<Photo>()
                .eq(Photo::getAlbumId, album.getId()));
        String coverUrl = album.getCoverUrl();
        if (coverUrl == null || coverUrl.isEmpty()) {
            Photo latest = latestPhoto(album.getId());
            coverUrl = latest != null ? latest.getUrl() : null;
        }
        AlbumVO vo = new AlbumVO();
        vo.setId(album.getId());
        vo.setName(album.getName());
        vo.setDescription(album.getDescription());
        vo.setCoverUrl(coverUrl);
        vo.setPhotoCount(photoCount);
        vo.setCreatedAt(album.getCreatedAt());
        return vo;
    }

    /** 相册最新一张照片（created_at 倒序，同秒按 id 倒序保证稳定） */
    private Photo latestPhoto(Long albumId) {
        return photoMapper.selectOne(new LambdaQueryWrapper<Photo>()
                .eq(Photo::getAlbumId, albumId)
                .orderByDesc(Photo::getCreatedAt)
                .orderByDesc(Photo::getId)
                .last("limit 1"));
    }

    private PhotoVO toPhotoVO(Photo photo, User uploader) {
        PhotoVO vo = new PhotoVO();
        vo.setId(photo.getId());
        vo.setUserId(photo.getUserId());
        vo.setUrl(photo.getUrl());
        vo.setThumbnailUrl(photo.getThumbnailUrl());
        vo.setDescription(photo.getDescription());
        vo.setUploaderNickname(uploader != null ? uploader.getNickname() : null);
        vo.setCreatedAt(photo.getCreatedAt());
        return vo;
    }

    /** 校验相册存在且属于当前情侣 */
    private Album requireOwnedAlbum(Long coupleId, Long albumId) {
        Album album = albumMapper.selectById(albumId);
        if (album == null || !album.getCoupleId().equals(coupleId)) {
            throw new BusinessException(404, "相册不存在");
        }
        return album;
    }

    /** 校验已绑定情侣 */
    private void requireCoupleId(Long coupleId) {
        if (coupleId == null) {
            throw new BusinessException(403, "请先绑定情侣");
        }
    }
}
