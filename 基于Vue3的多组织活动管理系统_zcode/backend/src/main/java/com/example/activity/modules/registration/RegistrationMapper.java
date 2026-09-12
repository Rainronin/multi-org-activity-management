package com.example.activity.modules.registration;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RegistrationMapper {

    int insert(Registration registration);

    Registration findById(@Param("id") Long id);

    Registration findByActivityAndAccount(
        @Param("activityId") Long activityId, @Param("accountId") Long accountId);

    List<Registration> listByActivity(
        @Param("activityId") Long activityId,
        @Param("status") String status,
        @Param("offset") long offset,
        @Param("limit") int limit);

    long countByActivity(@Param("activityId") Long activityId, @Param("status") String status);

    List<Registration> listByAccount(@Param("accountId") Long accountId);

    /** 审核带前置状态守卫；返回 0 表示已被处理。 */
    int review(
        @Param("id") Long id,
        @Param("toStatus") String toStatus,
        @Param("decidedBy") Long decidedBy,
        @Param("reason") String reason);

    /** 名额校验：当前有效报名数（锁内调用）。 */
    int countApproved(@Param("activityId") Long activityId);

    /** 重新申请：更新主记录状态并保留历史（不新增行）。 */
    int reapply(
        @Param("id") Long id,
        @Param("displayName") String displayName,
        @Param("note") String note);
}
