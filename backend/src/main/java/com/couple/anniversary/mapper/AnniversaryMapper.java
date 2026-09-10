package com.couple.anniversary.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.couple.anniversary.entity.Anniversary;
import org.apache.ibatis.annotations.Mapper;

/**
 * 纪念日表 Mapper
 */
@Mapper
public interface AnniversaryMapper extends BaseMapper<Anniversary> {
}
