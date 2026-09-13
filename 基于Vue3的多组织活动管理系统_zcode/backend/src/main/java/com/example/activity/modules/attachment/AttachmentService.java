package com.example.activity.modules.attachment;

import com.example.activity.common.ApiException;
import com.example.activity.common.audit.AuditService;
import com.example.activity.modules.activity.Activity;
import com.example.activity.modules.activity.ActivityMapper;
import com.example.activity.modules.organization.OrganizationMember;
import com.example.activity.modules.organization.OrganizationMemberMapper;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 受控附件（P3.1）：类型与大小白名单 + 内容嗅探；随机不可变存储名；
 * 上传先落盘校验再事务登记；删除先标记后清理磁盘；下载与删除逐请求鉴权。
 */
@Service
public class AttachmentService {

    private static final long MAX_SIZE_BYTES = 20L * 1024 * 1024;
    private static final List<String> ALLOWED_EXTENSIONS = List.of(
        "png", "jpg", "jpeg", "gif", "webp", "pdf", "txt", "docx", "xlsx", "pptx", "zip");
    private static final List<String> ALLOWED_CONTENT_TYPES = List.of(
        "image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf",
        "text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/zip");

    private final AttachmentMapper attachmentMapper;
    private final ActivityMapper activityMapper;
    private final OrganizationMemberMapper memberMapper;
    private final AuditService auditService;
    private final com.example.activity.modules.account.AccountMapper accountMapper;
    private final Path storageDir;

    public AttachmentService(
        AttachmentMapper attachmentMapper,
        ActivityMapper activityMapper,
        OrganizationMemberMapper memberMapper,
        AuditService auditService,
        com.example.activity.modules.account.AccountMapper accountMapper,
        @Value("${app.upload.dir:./uploads}") String storageDir) {
        this.attachmentMapper = attachmentMapper;
        this.activityMapper = activityMapper;
        this.memberMapper = memberMapper;
        this.auditService = auditService;
        this.accountMapper = accountMapper;
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
    }

    public record AttachmentItem(
        Long attachmentId, String activityId, String originalName, String contentType,
        long sizeBytes, Long uploadedBy, String uploaderUsername, OffsetDateTime createdAt) {
    }

    private record Context(OrganizationMember actor, Activity activity, boolean canManage) {
    }

    private Context requireContext(Long accountId, Long orgId, Long activityId) {
        OrganizationMember actor = memberMapper.findActive(orgId, accountId);
        Activity activity = actor == null ? null : activityMapper.findById(activityId);
        // S06：附件沿用活动自身可见性，未发布活动的资料不能被非负责人成员列出或下载
        if (actor == null || activity == null || !activity.getOrgId().equals(orgId)
            || !activity.isVisibleTo(actor, accountId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "活动不存在或无权访问。");
        }
        boolean canManage = OrganizationMember.ROLE_OWNER.equals(actor.getRole())
            || actor.getId().equals(activity.getOwnerMemberId());
        return new Context(actor, activity, canManage);
    }

    public List<AttachmentItem> list(Long accountId, Long orgId, Long activityId) {
        requireContext(accountId, orgId, activityId);
        return attachmentMapper.listActiveByActivity(activityId).stream().map(this::toItem).toList();
    }

