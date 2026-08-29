package com.hagent.common.handler;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.hagent.common.context.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * MyBatis-Plus自动填充处理器
 * 自动填充createdAt、createdBy、updatedAt、updatedBy字段
 * <p>
 * 兼容 LocalDateTime 与 OffsetDateTime（PG TIMESTAMPTZ 需 OffsetDateTime）
 *
 * @author H
 */
@Slf4j
@Component
public class AutoFillMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        log.debug("执行insertFill自动填充");
        // 兼容两种时间类型：LocalDateTime（历史表）与 OffsetDateTime（PG TIMESTAMPTZ）
        this.strictInsertFill(metaObject, "createdAt", OffsetDateTime::now, OffsetDateTime.class);
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime::now, LocalDateTime.class);
        this.strictInsertFill(metaObject, "updatedAt", OffsetDateTime::now, OffsetDateTime.class);
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime::now, LocalDateTime.class);
        
        String username = UserContext.getUsername();
        if (username != null) {
            this.strictInsertFill(metaObject, "createdBy", () -> username, String.class);
            this.strictInsertFill(metaObject, "updatedBy", () -> username, String.class);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        log.debug("执行updateFill自动填充");
        this.strictUpdateFill(metaObject, "updatedAt", OffsetDateTime::now, OffsetDateTime.class);
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime::now, LocalDateTime.class);
        
        String username = UserContext.getUsername();
        if (username != null) {
            this.strictUpdateFill(metaObject, "updatedBy", () -> username, String.class);
        }
    }
}
