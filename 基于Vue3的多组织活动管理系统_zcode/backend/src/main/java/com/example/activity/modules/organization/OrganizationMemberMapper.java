package com.example.activity.modules.organization;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrganizationMemberMapper {

    int insert(OrganizationMember member);

    OrganizationMember findActive(@Param("orgId") Long orgId, @Param("accountId") Long accountId);

    /** 含已退出／已移除的历史关系，用于区分"从未加入"与"重复退出"。 */
    OrganizationMember findAny(@Param("orgId") Long orgId, @Param("accountId") Long accountId);

    OrganizationMember findById(@Param("id") Long id);

    String findUsernameByMemberId(@Param("id") Long id);

    List<OrganizationMember> listActiveByAccountId(@Param("accountId") Long accountId);

    int countActiveOwners(@Param("orgId") Long orgId);

    /** 仅当仍为 ACTIVE 时更新；返回 0 表示并发下已被处理。 */
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /** 重新加入：把 LEFT 历史主关系重新激活为普通成员（主关系复用，见 数据设计.md）。 */
    int reactivate(@Param("id") Long id);

    /** 成员列表分页：按用户名过滤（可空），稳定排序 joined_at, id。 */
    List<MemberView> listMemberViewsPage(
        @Param("orgId") Long orgId,
        @Param("query") String query,
        @Param("offset") long offset,
        @Param("limit") int limit);

    int countMemberViews(@Param("orgId") Long orgId, @Param("query") String query);

    /** 仅当仍为 ACTIVE 时更新角色；返回 0 表示关系已变化。 */
    int updateRole(@Param("id") Long id, @Param("role") String role);
}
