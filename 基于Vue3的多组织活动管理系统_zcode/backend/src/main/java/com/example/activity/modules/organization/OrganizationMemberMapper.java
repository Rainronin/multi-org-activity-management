package com.example.activity.modules.organization;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrganizationMemberMapper {

    int insert(OrganizationMember member);

    OrganizationMember findActive(@Param("orgId") Long orgId, @Param("accountId") Long accountId);

    List<OrganizationMember> listActiveByAccountId(@Param("accountId") Long accountId);
}
