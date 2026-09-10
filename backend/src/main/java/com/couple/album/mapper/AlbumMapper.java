package com.couple.album.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.couple.album.entity.Album;
import org.apache.ibatis.annotations.Mapper;

/**
 * 相册表 Mapper
 */
@Mapper
public interface AlbumMapper extends BaseMapper<Album> {
}
