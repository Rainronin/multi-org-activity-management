package com.example.activity.modules.attachment;

import com.example.activity.common.ApiException;
import com.example.activity.common.audit.AuditService;
import com.example.activity.modules.activity.Activity;
import com.example.activity.modules.activity.ActivityMapper;
import com.example.activity.modules.organization.OrganizationMember;
import com.example.activity.modules.organization.OrganizationMemberMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * 受控附件（P3.1）：类型与大小白名单 + 内容嗅探；随机不可变存储名。
 * 上传按后端架构 6.1：粗粒度鉴权 → 受控临时目录完成大小／类型／内容检查 → 生成不可变存储名并移入持久目录
 * → 短事务按锁协议（组织锁 → 活动共享锁）再次鉴权并登记归属；登记失败只清理本次生成且未被引用的文件。
 * 删除：先标记不可访问并提交，再清理磁盘；磁盘失败不影响业务可见性。
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
    private final com.example.activity.modules.organization.OrganizationMapper organizationMapper;
    private final Path storageDir;
    private final Path tempDir;

    public AttachmentService(
        AttachmentMapper attachmentMapper,
        ActivityMapper activityMapper,
        OrganizationMemberMapper memberMapper,
        AuditService auditService,
        com.example.activity.modules.account.AccountMapper accountMapper,
        com.example.activity.modules.organization.OrganizationMapper organizationMapper,
        @Value("${app.upload.dir:./uploads}") String storageDir) {
        this.attachmentMapper = attachmentMapper;
        this.activityMapper = activityMapper;
        this.memberMapper = memberMapper;
        this.auditService = auditService;
        this.accountMapper = accountMapper;
        this.organizationMapper = organizationMapper;
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
        this.tempDir = this.storageDir.resolve("tmp");
    }

    public record AttachmentItem(
        Long attachmentId, String activityId, String originalName, String contentType,
        long sizeBytes, Long uploadedBy, String uploaderUsername, String purpose, OffsetDateTime createdAt) {
    }

    private record Context(OrganizationMember actor, Activity activity, boolean canManage) {
    }

    /** 粗粒度检查：只验证有效成员与活动归属，不做锁（锁在登记事务内）。 */
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

    /** 锁内复检：组织锁 → 活动行锁 → 重新鉴权与可见性，供登记事务使用。 */
    private Context requireLockedContext(Long accountId, Long orgId, Long activityId) {
        organizationMapper.lockOrganizationShared(orgId);
        activityMapper.lockActivityShared(activityId);
        return requireContext(accountId, orgId, activityId);
    }

    public List<AttachmentItem> list(Long accountId, Long orgId, Long activityId) {
        requireContext(accountId, orgId, activityId);
        return attachmentMapper.listActiveByActivity(activityId).stream().map(this::toItem).toList();
    }

    /**
     * 上传（第二步）：在短事务内锁内复检权限后登记归属并写审计。
     * 与 {@link #stage} 分开，确保 @Transactional 经过 Spring 代理（避免同类自调用失效，后端架构 5.1）。
     * 登记失败时由调用方清理本次生成且未被引用的文件。
     */
    @Transactional
    public AttachmentItem register(Long accountId, Long orgId, Long activityId, StagedFile staged) {
        Context locked = requireLockedContext(accountId, orgId, activityId);
        if (!locked.canManage()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问", "仅组织负责人或活动负责人可以上传活动资料。");
        }
        // 调整期待确认期间筹备数据只读（S07-A），附件同属筹备数据
        if (locked.activity().isPendingReconfirmation()) {
            throw new ApiException(HttpStatus.CONFLICT, "ACTIVITY_STATE_CONFLICT", "活动安排调整中",
                "活动已撤回到草稿等待重新确认，期间资料只读；重新确认后再上传。");
        }
        // 存储文件已被清理（例如上传过程中被并发删除）时不登记元数据
        if (!Files.exists(staged.persistedPath())) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误", "文件保存失败，请稍后重试。");
        }
        Attachment attachment = new Attachment();
        attachment.setOrgId(orgId);
        attachment.setActivityId(activityId);
        attachment.setPurpose(staged.purpose());
        attachment.setStoredName(staged.storedName());
        attachment.setOriginalName(staged.originalName());
        attachment.setContentType(staged.contentType());
        attachment.setSizeBytes(staged.sizeBytes());
        attachment.setUploadedBy(accountId);
        attachmentMapper.insert(attachment);
        auditService.record(accountId, "ACTIVITY", "ATTACHMENT_UPLOADED", "attachment:" + attachment.getId(),
            null, Map.of("activityId", activityId, "size", staged.sizeBytes(), "purpose", staged.purpose()));
        return toItem(attachmentMapper.findActiveById(attachment.getId()));
    }

    /** 上传（第一步）的返回：已通过校验并落盘的文件元数据与持久路径。 */
    public record StagedFile(
        String originalName, String contentType, String storedName, long sizeBytes, String purpose,
        Path persistedPath) {
    }

    /**
     * 上传（第一步）：粗粒度鉴权 → 受控临时目录完成大小／类型／内容检查 → 生成不可变存储名并移入持久目录。
     * 不含数据库事务；登记由 {@link #register} 在短事务内完成（后端架构 6.1）。
     */
    public StagedFile stage(Long accountId, Long orgId, Long activityId, MultipartFile file) {
        Context coarse = requireContext(accountId, orgId, activityId);
        if (!coarse.canManage()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问", "仅组织负责人或活动负责人可以上传活动资料。");
        }
        ValidatedFile validated = validateAndStage(file);
        Path target = storageDir.resolve(validated.storedName()).normalize();
        if (!target.startsWith(storageDir)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_INVALID", "存储路径无效", "文件存储路径异常。");
        }
        try {
            Files.createDirectories(storageDir);
            Files.move(validated.stagedPath(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误", "文件保存失败，请稍后重试。");
        }
        return new StagedFile(validated.originalName(), validated.contentType(), validated.storedName(),
            validated.sizeBytes(), validated.purpose(), target);
    }

    /** 登记失败后的补偿：只清理本次生成且确认没有数据库引用的文件（后端架构 6.1）。 */
    public void cleanupIfUnreferenced(StagedFile staged) {
        if (attachmentMapper.countByStoredName(staged.storedName()) == 0) {
            deleteQuietly(staged.persistedPath());
        }
    }

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

    /**
     * 删除：组织负责人、活动负责人或上传者。先在事务中标记不可访问，提交后再清理磁盘
     * （后端架构 6.1）；磁盘清理失败保留重试依据，不恢复业务可见性。
     */
    @Transactional
    public void delete(Long accountId, Long orgId, Long activityId, Long attachmentId) {
        Context ctx = requireLockedContext(accountId, orgId, activityId);
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
        Path path = storageDir.resolve(attachment.getStoredName()).normalize();
        // 磁盘清理安排在事务提交之后：提交前不删文件，避免回滚后文件已丢失
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    if (!deleteQuietly(path)) {
                        auditService.record(accountId, "ACTIVITY", "ATTACHMENT_FILE_CLEANUP_FAILED",
                            "attachment:" + attachmentId, "磁盘清理失败，保留重试依据", null);
                    }
                }
            });
        } else {
            deleteQuietly(path);
        }
    }

    /**
     * 大小、扩展名、Content-Type 与内容嗅探；通过后写入受控临时目录，返回待持久化的元数据。
     * 内容嗅探按魔数核对，WEBP 要求 RIFF....WEBP，避免文本改名伪装。
     */
    private ValidatedFile validateAndStage(MultipartFile file) {
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
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_INVALID", "不支持的文件类型",
                "文件的 Content-Type 不在允许列表内。");
        }
        // 扩展名与 Content-Type 必须自洽，避免用 image/png 声明夹带其它格式
        if (!extensionMatchesContentType(extension, contentType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_INVALID", "文件内容与类型不符",
                "文件扩展名与 Content-Type 不一致，请确认文件未损坏或未伪装类型。");
        }
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
            Files.createDirectories(tempDir);
            Path staged = tempDir.resolve(storedName + ".part").normalize();
            if (!staged.startsWith(tempDir)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_INVALID", "存储路径无效", "文件存储路径异常。");
            }
            file.transferTo(staged);
            return new ValidatedFile(originalName, contentType, storedName, file.getSize(),
                Attachment.PURPOSE_ACTIVITY_DOC, staged);
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误", "文件保存失败，请稍后重试。");
        }
    }

    private record ValidatedFile(
        String originalName, String contentType, String storedName, long sizeBytes,
        String purpose, Path stagedPath) {
    }

    private boolean extensionMatchesContentType(String extension, String contentType) {
        return switch (extension) {
            case "png" -> "image/png".equals(contentType);
            case "jpg", "jpeg" -> "image/jpeg".equals(contentType);
            case "gif" -> "image/gif".equals(contentType);
            case "webp" -> "image/webp".equals(contentType);
            case "pdf" -> "application/pdf".equals(contentType);
            case "txt" -> "text/plain".equals(contentType);
            case "zip" -> "application/zip".equals(contentType);
            case "docx" -> contentType.endsWith("wordprocessingml.document");
            case "xlsx" -> contentType.endsWith("spreadsheetml.sheet");
            case "pptx" -> contentType.endsWith("presentationml.presentation");
            default -> false;
        };
    }

    /** 内容嗅探：按魔数核对；WEBP 为 RIFF 容器，需同时校验第 8～11 字节。 */
    private boolean contentMatches(String extension, InputStream in) throws IOException {
        byte[] magic = in.readNBytes(12);
        return switch (extension) {
            case "png" -> starts(magic, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
            case "jpg", "jpeg" -> starts(magic, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
            case "gif" -> starts(magic, "GIF8".getBytes());
            case "webp" -> starts(magic, "RIFF".getBytes()) && matchesAt(magic, 8, "WEBP".getBytes());
            case "pdf" -> starts(magic, "%PDF".getBytes());
            case "zip", "docx", "xlsx", "pptx" -> starts(magic, new byte[] {0x50, 0x4B});
            case "txt" -> isProbablyText(magic);
            default -> false;
        };
    }

    /**
     * 文本白名单类型：无 NUL 字节且不含 C0 控制字符（换行、制表、回车除外）。
     * 允许 0x80 以上的高位字节，UTF-8 中文属于正常文本；只拦二进制与容器伪装。
     */
    private boolean isProbablyText(byte[] sample) {
        for (byte b : sample) {
            int value = b & 0xFF;
            if (value == 0) {
                return false;
            }
            boolean allowedControl = value == '\n' || value == '\r' || value == '\t';
            if (value < 0x20 && !allowedControl) {
                return false;
            }
            if (value == 0x7F) {
                return false;
            }
        }
        return true;
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

    private boolean matchesAt(byte[] data, int offset, byte[] expected) {
        if (data.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (data[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
            return true;
        } catch (IOException ex) {
            return false;
        }
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
            accountMapper.findUsernameById(attachment.getUploadedBy()), attachment.getPurpose(),
            attachment.getCreatedAt());
    }
}
