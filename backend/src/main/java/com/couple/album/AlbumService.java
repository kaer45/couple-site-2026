package com.couple.album;

import com.couple.album.dto.AlbumDetailVO;
import com.couple.album.dto.AlbumPhotoRequest;
import com.couple.album.dto.AlbumRequest;
import com.couple.album.dto.AlbumVO;
import com.couple.album.dto.PhotoVO;

import java.util.List;

/**
 * 相册服务接口
 */
public interface AlbumService {

    /** 相册列表（含照片数与封面） */
    List<AlbumVO> list(Long coupleId);

    /** 创建相册 */
    AlbumVO create(Long coupleId, AlbumRequest request);

    /** 相册详情（含照片列表） */
    AlbumDetailVO detail(Long coupleId, Long albumId);

    /** 上传照片（JSON 请求体：{ urls, description }，记录 uploader=当前用户） */
    List<PhotoVO> uploadPhotos(Long coupleId, Long albumId, Long userId, AlbumPhotoRequest request);

    /** 删除照片（只能删自己上传的，封面自动回退） */
    void deletePhoto(Long coupleId, Long albumId, Long photoId, Long userId);

    /** 删除相册（级联删除照片与本地文件） */
    void deleteAlbum(Long coupleId, Long albumId);
}
