package com.example.activity.modules.attachment;

import com.example.activity.security.AccountUserDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 附件端点（P3.1）：multipart 上传、列表、下载与删除；权限在 Service 内校验。 */
@RestController
public class AttachmentController {

    private final AttachmentService attachmentService;

    private final long maxSizeBytes;

    public AttachmentController(
        AttachmentService attachmentService,
        @Value("${app.upload.max-size-bytes:20971520}") long maxSizeBytes) {
        this.attachmentService = attachmentService;
        this.maxSizeBytes = maxSizeBytes;
    }

    public record AttachmentResponse(
        String attachmentId, String activityId, String taskId, String expenseId, String originalName,
        String contentType, long sizeBytes, String uploaderUsername, String purpose,
        java.time.OffsetDateTime createdAt) {
    }

    public record AttachmentsResponse(List<AttachmentResponse> items) {
    }

    private AttachmentResponse toResponse(AttachmentService.AttachmentItem item) {
        return new AttachmentResponse(String.valueOf(item.attachmentId()), item.activityId(), item.taskId(),
            item.expenseId(), item.originalName(), item.contentType(), item.sizeBytes(),
            item.uploaderUsername(), item.purpose(), item.createdAt());
    }

    /**
     * 上传（后端架构 6.1）：先在事务外完成校验与落盘，再调用事务方法登记；
     * 登记失败只清理本次生成且未被引用的文件。两次调用都经过 Spring 代理，事务不会被自调用绕过。
     */
    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/attachments")
    public ResponseEntity<AttachmentResponse> upload(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestParam("file") MultipartFile file) {
        return uploadFor(principal, orgId, activityId, AttachmentService.ACTIVITY_DOC, idempotencyKey, file);
    }

    /** S10：任务资料上传；权限为组织负责人、活动负责人或该任务承担者。 */
    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/tasks/{taskId}/attachments")
    public ResponseEntity<AttachmentResponse> uploadForTask(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @PathVariable("taskId") Long taskId,
        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestParam("file") MultipartFile file) {
        return uploadFor(principal, orgId, activityId,
            attachmentService.taskOwner(orgId, activityId, taskId), idempotencyKey, file);
    }

    private ResponseEntity<AttachmentResponse> uploadFor(
        AccountUserDetails principal, Long orgId, Long activityId,
        AttachmentService.AttachmentOwner owner, String idempotencyKey, MultipartFile file) {
        if (file != null && file.getSize() > maxSizeBytes) {
            throw new com.example.activity.common.ApiException(HttpStatus.BAD_REQUEST,
                "ATTACHMENT_INVALID", "文件过大", "单个文件不超过 20MB。");
        }
        AttachmentService.StagedFile staged =
            attachmentService.stage(principal.getAccountId(), orgId, activityId, owner, file);
        try {
            AttachmentService.AttachmentItem item = attachmentService.register(
                principal.getAccountId(), orgId, activityId, owner, staged, idempotencyKey);
            // F06：同键重放时本次暂存的文件没有引用，随即清理，避免留下孤儿文件
            attachmentService.cleanupIfUnreferenced(staged);
            return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(toResponse(item));
        } catch (RuntimeException ex) {
            attachmentService.cleanupIfUnreferenced(staged);
            throw ex;
        }
    }

    @GetMapping("/api/organizations/{orgId}/activities/{activityId}/attachments")
    public ResponseEntity<AttachmentsResponse> list(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId) {
        List<AttachmentResponse> items = attachmentService.list(principal.getAccountId(), orgId, activityId)
            .stream().map(this::toResponse).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new AttachmentsResponse(items));
    }

    /** S10：按任务列出资料。 */
    @GetMapping("/api/organizations/{orgId}/activities/{activityId}/tasks/{taskId}/attachments")
    public ResponseEntity<AttachmentsResponse> listByTask(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @PathVariable("taskId") Long taskId) {
        List<AttachmentResponse> items =
            attachmentService.listByTask(principal.getAccountId(), orgId, activityId, taskId)
                .stream().map(this::toResponse).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new AttachmentsResponse(items));
    }

    @GetMapping("/api/organizations/{orgId}/activities/{activityId}/attachments/{attachmentId}/download")
    public ResponseEntity<ByteArrayResource> download(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @PathVariable("attachmentId") Long attachmentId) {
        AttachmentService.Download download =
            attachmentService.download(principal.getAccountId(), orgId, activityId, attachmentId);
        // F07：filename 交给 ContentDisposition 编码（输出 filename* 的 UTF-8 百分号形式）；
        // 预先 URLEncoder 会二次编码成 %25E5…，客户端拿到的是乱码文件名。
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                    .filename(download.attachment().getOriginalName(), StandardCharsets.UTF_8)
                    .build().toString())
            .body(new ByteArrayResource(download.content()));
    }

    @DeleteMapping("/api/organizations/{orgId}/activities/{activityId}/attachments/{attachmentId}")
    public ResponseEntity<Void> delete(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @PathVariable("attachmentId") Long attachmentId) {
        attachmentService.delete(principal.getAccountId(), orgId, activityId, attachmentId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
