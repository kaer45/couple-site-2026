package com.couple.common;

import lombok.Data;

/**
 * 统一响应体：{ code, message, data }
 * code: 0=成功 400=参数错误 401=未登录 403=无权限 500=服务器错误
 */
@Data
public class Result<T> {

    private Integer code;

    private String message;

    private T data;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.setCode(0);
        r.setMessage("success");
        r.setData(data);
        return r;
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> error(Integer code, String message) {
        Result<T> r = new Result<>();
        r.setCode(code);
        r.setMessage(message);
        r.setData(null);
        return r;
    }
}
