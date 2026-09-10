package com.couple.couple.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 情侣关系表实体
 */
@Data
@TableName("couple")
public class Couple {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发起绑定方用户 ID */
    @TableField("user_a_id")
    private Long userAId;

    /** 被绑定方用户 ID */
    @TableField("user_b_id")
    private Long userBId;

    /** 在一起的那天 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
