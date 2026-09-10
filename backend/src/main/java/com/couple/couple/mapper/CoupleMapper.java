package com.couple.couple.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.couple.couple.entity.Couple;
import org.apache.ibatis.annotations.Mapper;

/**
 * 情侣关系表 Mapper
 */
@Mapper
public interface CoupleMapper extends BaseMapper<Couple> {
}
