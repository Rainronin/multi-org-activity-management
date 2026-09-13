package com.example.activity.modules.activity;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ActivityMapper {

    int insert(Activity activity);

    Activity findById(@Param("id") Long id);

    /** S08：创建防重——按组织与 Idempotency-Key 查已提交资源。 */
    Activity findByRequestKey(@Param("orgId") Long orgId, @Param("requestKey") String requestKey);

    /** S08 锁协议：活动行共享锁（活动下子记录写入前）。 */
    Long lockActivityShared(@Param("id") Long id);

    /** S08 锁协议：活动行排他锁（活动自身写入与跨子记录写路径）。 */
    Long lockActivity(@Param("id") Long id);

    List<Activity> listPage(
        @Param("orgId") Long orgId,
        @Param("query") String query,
        @Param("status") String status,
        @Param("ownerOnly") Long ownerOnly,
        @Param("offset") long offset,
        @Param("limit") int limit);

    long countPage(
        @Param("orgId") Long orgId,
        @Param("query") String query,
        @Param("status") String status,
        @Param("ownerOnly") Long ownerOnly);

    /** 仅更新显式传入的字段（PATCH 语义）；F05：必须携带期望版本，不做无版本覆盖。 */
    int updateDraft(Activity activity);

    /** F05：方案保存以期望版本为前置条件；返回 0 表示版本过期或状态已变化。 */
    int updateProposal(
        @Param("id") Long id,
        @Param("orgId") Long orgId,
        @Param("proposal") String proposal,
        @Param("version") Long version);

    /** 带状态前置条件的流转更新；返回 0 表示当前状态不允许。 */
    int transitionStatus(
        @Param("id") Long id,
        @Param("orgId") Long orgId,
        @Param("fromStatus") String fromStatus,
        @Param("toStatus") String toStatus);

    int updateReview(
        @Param("id") Long id,
        @Param("orgId") Long orgId,
        @Param("reviewNote") String reviewNote,
        @Param("submittedBy") Long submittedBy,
        @Param("confirmedBy") Long confirmedBy,
        @Param("confirmType") String confirmType);

    int deleteDraft(@Param("id") Long id, @Param("orgId") Long orgId);

    /** S07：立项确认时记录确认时间；撤回草稿保留该值以识别调整期待确认。 */
    int markConfirmed(@Param("id") Long id, @Param("orgId") Long orgId);

    /** S07-B：退出／移除前的活动职责清点（该成员仍负责的未归档活动数）。 */
    int countOwnedByMember(@Param("orgId") Long orgId, @Param("memberId") Long memberId);

    /** S11：公开浏览只暴露已确认活动，按开始时间排序。 */
    List<Activity> listPublicApproved(@Param("offset") long offset, @Param("limit") int limit);

    long countPublicApproved();

    /** S02：报名开关（独立于申请时间窗）；开关仅影响新申请，不影响既有申请审核。 */
    int updateRegistrationOpen(
        @Param("id") Long id, @Param("orgId") Long orgId, @Param("open") boolean open);
}
