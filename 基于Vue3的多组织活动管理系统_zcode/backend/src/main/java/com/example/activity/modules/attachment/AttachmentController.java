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
        String attachmentId, String activityId, String originalName, String contentType,
        long sizeBytes, String uploaderUsername, java.time.OffsetDateTime createdAt) {
    }

    public record AttachmentsResponse(List<AttachmentResponse> items) {
    }

    private AttachmentResponse toResponse(AttachmentService.AttachmentItem item) {
        return new AttachmentResponse(String.valueOf(item.attachmentId()), item.activityId(),
            item.originalName(), item.contentType(), item.sizeBytes(), item.uploaderUsername(),
            item.createdAt());
    }

    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/attachments")
    public ResponseEntity<AttachmentResponse> upload(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @RequestParam("file") MultipartFile file) {
        if (file != null && file.getSize() > maxSizeBytes) {
            throw new com.example.activity.common.ApiException(HttpStatus.BAD_REQUEST,
                "ATTACHMENT_INVALID", "文件过大", "单个文件不超过 20MB。");
        }
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
            .body(toResponse(attachmentService.upload(principal.getAccountId(), orgId, activityId, file)));
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

    @GetMapping("/api/organizations/{orgId}/activities/{activityId}/attachments/{attachmentId}/download")
    public ResponseEntity<ByteArrayResource> download(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @PathVariable("attachmentId") Long attachmentId) {
        AttachmentService.Download download =
            attachmentService.download(principal.getAccountId(), orgId, activityId, attachmentId);
        String encodedName = java.net.URLEncoder.encode(download.attachment().getOriginalName(),
            StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(encodedName, StandardCharsets.UTF_8).build().toString())
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
