package com.hagent.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置
 * 注册分页插件与乐观锁插件
 * 乐观锁使用方式：实体类需添加 @Version 字段并对应数据库 version 列
 *
 * @author H
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 注册 MyBatis-Plus 拦截器链
     * 注意：乐观锁拦截器必须在分页拦截器之前添加
     *
     * @return MyBatis-Plus 全局拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 乐观锁插件：用于并发更新场景，实体需标注 @Version 字段
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        // 分页插件
        PaginationInnerInterceptor paginationInterceptor = new PaginationInnerInterceptor(DbType.MYSQL);
        // 设置最大分页限制，防止恶意大分页查询
        paginationInterceptor.setMaxLimit(500L);
        interceptor.addInnerInterceptor(paginationInterceptor);
        return interceptor;
    }
}
