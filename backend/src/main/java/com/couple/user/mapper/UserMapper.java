package com.couple.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.couple.user.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表 Mapper
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
