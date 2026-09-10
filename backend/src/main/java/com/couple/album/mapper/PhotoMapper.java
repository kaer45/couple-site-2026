package com.couple.album.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.couple.album.entity.Photo;
import org.apache.ibatis.annotations.Mapper;

/**
 * 照片表 Mapper
 */
@Mapper
public interface PhotoMapper extends BaseMapper<Photo> {
}