    @Transactional
    public AttachmentItem upload(Long accountId, Long orgId, Long activityId, MultipartFile file) {
        Context ctx = requireContext(accountId, orgId, activityId);
        if (!ctx.canManage()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问", "仅组织负责人或活动负责人可以上传活动资料。");
        }
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_INVALID", "文件无效", "请选择要上传的文件。");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_INVALID", "文件过大", "单个文件不超过 20MB。");
        }
        String originalName = file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename();
        String extension = extensionOf(originalName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_INVALID", "不支持的文件类型",
                "仅支持图片、PDF、文本、Office 文档与 ZIP 压缩包。");
        }
        if (file.getContentType() == null || !ALLOWED_CONTENT_TYPES.contains(file.getContentType().toLowerCase(Locale.ROOT))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_INVALID", "不支持的文件类型",
                "文件的 Content-Type 不在允许列表内。");
        }
        // 内容嗅探：图片 / PDF / ZIP 按魔数核对，防止伪装扩展名
        try (InputStream in = file.getInputStream()) {
            if (!contentMatches(extension, in)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_INVALID", "文件内容与类型不符",
                    "文件内容校验未通过，请确认文件未损坏或未伪装类型。");
            }
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_INVALID", "文件读取失败", "无法读取上传的文件。");
        }

        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        try {
            Files.createDirectories(storageDir);
            Path target = storageDir.resolve(storedName).normalize();
            if (!target.startsWith(storageDir)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_INVALID", "存储路径无效", "文件存储路径异常。");
            }
            file.transferTo(target);
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误", "文件保存失败，请稍后重试。");
        }

        Attachment attachment = new Attachment();
        attachment.setOrgId(orgId);
        attachment.setActivityId(activityId);
        attachment.setPurpose(Attachment.PURPOSE_ACTIVITY_DOC);
        attachment.setStoredName(storedName);
        attachment.setOriginalName(originalName);
        attachment.setContentType(file.getContentType());
        attachment.setSizeBytes(file.getSize());
        attachment.setUploadedBy(accountId);
        attachmentMapper.insert(attachment);
        auditService.record(accountId, "ACTIVITY", "ATTACHMENT_UPLOADED", "attachment:" + attachment.getId(),
            null, Map.of("activityId", activityId, "size", file.getSize()));
        return toItem(attachmentMapper.findActiveById(attachment.getId()));
    }

    /** 下载鉴权：本组织有效成员；存储文件缺失返回 404（不泄露细节）。 */
    public record Download(Attachment attachment, byte[] content) {
    }

    public Download download(Long accountId, Long orgId, Long activityId, Long attachmentId) {
        requireContext(accountId, orgId, activityId);
        Attachment attachment = attachmentMapper.findActiveById(attachmentId);
        if (attachment == null || !attachment.getOrgId().equals(orgId)
            || !attachment.getActivityId().equals(activityId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "附件不存在或无权访问。");
        }
        try {
            Path path = storageDir.resolve(attachment.getStoredName()).normalize();
            if (!path.startsWith(storageDir) || !Files.exists(path)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "附件文件缺失。");
            }
            return new Download(attachment, Files.readAllBytes(path));
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "附件文件缺失。");
        }
    }

    /** 删除：组织负责人或上传者；先标记软删除，磁盘清理失败保留重试依据。 */
    @Transactional
    public void delete(Long accountId, Long orgId, Long activityId, Long attachmentId) {
        Context ctx = requireContext(accountId, orgId, activityId);
        Attachment attachment = attachmentMapper.findActiveById(attachmentId);
        if (attachment == null || !attachment.getOrgId().equals(orgId)
            || !attachment.getActivityId().equals(activityId)) {
            throw notFound();
        }
        if (!ctx.canManage() && !attachment.getUploadedBy().equals(accountId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问", "仅组织负责人、活动负责人或上传者可以删除附件。");
        }
        if (attachmentMapper.markDeleted(attachmentId) != 1) {
            throw notFound();
        }
        auditService.record(accountId, "ACTIVITY", "ATTACHMENT_DELETED", "attachment:" + attachmentId,
            null, Map.of("activityId", activityId));
        try {
            Files.deleteIfExists(storageDir.resolve(attachment.getStoredName()));
        } catch (IOException ex) {
            // 磁盘清理失败不回滚业务删除；文件成为孤立文件，由维护流程按清单核对清理
            auditService.record(accountId, "ACTIVITY", "ATTACHMENT_FILE_CLEANUP_FAILED",
                "attachment:" + attachmentId, ex.getMessage(), null);
        }
    }

    /** 内容嗅探：仅对可识别格式核对魔数，文本与 Office（ZIP 容器）按扩展信任并限制大小。 */
    private boolean contentMatches(String extension, InputStream in) throws IOException {
        byte[] magic = in.readNBytes(8);
        return switch (extension) {
            case "png" -> starts(magic, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47});
            case "jpg", "jpeg" -> starts(magic, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
            case "gif" -> starts(magic, "GIF8".getBytes());
            case "pdf" -> starts(magic, "%PDF".getBytes());
            case "zip", "docx", "xlsx", "pptx" -> starts(magic, new byte[] {0x50, 0x4B});
            default -> true; // txt / webp 不做魔数强校验，类型与大小受限
        };
    }

    private boolean starts(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "附件不存在或无权访问。");
    }

    private AttachmentItem toItem(Attachment attachment) {
        return new AttachmentItem(
            attachment.getId(), String.valueOf(attachment.getActivityId()), attachment.getOriginalName(),
            attachment.getContentType(), attachment.getSizeBytes(), attachment.getUploadedBy(),
            accountMapper.findUsernameById(attachment.getUploadedBy()), attachment.getCreatedAt());
    }
}
