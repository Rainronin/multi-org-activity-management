package com.example.activity.modules.expense;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 活动支出记录（P5.1）。金额以 DECIMAL(12,2) 精确保存；
 * 状态链路：DRAFT → PENDING → APPROVED／REJECTED；APPROVED 只能作废或另建更正记录。
 */
public class Expense {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_VOIDED = "VOIDED";

    /** 简单分类白名单：不做多级科目，避免把记账系统搬进来。 */
    public static final java.util.List<String> CATEGORIES = java.util.List.of(
        "MATERIAL", "VENUE", "TRANSPORT", "CATERING", "PRINTING", "OTHER");

    private Long id;
    private Long orgId;
    private Long activityId;
    private String purpose;
    private BigDecimal amount;
    private LocalDate incurredOn;
    private String category;
    private String handledNote;
    private String noReceiptNote;
    private String status;
    private Long submittedBy;
    private OffsetDateTime submittedAt;
    private Long decidedBy;
    private OffsetDateTime decidedAt;
    private String rejectReason;
    private String overBudgetNote;
    private String voidReason;
    private OffsetDateTime voidedAt;
    private Long correctsExpenseId;
    private Long version;
    private String requestKey;
    private String requestFingerprint;
    private Long createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    /** 草稿或被驳回的记录可以修改；待确认与已确认都不能直接改（P5.2）。 */
    public boolean isEditable() {
        return STATUS_DRAFT.equals(status) || STATUS_REJECTED.equals(status);
    }

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

    public Long getActivityId() {
        return activityId;
    }

    public void setActivityId(Long activityId) {
        this.activityId = activityId;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public LocalDate getIncurredOn() {
        return incurredOn;
    }

    public void setIncurredOn(LocalDate incurredOn) {
        this.incurredOn = incurredOn;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getHandledNote() {
        return handledNote;
    }

    public void setHandledNote(String handledNote) {
        this.handledNote = handledNote;
    }

    public String getNoReceiptNote() {
        return noReceiptNote;
    }

    public void setNoReceiptNote(String noReceiptNote) {
        this.noReceiptNote = noReceiptNote;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getSubmittedBy() {
        return submittedBy;
    }

    public void setSubmittedBy(Long submittedBy) {
        this.submittedBy = submittedBy;
    }

    public OffsetDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(OffsetDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    public Long getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(Long decidedBy) {
        this.decidedBy = decidedBy;
    }

    public OffsetDateTime getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(OffsetDateTime decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public String getOverBudgetNote() {
        return overBudgetNote;
    }

    public void setOverBudgetNote(String overBudgetNote) {
        this.overBudgetNote = overBudgetNote;
    }

    public String getVoidReason() {
        return voidReason;
    }

    public void setVoidReason(String voidReason) {
        this.voidReason = voidReason;
    }

    public OffsetDateTime getVoidedAt() {
        return voidedAt;
    }

    public void setVoidedAt(OffsetDateTime voidedAt) {
        this.voidedAt = voidedAt;
    }

    public Long getCorrectsExpenseId() {
        return correctsExpenseId;
    }

    public void setCorrectsExpenseId(Long correctsExpenseId) {
        this.correctsExpenseId = correctsExpenseId;
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

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
