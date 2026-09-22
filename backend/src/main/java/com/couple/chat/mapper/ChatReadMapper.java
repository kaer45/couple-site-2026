package com.couple.chat.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 聊天已读位置 Mapper
 * 复合主键 (couple_id, user_id)，不走 MyBatis-Plus 实体，用注解 SQL 直连
 */
@Mapper
public interface ChatReadMapper {

    /** 已读位点只增不减（GREATEST 防止旧值回退） */
    @Insert("INSERT INTO chat_read (couple_id, user_id, last_read_message_id) " +
            "VALUES (#{coupleId}, #{userId}, #{messageId}) " +
            "ON DUPLICATE KEY UPDATE last_read_message_id = " +
            "GREATEST(last_read_message_id, VALUES(last_read_message_id))")
    int upsertLastRead(@Param("coupleId") Long coupleId,
                       @Param("userId") Long userId,
                       @Param("messageId") Long messageId);

    /** 查询某用户已读位点，无记录返回 null */
    @Select("SELECT last_read_message_id FROM chat_read WHERE couple_id = #{coupleId} AND user_id = #{userId}")
    Long selectLastRead(@Param("coupleId") Long coupleId, @Param("userId") Long userId);
}