package com.example.activity.modules.organization;

import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrganizationInvitationMapper {

    int insert(OrganizationInvitation invitation);

    OrganizationInvitation findById(@Param("id") Long id);

    OrganizationInvitation findByRequestKey(@Param("orgId") Long orgId, @Param("requestKey") String requestKey);

    OrganizationInvitation findActiveByCodeHash(@Param("codeHash") String codeHash);

    List<OrganizationInvitation> listActiveByOrgId(@Param("orgId") Long orgId);

    /** 仅在仍有效时递增计数；返回 0 表示并发下已失效，调用方按邀请无效处理。 */
    int incrementUsedCount(
        @Param("id") Long id,
        @Param("now") OffsetDateTime now,
        @Param("maxUses") int maxUses);

    int revoke(@Param("id") Long id);
}
