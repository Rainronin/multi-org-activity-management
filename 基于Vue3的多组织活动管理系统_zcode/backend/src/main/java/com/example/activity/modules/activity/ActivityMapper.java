package com.example.activity.modules.activity;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ActivityMapper {

    int insert(Activity activity);

    Activity findById(@Param("id") Long id);

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

    /** 仅更新显式传入的字段（PATCH 语义），由 Service 组装非空字段。 */
    int updateDraft(Activity activity);

    int updateProposal(@Param("id") Long id, @Param("orgId") Long orgId, @Param("proposal") String proposal);

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

    /** S02：报名开关（独立于申请时间窗）；开关仅影响新申请，不影响既有申请审核。 */
    int updateRegistrationOpen(
        @Param("id") Long id, @Param("orgId") Long orgId, @Param("open") boolean open);
}
