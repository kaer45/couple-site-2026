package com.couple.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前用户信息响应（GET /api/auth/me）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MeVO {

    private UserVO user;

    /** 伴侣信息，未绑定时为 null */
    private PartnerVO partner;

    /** 情侣关系摘要，未绑定时为 null */
    private CoupleInfoVO couple;
}
