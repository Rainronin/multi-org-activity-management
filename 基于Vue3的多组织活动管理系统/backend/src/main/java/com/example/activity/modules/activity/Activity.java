package com.example.activity.modules.activity;

import com.example.activity.modules.organization.OrganizationMember;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 活动持久化记录；金额以 DECIMAL 映射，接口层转为两位小数字符串。 */
public class Activity {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String STATUS_APPROVED = "APPROVED";
    /** P6：执行中、复盘中、已归档、已取消。 */
    public static final String STATUS_EXECUTING = "EXECUTING";
    public static final String STATUS_REVIEWING = "REVIEWING";
    public static final String STATUS_ARCHIVED = "ARCHIVED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String SCOPE_INTERNAL = "INTERNAL";
    public static final String SCOPE_PUBLIC = "PUBLIC";

    /** P6：已发布（参与者可见，公开端点同样按此判断）。归档与取消不再作为正常招募中的活动展示。 */
    public static boolean isPublished(String status) {
        return STATUS_APPROVED.equals(status) || STATUS_EXECUTING.equals(status)
            || STATUS_REVIEWING.equals(status);
    }

    /** P6：执行、复盘与取消后的遗留处理都要求活动已确认过。 */
    public boolean isStartedOrBeyond() {
        return STATUS_EXECUTING.equals(status) || STATUS_REVIEWING.equals(status)
            || STATUS_ARCHIVED.equals(status) || STATUS_CANCELLED.equals(status);
    }

    /** P6：归档后普通编辑、上传、删除与反馈修改全部关闭。 */
    public boolean isArchived() {
        return STATUS_ARCHIVED.equals(status);
    }

    /** S06：未发布内容（含从未确认的取消／归档）仅组织负责人与活动负责人可见。 */
    public boolean isVisibleTo(OrganizationMember member, Long accountId) {
        if (member == null) {
            return false;
        }
        boolean unpublished = STATUS_DRAFT.equals(status) || STATUS_PENDING_APPROVAL.equals(status)
            || (confirmedAt == null && (STATUS_CANCELLED.equals(status) || STATUS_ARCHIVED.equals(status)));
        if (!unpublished) {
            return true;
        }
        return OrganizationMember.ROLE_OWNER.equals(member.getRole())
            || member.getId().equals(ownerMemberId);
    }

    /** 报名开关：已确认且未开始时才允许变更（P2.3 重新确认后默认关闭）。 */
    public boolean isBeforeStart() {
        return startTime == null || OffsetDateTime.now().isBefore(startTime);
    }

    /**
     * S07／F02 调整期待确认：曾通过立项（confirmed_at 非空）后又回到草稿，
     * 或撤回后重新提交处于待确认。该期间任务、物资与附件都只读，活动也不能按“从未确认的初始草稿”删除。
     */
    public boolean isPendingReconfirmation() {
        return confirmedAt != null
            && (STATUS_DRAFT.equals(status) || STATUS_PENDING_APPROVAL.equals(status));
    }

    /**
     * F02／P6：筹备与执行期数据（任务、物资、附件）可写——已确认、执行中、复盘中都允许，
     * 归档后关闭；调整期待确认期间只读。
     */
    public boolean isPreparationWritable() {
        return (STATUS_APPROVED.equals(status) || STATUS_EXECUTING.equals(status)
            || STATUS_REVIEWING.equals(status)) && !isPendingReconfirmation();
    }

    /** P5／P6：费用可在筹备、执行、复盘期登记；活动取消后仍允许处理遗留费用（计划书 6.3）。 */
    public boolean isExpenseWritable() {
        return isPreparationWritable() || STATUS_CANCELLED.equals(status);
    }

    /** P6：执行记录可在执行、复盘与取消遗留处理期间追加，归档后只读。 */
    public boolean isExecutionRecordable() {
        return STATUS_EXECUTING.equals(status) || STATUS_REVIEWING.equals(status)
            || STATUS_CANCELLED.equals(status);
    }

    /**
     * P6.3：反馈窗口——首次进入复盘后开放；首次归档后永久关闭，重新打开不恢复
     * （以最近一次归档时间是否已存在判断，计划书 6.5）。
     */
    public boolean isFeedbackOpen() {
        return STATUS_REVIEWING.equals(status) && archivedAt == null;
    }


    private Long id;
    private Long orgId;
    private String name;
    private String description;
    private Long ownerMemberId;
    private String status;
    private OffsetDateTime startTime;
    private OffsetDateTime endTime;
    private String location;
    private BigDecimal budget;
    private String registrationScope;
    private Integer capacity;
    private OffsetDateTime registrationStart;
    private OffsetDateTime registrationEnd;
    /** S02：独立报名开关，新建活动默认关闭；仅报名申请校验，审核既有申请不受影响。 */
    private boolean registrationOpen;
    private String proposal;
    private String reviewNote;
    private Long submittedBy;
    private Long confirmedBy;
    private String confirmType;
    /** S07：最近一次立项确认时间；撤回后仍保留，用于识别调整期待确认状态。 */
    private OffsetDateTime confirmedAt;

