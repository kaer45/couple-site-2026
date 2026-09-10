package com.couple.auth.dto;

import com.couple.user.entity.User;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对外暴露的用户信息（不包含密码）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserVO {

    private Long id;

    private String username;

    private String nickname;

    private String avatar;

    private Long coupleId;

    /** 是否已绑定情侣 */
    private Boolean bound;

    public static UserVO from(com.couple.user.entity.User user) {
        return new UserVO(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getAvatar(),
                user.getCoupleId(),
                user.getCoupleId() != null
        );
    }
}
