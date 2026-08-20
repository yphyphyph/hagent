package com.hagent.common;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页结果类，包含列表、总数、页码、每页大小
 *
 * @param <T> 数据类型
 * @author H
 */
@Data
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 数据列表 */
    private List<T> list;

    /** 总记录数 */
    private Long total;

    /** 当前页码 */
    private Integer pageNum;

    /** 每页大小 */
    private Integer pageSize;

    /**
     * 创建分页结果
     *
     * @param list     数据列表
     * @param total    总记录数
     * @param pageNum  当前页码
     * @param pageSize 每页大小
     * @return PageResult
     */
    public static <T> PageResult<T> of(List<T> list, Long total, Integer pageNum, Integer pageSize) {
        PageResult<T> result = new PageResult<>();
        result.setList(list);
        result.setTotal(total);
        result.setPageNum(pageNum);
        result.setPageSize(pageSize);
        return result;
    }

    /**
     * 从MyBatis-Plus IPage转换
     *
     * @param page MyBatis-Plus IPage
     * @return PageResult
     */
    public static <T> PageResult<T> of(com.baomidou.mybatisplus.core.metadata.IPage<T> page) {
        return of(page.getRecords(), page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }
}
