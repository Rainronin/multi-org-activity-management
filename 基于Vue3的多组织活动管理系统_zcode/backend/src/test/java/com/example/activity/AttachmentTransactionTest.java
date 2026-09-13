package com.example.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    private MvcResult upload(TestSession actor, Long orgId, Long activityId, String name, String contentType,
        byte[] content, int expectedStatus) throws Exception {
        return mockMvc.perform(multipart("/api/organizations/" + orgId + "/activities/" + activityId + "/attachments")
                .file(new MockMultipartFile("file", name, contentType, content))
                .with(csrf()).session(actor.session()))
            .andExpect(status().is(expectedStatus))
            .andReturn();
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
                .content("{\"reason\":\"安排调整\"}"))
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
}
