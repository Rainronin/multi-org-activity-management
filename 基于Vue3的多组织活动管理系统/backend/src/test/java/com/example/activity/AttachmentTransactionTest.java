package com.example.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.activity.modules.attachment.AttachmentService;
import com.example.activity.support.FlowTestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

/**
 * S09 回归：附件内容校验、用途与事务时序。
 * 覆盖：WEBP 伪装（仅扩展名/Content-Type 像）被拒、扩展名与 Content-Type 不一致被拒、
 * 二进制改名 txt 被拒、正常图片与文本可上传且用途为 ACTIVITY_DOC；
 * 调整期待确认期间资料只读；删除先失效元数据再清理磁盘。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AttachmentTransactionTest extends FlowTestSupport {

    private static final byte[] PNG = new byte[] {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
    /** RIFF 容器：前 4 字节 RIFF、第 8～11 字节 WEBP。 */
    private static final byte[] WEBP = new byte[] {
        'R', 'I', 'F', 'F', 0x1A, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P', 1, 2, 3};

    @Autowired
    private AttachmentService attachmentService;

    private Path storageDir() {
        return (Path) ReflectionTestUtils.getField(attachmentService, "storageDir");
    }

    /** F06：持久目录里的文件数（不含 tmp 子目录），用于核对重放不遗留孤儿文件。 */
    private long storedFileCount() throws java.io.IOException {
        try (var paths = Files.list(storageDir())) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private MvcResult upload(TestSession actor, Long orgId, Long activityId, String name, String contentType,
        byte[] content, int expectedStatus) throws Exception {
        return uploadWithKey(actor, orgId, activityId, name, contentType, content, expectedStatus,
            newIdempotencyKey());
    }

    /** F06：上传必须携带 Idempotency-Key；此重载供防重重放用例复用同一个键。 */
    private MvcResult uploadWithKey(TestSession actor, Long orgId, Long activityId, String name,
        String contentType, byte[] content, int expectedStatus, String key) throws Exception {
        return mockMvc.perform(multipart("/api/organizations/" + orgId + "/activities/" + activityId + "/attachments")
                .file(new MockMultipartFile("file", name, contentType, content))
                .header("Idempotency-Key", key)
                .with(csrf()).session(actor.session()))
            .andExpect(status().is(expectedStatus))
            .andReturn();
    }

    /** S10：任务资料上传（同一套校验与落盘／登记流程，归属改为任务）。 */
    private MvcResult uploadForTask(TestSession actor, Long orgId, Long activityId, String taskId,
        String name, String contentType, byte[] content, int expectedStatus) throws Exception {
        return mockMvc.perform(multipart("/api/organizations/" + orgId + "/activities/" + activityId
                + "/tasks/" + taskId + "/attachments")
                .file(new MockMultipartFile("file", name, contentType, content))
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(actor.session()))
            .andExpect(status().is(expectedStatus))
            .andReturn();
    }

    private String createTask(TestSession actor, Long orgId, Long activityId, Long assigneeMemberId)
        throws Exception {
        MvcResult created = mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/tasks")
                .with(csrf()).session(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"任务资料核对\",\"assigneeMemberId\":%d}").formatted(assigneeMemberId)))
            .andExpect(status().isCreated())
            .andReturn();
        return readField(created, "$.taskId");
    }

    @Test
    void taskAttachmentsAreScopedAndPermissionChecked() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession assignee = registerAndLogin();
        TestSession outsider = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, assignee, orgId);
        inviteAndAccept(owner, outsider, orgId);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-04-01T14:00:00+08:00");
        String taskId = createTask(owner, orgId, activityId, memberIdOf(orgId, assignee.username()));
        String otherTaskId = createTask(owner, orgId, activityId, memberIdOf(orgId, owner.username()));

        // 任务承担者可以上传任务资料，用途与归属正确
        MvcResult uploaded = uploadForTask(assignee, orgId, activityId, taskId, "现场照片.png", "image/png", PNG, 201);
        assertThat(readField(uploaded, "$.purpose")).isEqualTo("TASK_ATTACHMENT");
        assertThat(readField(uploaded, "$.taskId")).isEqualTo(taskId);
        String attachmentId = readField(uploaded, "$.attachmentId");

        // 非承担者的普通成员不能上传到该任务
        uploadForTask(outsider, orgId, activityId, taskId, "越权.png", "image/png", PNG, 403);

        // 任务资料不出现在活动资料列表，只出现在该任务下
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId + "/attachments")
                .session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId
                + "/tasks/" + taskId + "/attachments").session(assignee.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].attachmentId").value(attachmentId));
        // 归属不同任务的资料互不可见
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId
                + "/tasks/" + otherTaskId + "/attachments").session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));

        // 任务资料可以下载；不存在的任务返回 404
        // F07：中文文件名只在 Content-Disposition 里编码一次（filename*=UTF-8''…），不是 %25 双重编码
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId
                + "/attachments/" + attachmentId + "/download").session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("filename*=UTF-8''%E7%8E%B0%E5%9C%BA%E7%85%A7%E7%89%87.png")))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("%25"))));
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId
                + "/tasks/999999/attachments").session(owner.session()))
            .andExpect(status().isNotFound());

        // 承担者可以删除自己上传的任务资料；无关成员的删除被拒
        mockMvc.perform(delete("/api/organizations/" + orgId + "/activities/" + activityId
                + "/attachments/" + attachmentId)
                .with(csrf()).session(outsider.session()))
            .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/organizations/" + orgId + "/activities/" + activityId
                + "/attachments/" + attachmentId)
                .with(csrf()).session(assignee.session()))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId
                + "/tasks/" + taskId + "/attachments").session(assignee.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void contentSniffingRejectsDisguisedFiles() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-03-01T14:00:00+08:00");

        // S09 原缺陷：文本内容声称 image/webp 就能上传成功（201）→ 现在按 RIFF/WEBP 魔数拒绝
        upload(owner, orgId, activityId, "fake.webp", "image/webp",
            "This is not a WebP image".getBytes(StandardCharsets.UTF_8), 400);

        // 扩展名与 Content-Type 不一致
        upload(owner, orgId, activityId, "poster.png", "image/webp", PNG, 400);

        // 二进制内容改名 txt（含 NUL 字节）
        upload(owner, orgId, activityId, "notes.txt", "text/plain",
            new byte[] {0x00, 0x01, 0x02, (byte) 0xFF, 0x00}, 400);

        // 合法文件仍然可以上传：真实 PNG 与真实文本
        MvcResult png = upload(owner, orgId, activityId, "海报.png", "image/png", PNG, 201);
        assertThat(readField(png, "$.purpose")).isEqualTo("ACTIVITY_DOC");
        upload(owner, orgId, activityId, "说明.txt", "text/plain",
            "正常文本内容\n第二行".getBytes(StandardCharsets.UTF_8), 201);

        // 校验失败不得留下本次的孤立文件：本活动的元数据与磁盘文件一一对应
        // （上传目录是跨测试共享的临时目录，可能残留其它测试／上一次运行的文件，因此按活动核对）
        var registered = jdbcTemplate.queryForList(
            "SELECT stored_name FROM attachment WHERE activity_id = ?", String.class, activityId);
        assertThat(registered).hasSize(2);
        try (var files = Files.list(storageDir())) {
            var onDisk = files.filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString()).toList();
            assertThat(onDisk).containsAll(registered);
        }
        for (String storedName : registered) {
            assertThat(Files.exists(storageDir().resolve(storedName))).isTrue();
        }
    }

    @Test
    void realWebpIsAccepted() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-03-02T14:00:00+08:00");

        MvcResult created = upload(owner, orgId, activityId, "封面.webp", "image/webp", WEBP, 201);
        assertThat(readField(created, "$.contentType")).isEqualTo("image/webp");
    }

    @Test
    void attachmentsAreReadOnlyDuringReconfirmation() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-03-03T14:00:00+08:00");

        MvcResult uploaded = upload(owner, orgId, activityId, "资料.png", "image/png", PNG, 201);
        String attachmentId = readField(uploaded, "$.attachmentId");

        // 撤回到草稿（调整期待确认）：上传被拒，下载仍可用
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/withdraw-approval")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"reason\":\"安排调整\"}")))
            .andExpect(status().isOk());
        upload(owner, orgId, activityId, "调整期资料.png", "image/png", PNG, 409);
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId
                + "/attachments/" + attachmentId + "/download").session(owner.session()))
            .andExpect(status().isOk());
    }

    @Test
    void deleteRemovesMetadataThenFile() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-03-04T14:00:00+08:00");
        MvcResult uploaded = upload(owner, orgId, activityId, "待删.png", "image/png", PNG, 201);
        String attachmentId = readField(uploaded, "$.attachmentId");
        String storedName = jdbcTemplate.queryForObject(
            "SELECT stored_name FROM attachment WHERE id = ?", String.class, Long.parseLong(attachmentId));
        Path storedPath = storageDir().resolve(storedName);
        assertThat(Files.exists(storedPath)).isTrue();

        // 无关成员不能删除
        mockMvc.perform(delete("/api/organizations/" + orgId + "/activities/" + activityId
                + "/attachments/" + attachmentId)
                .with(csrf()).session(member.session()))
            .andExpect(status().isForbidden());

        // 上传者删除：元数据先失效，磁盘文件在提交后清理
        mockMvc.perform(delete("/api/organizations/" + orgId + "/activities/" + activityId
                + "/attachments/" + attachmentId)
                .with(csrf()).session(owner.session()))
            .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT deleted_at IS NOT NULL FROM attachment WHERE id = ?", Boolean.class, Long.parseLong(attachmentId)))
            .isTrue();
        assertThat(Files.exists(storedPath)).isFalse();
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId
                + "/attachments/" + attachmentId + "/download").session(owner.session()))
            .andExpect(status().isNotFound());
    }

    /**
     * F07 两阶段回归：任务承担者在暂存（stage）通过后、登记（register）之前被改派，
     * 登记必须按锁内重读的当前任务拒绝旧承担者，不能使用阶段一的旧 Task 对象授权。
     */
    @Test
    void reassignmentBetweenStageAndRegisterDeniesStaleAssignee() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long ownerMemberId = memberIdOf(orgId, owner.username());
        Long memberMemberId = memberIdOf(orgId, member.username());
        Long activityId = createApprovedActivity(owner, orgId, ownerMemberId, "2027-04-05T14:00:00+08:00");
        String taskId = createTask(owner, orgId, activityId, memberMemberId);

        // 阶段一：旧承担者通过粗粒度检查并落盘，随后被改派
        AttachmentService.AttachmentOwner staleOwner =
            attachmentService.taskOwner(orgId, activityId, Long.parseLong(taskId));
        AttachmentService.StagedFile staged = attachmentService.stage(
            accountIdOf(orgId, member.username()), orgId, activityId, staleOwner,
            new MockMultipartFile("file", "阶段照片.png", "image/png", PNG));        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/tasks/" + taskId + "/update")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(taskVersioned(taskId, ("{\"title\":\"任务资料核对\",\"assigneeMemberId\":%d}")
                    .formatted(ownerMemberId))))
            .andExpect(status().isOk());

        // 阶段二：旧承担者登记被拒，且没有留下元数据
        com.example.activity.common.ApiException denied = org.junit.jupiter.api.Assertions.assertThrows(
            com.example.activity.common.ApiException.class,
            () -> attachmentService.register(
                accountIdOf(orgId, member.username()), orgId, activityId, staleOwner, staged,
                newIdempotencyKey()));
        assertThat(denied.getCode()).isEqualTo("FORBIDDEN");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM attachment WHERE stored_name = ?", Integer.class, staged.storedName()))
            .isEqualTo(0);
        // 控制器在登记失败后清理本次文件；服务层同样只清理无引用的文件
        attachmentService.cleanupIfUnreferenced(staged);
        assertThat(Files.exists(staged.persistedPath())).isFalse();

        // 新承担者可以正常上传任务资料
        AttachmentService.AttachmentOwner currentOwner =
            attachmentService.taskOwner(orgId, activityId, Long.parseLong(taskId));
        AttachmentService.StagedFile currentStaged = attachmentService.stage(
            accountIdOf(orgId, owner.username()), orgId, activityId, currentOwner,
            new MockMultipartFile("file", "改派后照片.png", "image/png", PNG));
        AttachmentService.AttachmentItem registered = attachmentService.register(
            accountIdOf(orgId, owner.username()), orgId, activityId, currentOwner, currentStaged,
            newIdempotencyKey());
        assertThat(registered.taskId()).isEqualTo(taskId);
        assertThat(Files.exists(currentStaged.persistedPath())).isTrue();
    }

    private Long accountIdOf(Long orgId, String username) {
        return jdbcTemplate.queryForObject(
            "SELECT m.account_id FROM organization_member m JOIN account a ON a.id = m.account_id "
                + "WHERE m.org_id = ? AND a.username = ? AND m.status = 'ACTIVE'",
            Long.class, orgId, username);
    }

    /** F06：缺防重键的上传被拒；同键同内容重放返回原附件，同键异内容 409。 */
    @Test
    void uploadRequiresIdempotencyKeyAndReplaysSameResult() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-04-06T14:00:00+08:00");

        // 缺键：400，且不留元数据与磁盘文件
        mockMvc.perform(multipart("/api/organizations/" + orgId + "/activities/" + activityId + "/attachments")
                .file(new MockMultipartFile("file", "无键.png", "image/png", PNG))
                .with(csrf()).session(owner.session()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM attachment WHERE activity_id = ?", Integer.class, activityId)).isEqualTo(0);

        String key = newIdempotencyKey();
        MvcResult first = uploadWithKey(owner, orgId, activityId, "防重资料.png", "image/png", PNG, 201, key);
        String attachmentId = readField(first, "$.attachmentId");
        long filesAfterFirst = storedFileCount();

        // 同键同内容重放：返回原资源，不新增记录，且暂存的重复文件被清理
        MvcResult replay = uploadWithKey(owner, orgId, activityId, "防重资料.png", "image/png", PNG, 201, key);
        assertThat(readField(replay, "$.attachmentId")).isEqualTo(attachmentId);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM attachment WHERE activity_id = ?", Integer.class, activityId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM attachment WHERE request_key = ?", Integer.class, key)).isEqualTo(1);
        assertThat(storedFileCount()).isEqualTo(filesAfterFirst);

        // 同键不同内容：409，不静默替换
        byte[] otherPng = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 9, 9};
        uploadWithKey(owner, orgId, activityId, "防重资料.png", "image/png", otherPng, 409, key);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM attachment WHERE activity_id = ?", Integer.class, activityId)).isEqualTo(1);

        // 换键可正常再上传
        uploadWithKey(owner, orgId, activityId, "第二份.png", "image/png", otherPng, 201, newIdempotencyKey());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM attachment WHERE activity_id = ?", Integer.class, activityId)).isEqualTo(2);
    }
}
