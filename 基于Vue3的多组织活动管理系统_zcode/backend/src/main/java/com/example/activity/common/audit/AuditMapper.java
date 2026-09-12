package com.example.activity.common.audit;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 审计写入归 common（无业务归属能力）；查询按资源权限由各业务模块扩展。 */
@Mapper
public interface AuditMapper {

    int insert(
        @Param("actorAccountId") Long actorAccountId,
        @Param("scope") String scope,
        @Param("action") String action,
        @Param("target") String target,
        @Param("reason") String reason,
        @Param("detail") String detail);
}
