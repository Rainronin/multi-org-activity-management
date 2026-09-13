package com.example.activity.modules.expense;

import com.example.activity.security.AccountUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 活动经费端点（P5）。动作 URL 仍属于活动资源，不暴露独立经费工作台；
 * 权限与状态规则在 Service 内按锁协议判定（计划书 7.4、后端架构 5.3）。
 */
@RestController
public class ExpenseController {

    private final ExpenseService expenseService;
    private final com.example.activity.modules.attachment.AttachmentService attachmentService;
    private final long maxVoucherSizeBytes;

    public ExpenseController(
        ExpenseService expenseService,
        com.example.activity.modules.attachment.AttachmentService attachmentService,
        @org.springframework.beans.factory.annotation.Value("${app.upload.max-size-bytes:20971520}")
        long maxVoucherSizeBytes) {
        this.expenseService = expenseService;
        this.attachmentService = attachmentService;
        this.maxVoucherSizeBytes = maxVoucherSizeBytes;
    }

    public record ExpenseResponse(
        String expenseId, String activityId, String purpose, String amount, LocalDate incurredOn,
        String category, String handledNote, String noReceiptNote, String status,
        Long submittedBy, java.time.OffsetDateTime submittedAt, Long decidedBy, java.time.OffsetDateTime decidedAt,
        String rejectReason, String overBudgetNote, String voidReason, java.time.OffsetDateTime voidedAt,
        String correctsExpenseId, String voucherCount, Long version) {
    }

    public record ExpensesResponse(List<ExpenseResponse> items, int page, int pageSize, long total) {
    }

    public record ExpenseSummaryResponse(
        String budget, String pendingAmount, String approvedAmount, String balance, long total) {
    }

    public record SaveExpenseRequest(
        @NotBlank(message = "支出用途不能为空") @Size(max = 200, message = "用途不超过 200 字符") String purpose,
        @NotNull(message = "金额不能为空")
        @DecimalMin(value = "0.01", message = "金额必须大于零")
        @Digits(integer = 10, fraction = 2, message = "金额最多两位小数") BigDecimal amount,
        @NotNull(message = "请填写费用发生日期") LocalDate incurredOn,
        @NotBlank(message = "请选择费用分类")
        @Pattern(regexp = "^(MATERIAL|VENUE|TRANSPORT|CATERING|PRINTING|OTHER)$", message = "未知的费用分类")
        String category,
        @Size(max = 500, message = "经手说明不超过 500 字符") String handledNote,
        @Size(max = 500, message = "无票说明不超过 500 字符") String noReceiptNote,
        /** S08／F05：编辑与状态动作必须携带期望版本。 */
        @Min(value = 1, message = "版本号必须为正整数") Long version) {
    }

    public record VersionedRequest(
        @Min(value = 1, message = "版本号必须为正整数") Long version,
        @Size(max = 500, message = "说明不超过 500 字符") String reason,
        @Size(max = 500, message = "超支说明不超过 500 字符") String overBudgetNote) {
    }

    public record CorrectionRequest(
        @NotBlank(message = "支出用途不能为空") @Size(max = 200, message = "用途不超过 200 字符") String purpose,
        @NotNull(message = "金额不能为空")
        @DecimalMin(value = "0.01", message = "金额必须大于零")
        @Digits(integer = 10, fraction = 2, message = "金额最多两位小数") BigDecimal amount,
        @NotNull(message = "请填写费用发生日期") LocalDate incurredOn,
        @NotBlank(message = "请选择费用分类")
        @Pattern(regexp = "^(MATERIAL|VENUE|TRANSPORT|CATERING|PRINTING|OTHER)$", message = "未知的费用分类")
        String category,
        @Size(max = 500, message = "经手说明不超过 500 字符") String handledNote,
        @Size(max = 500, message = "无票说明不超过 500 字符") String noReceiptNote) {
    }

