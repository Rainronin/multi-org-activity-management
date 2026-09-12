package com.example.activity.modules.organization;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrganizationMapper {

    int insert(Organization organization);

    Organization findById(@Param("id") Long id);

    int countActiveMembers(@Param("orgId") Long orgId);
}
