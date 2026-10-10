package com.example.activity.security;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 安全层专用只读读取：按请求核对账号状态与认证版本，
 * 不调用业务 Service，也不读取多余字段（后端架构 2.1、4.2）。
 */
@Mapper
public interface AccessMapper {

    Long selectAuthVersionById(@Param("id") Long id);

    String selectStatusById(@Param("id") Long id);
}