    private ExpenseResponse toResponse(ExpenseService.ExpenseItem item) {
        return new ExpenseResponse(
            String.valueOf(item.expenseId()), String.valueOf(item.activityId()), item.purpose(), item.amount(),
            item.incurredOn(), item.category(), item.handledNote(), item.noReceiptNote(), item.status(),
            item.submittedBy(), item.submittedAt(), item.decidedBy(), item.decidedAt(), item.rejectReason(),
            item.overBudgetNote(), item.voidReason(), item.voidedAt(),
            item.correctsExpenseId() == null ? null : String.valueOf(item.correctsExpenseId()),
            item.voucherCount(), item.version());
    }

    private Expense draftOf(SaveExpenseRequest request) {
        Expense draft = new Expense();
        draft.setPurpose(request.purpose().trim());
        draft.setAmount(request.amount());
        draft.setIncurredOn(request.incurredOn());
        draft.setCategory(request.category());
        draft.setHandledNote(trimToNull(request.handledNote()));
        draft.setNoReceiptNote(trimToNull(request.noReceiptNote()));
        draft.setVersion(request.version());
        return draft;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @GetMapping("/api/organizations/{orgId}/activities/{activityId}/expenses")
    public ResponseEntity<ExpensesResponse> list(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @RequestParam(name = "page", defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") int page,
        @RequestParam(name = "pageSize", defaultValue = "20") @Min(value = 1, message = "每页至少 1 条") @Max(value = 100, message = "每页最多 100 条") int pageSize,
        @RequestParam(name = "status", required = false) String status) {
        ExpenseService.ExpensePage result =
            expenseService.list(principal.getAccountId(), orgId, activityId, page, pageSize, status);
        List<ExpenseResponse> items = result.items().stream().map(this::toResponse).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ExpensesResponse(items, result.page(), result.pageSize(), result.total()));
    }

    @GetMapping("/api/organizations/{orgId}/activities/{activityId}/expenses/summary")
    public ResponseEntity<ExpenseSummaryResponse> summary(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId) {
        ExpenseService.ExpenseSummary summary =
            expenseService.summary(principal.getAccountId(), orgId, activityId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ExpenseSummaryResponse(summary.budget(), summary.pendingAmount(),
                summary.approvedAmount(), summary.balance(), summary.total()));
    }

    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/expenses")
    public ResponseEntity<ExpenseResponse> create(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody SaveExpenseRequest request) {
        ExpenseService.ExpenseItem created = expenseService.create(
            principal.getAccountId(), orgId, activityId,
            idempotencyKey == null ? null : idempotencyKey.trim(), draftOf(request));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(toResponse(created));
    }

    /** 更正登记：原记录须为已确认或已作废，新记录关联原记录并重新提交确认。 */
    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/expenses/{expenseId}/correction")
    public ResponseEntity<ExpenseResponse> createCorrection(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @PathVariable("expenseId") Long expenseId,
        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody CorrectionRequest request) {
        Expense draft = new Expense();
        draft.setPurpose(request.purpose().trim());
        draft.setAmount(request.amount());
        draft.setIncurredOn(request.incurredOn());
        draft.setCategory(request.category());
        draft.setHandledNote(trimToNull(request.handledNote()));
        draft.setNoReceiptNote(trimToNull(request.noReceiptNote()));
        ExpenseService.ExpenseItem created = expenseService.createCorrection(
            principal.getAccountId(), orgId, activityId, expenseId,
            idempotencyKey == null ? null : idempotencyKey.trim(), draft);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(toResponse(created));
    }

    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/expenses/{expenseId}/update")
    public ResponseEntity<ExpenseResponse> update(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @PathVariable("expenseId") Long expenseId,
        @Valid @RequestBody SaveExpenseRequest request) {
        ExpenseService.ExpenseItem updated = expenseService.update(
            principal.getAccountId(), orgId, activityId, expenseId, draftOf(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toResponse(updated));
    }

    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/expenses/{expenseId}/submit")
    public ResponseEntity<ExpenseResponse> submit(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @PathVariable("expenseId") Long expenseId,
        @Valid @RequestBody VersionedRequest request) {
        ExpenseService.ExpenseItem submitted = expenseService.submit(
            principal.getAccountId(), orgId, activityId, expenseId, request.version());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toResponse(submitted));
    }

    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/expenses/{expenseId}/confirm")
    public ResponseEntity<ExpenseResponse> confirm(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @PathVariable("expenseId") Long expenseId,
        @Valid @RequestBody VersionedRequest request) {
        ExpenseService.ExpenseItem confirmed = expenseService.confirm(
            principal.getAccountId(), orgId, activityId, expenseId,
            trimToNull(request.overBudgetNote()), request.version());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toResponse(confirmed));
    }

    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/expenses/{expenseId}/reject")
    public ResponseEntity<ExpenseResponse> reject(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @PathVariable("expenseId") Long expenseId,
        @Valid @RequestBody VersionedRequest request) {
        ExpenseService.ExpenseItem rejected = expenseService.reject(
            principal.getAccountId(), orgId, activityId, expenseId,
            trimToNull(request.reason()), request.version());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toResponse(rejected));
    }

    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/expenses/{expenseId}/void")
    public ResponseEntity<ExpenseResponse> voidExpense(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @PathVariable("expenseId") Long expenseId,
        @Valid @RequestBody VersionedRequest request) {
        ExpenseService.ExpenseItem voided = expenseService.voidExpense(
            principal.getAccountId(), orgId, activityId, expenseId,
            trimToNull(request.reason()), request.version());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toResponse(voided));
    }

    // ---------- 凭证附件（P5.1）：走受控附件入口，归属为具体支出 ----------

    public record VoucherResponse(
        String attachmentId, String expenseId, String originalName, String contentType,
        long sizeBytes, String uploaderUsername, java.time.OffsetDateTime createdAt) {
    }

    public record VouchersResponse(List<VoucherResponse> items) {
    }

    private VoucherResponse toVoucher(com.example.activity.modules.attachment.AttachmentService.AttachmentItem item) {
        return new VoucherResponse(String.valueOf(item.attachmentId()), item.expenseId(),
            item.originalName(), item.contentType(), item.sizeBytes(), item.uploaderUsername(), item.createdAt());
    }

    @GetMapping("/api/organizations/{orgId}/activities/{activityId}/expenses/{expenseId}/attachments")
    public ResponseEntity<VouchersResponse> listVouchers(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @PathVariable("expenseId") Long expenseId) {
        expenseService.requireVoucherTarget(principal.getAccountId(), orgId, activityId, expenseId);
        List<VoucherResponse> items = attachmentService
            .listByExpense(principal.getAccountId(), orgId, activityId, expenseId)
            .stream().map(this::toVoucher).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new VouchersResponse(items));
    }

    /** 上传凭证：与活动资料同一套校验、落盘与登记流程，归属改为该笔支出。 */
    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/expenses/{expenseId}/attachments")
    public ResponseEntity<VoucherResponse> uploadVoucher(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @PathVariable("expenseId") Long expenseId,
        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        expenseService.requireVoucherTarget(principal.getAccountId(), orgId, activityId, expenseId);
        if (file != null && file.getSize() > maxVoucherSizeBytes) {
            throw new com.example.activity.common.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "ATTACHMENT_INVALID", "文件过大", "单个文件不超过 20MB。");
        }
        com.example.activity.modules.attachment.AttachmentService.AttachmentOwner owner =
            attachmentService.expenseOwner(expenseId);
        com.example.activity.modules.attachment.AttachmentService.StagedFile staged =
            attachmentService.stage(principal.getAccountId(), orgId, activityId, owner, file);
        try {
            com.example.activity.modules.attachment.AttachmentService.AttachmentItem item =
                attachmentService.register(principal.getAccountId(), orgId, activityId, owner, staged,
                    idempotencyKey == null ? null : idempotencyKey.trim());
            attachmentService.cleanupIfUnreferenced(staged);
            return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(toVoucher(item));
        } catch (RuntimeException ex) {
            attachmentService.cleanupIfUnreferenced(staged);
            throw ex;
        }
    }
}