    // ---------- P6：执行、取消、归档与总结 ----------
    /** 明确启动执行的时刻（不根据报名开关或时间自动跳转）。 */
    private OffsetDateTime startedAt;
    /** 负责人确认执行结束、进入复盘的时刻。 */
    private OffsetDateTime endedAt;
    private OffsetDateTime archivedAt;
    private OffsetDateTime cancelledAt;
    private String cancelReason;
    private String reopenReason;
    private int reopenCount;
    /** 执行开始时的批准报名快照摘要（保留反馈资格依据，不随之后报名变化而漂移）。 */
    private String executionSnapshotHash;
    /** 人工总结正文（可含改进项：处理人、结果或后续安排）。 */
    private String summary;
    private OffsetDateTime summaryConfirmedAt;
    private Long summaryConfirmedBy;
    /** 总结确认时的来源摘要；来源变化后与现值不一致即需重新核对。 */
    private String summarySourceHash;

    private Long createdBy;
    /** S08：编辑冲突用整数版本；每次成功写入 +1。 */
    private Long version;
    /** S08：创建防重的 Idempotency-Key（按组织唯一），仅创建时写入。 */
    private String requestKey;
    /** F06：创建请求的不可变指纹（规范化业务字段的 SHA-256），仅创建时写入，重放只与此比较。 */
    private String requestFingerprint;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrgId() {
        return orgId;
    }

    public void setOrgId(Long orgId) {
        this.orgId = orgId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getOwnerMemberId() {
        return ownerMemberId;
    }

    public void setOwnerMemberId(Long ownerMemberId) {
        this.ownerMemberId = ownerMemberId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(OffsetDateTime startTime) {
        this.startTime = startTime;
    }

    public OffsetDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(OffsetDateTime endTime) {
        this.endTime = endTime;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public BigDecimal getBudget() {
        return budget;
    }

    public void setBudget(BigDecimal budget) {
        this.budget = budget;
    }

    public String getRegistrationScope() {
        return registrationScope;
    }

    public void setRegistrationScope(String registrationScope) {
        this.registrationScope = registrationScope;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public OffsetDateTime getRegistrationStart() {
        return registrationStart;
    }

    public void setRegistrationStart(OffsetDateTime registrationStart) {
        this.registrationStart = registrationStart;
    }

    public OffsetDateTime getRegistrationEnd() {
        return registrationEnd;
    }

    public void setRegistrationEnd(OffsetDateTime registrationEnd) {
        this.registrationEnd = registrationEnd;
    }

    public boolean isRegistrationOpen() {
        return registrationOpen;
    }

    public void setRegistrationOpen(boolean registrationOpen) {
        this.registrationOpen = registrationOpen;
    }

    public String getProposal() {
        return proposal;
    }

    public void setProposal(String proposal) {
        this.proposal = proposal;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public void setReviewNote(String reviewNote) {
        this.reviewNote = reviewNote;
    }

    public Long getSubmittedBy() {
        return submittedBy;
    }

    public void setSubmittedBy(Long submittedBy) {
        this.submittedBy = submittedBy;
    }

    public Long getConfirmedBy() {
        return confirmedBy;
    }

    public void setConfirmedBy(Long confirmedBy) {
        this.confirmedBy = confirmedBy;
    }

    public String getConfirmType() {
        return confirmType;
    }

    public void setConfirmType(String confirmType) {
        this.confirmType = confirmType;
    }

    public OffsetDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(OffsetDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public OffsetDateTime getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(OffsetDateTime endedAt) {
        this.endedAt = endedAt;
    }

    public OffsetDateTime getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(OffsetDateTime archivedAt) {
        this.archivedAt = archivedAt;
    }

    public OffsetDateTime getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(OffsetDateTime cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }

    public String getReopenReason() {
        return reopenReason;
    }

    public void setReopenReason(String reopenReason) {
        this.reopenReason = reopenReason;
    }

    public int getReopenCount() {
        return reopenCount;
    }

    public void setReopenCount(int reopenCount) {
        this.reopenCount = reopenCount;
    }

    public String getExecutionSnapshotHash() {
        return executionSnapshotHash;
    }

    public void setExecutionSnapshotHash(String executionSnapshotHash) {
        this.executionSnapshotHash = executionSnapshotHash;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public OffsetDateTime getSummaryConfirmedAt() {
        return summaryConfirmedAt;
    }

    public void setSummaryConfirmedAt(OffsetDateTime summaryConfirmedAt) {
        this.summaryConfirmedAt = summaryConfirmedAt;
    }

    public Long getSummaryConfirmedBy() {
        return summaryConfirmedBy;
    }

    public void setSummaryConfirmedBy(Long summaryConfirmedBy) {
        this.summaryConfirmedBy = summaryConfirmedBy;
    }

    public String getSummarySourceHash() {
        return summarySourceHash;
    }

    public void setSummarySourceHash(String summarySourceHash) {
        this.summarySourceHash = summarySourceHash;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public String getRequestKey() {
        return requestKey;
    }

    public void setRequestKey(String requestKey) {
        this.requestKey = requestKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public void setRequestFingerprint(String requestFingerprint) {
        this.requestFingerprint = requestFingerprint;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
