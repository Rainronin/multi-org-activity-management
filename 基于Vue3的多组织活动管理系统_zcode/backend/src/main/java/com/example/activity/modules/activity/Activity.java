package com.example.activity.modules.activity;

import com.example.activity.modules.organization.OrganizationMember;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 活动持久化记录；金额以 DECIMAL 映射，接口层转为两位小数字符串。 */
public class Activity {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String SCOPE_INTERNAL = "INTERNAL";
    public static final String SCOPE_PUBLIC = "PUBLIC";

    /** S06：未发布（草稿／待确认）仅组织负责人与活动负责人可见；其余状态对有效成员可见。 */
    public boolean isVisibleTo(OrganizationMember member, Long accountId) {
        if (member == null) {
            return false;
        }
        if (!STATUS_DRAFT.equals(status) && !STATUS_PENDING_APPROVAL.equals(status)) {
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

    /** F02：筹备数据（任务、物资、附件）仅在已确认且不处于调整期待确认时可写。 */
    public boolean isPreparationWritable() {
        return STATUS_APPROVED.equals(status) && !isPendingReconfirmation();
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
