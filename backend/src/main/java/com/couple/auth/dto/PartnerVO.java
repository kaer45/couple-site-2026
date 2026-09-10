package com.couple.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 伴侣信息（me/bind 接口中 partner 字段）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PartnerVO {

    private Long id;

    private String nickname;

    private String avatar;
}
