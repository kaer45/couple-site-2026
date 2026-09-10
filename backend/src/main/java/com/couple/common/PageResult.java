package com.couple.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.util.List;

/**
 * 统一分页响应：{ records, total, size, current, pages }
 * 与契约中的分页结构完全一致（不使用 IPage 原始序列化，避免多余字段）
 */
@Data
public class PageResult<T> {

    private List<T> records;

    private long total;

    private long size;

    private long current;

    private long pages;

    /** 由 MyBatis-Plus IPage 转换而来，records 为业务 VO 列表 */
    public static <T> PageResult<T> of(IPage<?> page, List<T> records) {
        PageResult<T> r = new PageResult<>();
        r.setRecords(records);
        r.setTotal(page.getTotal());
        r.setSize(page.getSize());
        r.setCurrent(page.getCurrent());
        r.setPages(page.getPages());
        return r;
    }
}
