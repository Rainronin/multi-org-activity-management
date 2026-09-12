package com.example.activity.modules.organization;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrganizationMapper {

    int insert(Organization organization);

    Organization findById(@Param("id") Long id);

    int countActiveMembers(@Param("orgId") Long orgId);

    /** 组织行排他锁：成员、负责人与邀请接受等变更在事务内先取锁（后端架构 5.1）。 */
    Long lockOrganization(@Param("orgId") Long orgId);
}
