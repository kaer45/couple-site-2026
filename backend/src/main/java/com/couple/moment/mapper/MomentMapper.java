package com.couple.moment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.couple.moment.entity.Moment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 动态表 Mapper
 */
@Mapper
public interface MomentMapper extends BaseMapper<Moment> {

    /**
     * 动态发布日期列表（yyyy-MM-dd，倒序），用于时间轴快速跳转
     */
    @Select("SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS d FROM moment " +
            "WHERE couple_id = #{coupleId} GROUP BY d ORDER BY d DESC")
    List<String> selectDistinctDates(@Param("coupleId") Long coupleId);

    /**
     * 动态发布月份列表（yyyy-MM，倒序），用于月份级快速定位
     */
    @Select("SELECT DATE_FORMAT(created_at, '%Y-%m') AS m FROM moment " +
            "WHERE couple_id = #{coupleId} GROUP BY m ORDER BY m DESC")
    List<String> selectDistinctMonths(@Param("coupleId") Long coupleId);
}
