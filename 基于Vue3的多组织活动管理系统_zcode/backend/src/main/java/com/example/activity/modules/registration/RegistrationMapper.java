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

    /** S07：报名范围锁定用——活动下任意主记录（含已取消／已驳回）数量。 */
    int countAnyByActivity(@Param("activityId") Long activityId);

    /** S07：草稿删除依赖检查用——未取消且未驳回的有效报名数。 */
    int countEffective(@Param("activityId") Long activityId);

    /**
     * S07-B：成员退出联动用——该账号在「尚未进入执行」的未取消／未驳回报名。
     * 执行、复盘与归档状态尚未建模，当前以活动开始时间与活动状态（DRAFT／PENDING_APPROVAL 视为未确认）判断。
     */
    List<Registration> listBlockingForLeave(@Param("orgId") Long orgId, @Param("accountId") Long accountId);

    /** 重新申请：更新主记录状态并保留历史（不新增行）。 */
    /** 取消：支持从 PENDING 或 APPROVED 源状态取消（S03）。 */
    int cancel(
        @Param("id") Long id,
        @Param("toStatus") String toStatus,
        @Param("decidedBy") Long decidedBy,
        @Param("reason") String reason);

    int reapply(
        @Param("id") Long id,
        @Param("displayName") String displayName,
        @Param("note") String note);
}
