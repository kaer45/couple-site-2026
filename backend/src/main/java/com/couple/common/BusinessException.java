package com.couple.common;

import lombok.Getter;

/**
 * 业务异常：携带 HTTP 状态码（与 body.code 一致）与提示信息
 */
@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
