package com.example.activity;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.activity.support.ActivityTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * P3 链路：任务创建/分派/推进/权限、物资维护与状态、附件上传下载删除与伪装拒绝、
 * 成员退出前的未结责任检查（P1.4 联动）、任务分派通知意图落库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class P3FlowTest extends ActivityTestBase {

    private static final String PASSWORD = "Passw0rd123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record TestSession(String username, MockHttpSession session) {
    }

    private TestSession registerAndLogin() throws Exception {
        String username = "p3" + Long.toString(System.nanoTime(), 36);
        mockMvc.perform(post("/api/auth/register").with(csrf())
                .with(request -> {
                    request.setRemoteAddr("10.%d.%d.31".formatted(
                        (username.hashCode() >> 8) & 0xFF, username.hashCode() & 0xFF));
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD)))
            .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/auth/login").with(csrf())
                .param("username", username)
                .param("password", PASSWORD))
            .andExpect(status().isNoContent())
            .andReturn();
        return new TestSession(username, (MockHttpSession) login.getRequest().getSession());
    }

    private Long createOrg(TestSession owner) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/organizations").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"P3组织-%d\"}".formatted(System.nanoTime())))
            .andExpect(status().isCreated())
            .andReturn();
        return Long.parseLong(com.jayway.jsonpath.JsonPath.read(
            result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.id"));
    }

    private void inviteAndAccept(TestSession owner, TestSession invitee, Long orgId) throws Exception {
        MvcResult invitation = mockMvc.perform(
                post("/api/organizations/" + orgId + "/invitations").with(csrf()).session(owner.session())
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isCreated())
            .andReturn();
        String code = com.jayway.jsonpath.JsonPath.read(
            invitation.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.code");
        mockMvc.perform(post("/api/invitations/accept").with(csrf()).session(invitee.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"%s\"}".formatted(code)))
            .andExpect(status().isOk());
    }

    private Long createApprovedActivity(TestSession owner, Long orgId, Long ownerMemberId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"name\":\"P3活动\",\"ownerMemberId\":%d,\"description\":\"简介\","
                    + "\"startTime\":\"2026-11-01T14:00:00+08:00\",\"endTime\":\"2026-11-01T16:00:00+08:00\","
                    + "\"location\":\"礼堂\"}").formatted(ownerMemberId)))
            .andExpect(status().isCreated())
            .andReturn();
        Long activityId = Long.parseLong(com.jayway.jsonpath.JsonPath.read(
            created.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.id"));
        mockMvc.perform(put("/api/organizations/" + orgId + "/activities/" + activityId + "/proposal")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"方案\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/submit")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/approve")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk());
        return activityId;
    }

    private Long memberIdOf(Long orgId, String username) {
        return jdbcTemplate.queryForObject(
            "SELECT m.id FROM organization_member m JOIN account a ON a.id = m.account_id "
                + "WHERE m.org_id = ? AND a.username = ? AND m.status = 'ACTIVE'",
            Long.class, orgId, username);
    }

    private Long accountIdOf(String username) {
        return jdbcTemplate.queryForObject("SELECT id FROM account WHERE username = ?", Long.class, username);
    }

    @Test
    void taskLifecyclePermissionAndNotification() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()));
        Long memberMemberId = memberIdOf(orgId, member.username());

        // OWNER 创建任务并分派给成员；通知意图同事务落库
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/tasks")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"布置会场\",\"description\":\"桌椅摆放\",\"assigneeMemberId\":%d,"
                        + "\"priority\":\"HIGH\",\"dueAt\":\"2026-10-30T18:00:00+08:00\"}")
                        .formatted(memberMemberId).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"));

        MvcResult list = mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId + "/tasks")
                .session(owner.session()))
            .andExpect(status().isOk())
            .andReturn();
        Long taskId = Long.parseLong(com.jayway.jsonpath.JsonPath.read(
            list.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.items[0].taskId"));

        Integer notifications = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notification WHERE activity_id = ? AND type = 'TASK_ASSIGNED'",
            Integer.class, activityId);
        assertThat(notifications).isEqualTo(1);

        // 非分派成员不能推进他人任务
        TestSession outsider = registerAndLogin();
        inviteAndAccept(owner, outsider, orgId);
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/tasks/" + taskId + "/advance")
                .with(csrf()).session(outsider.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toStatus\":\"IN_PROGRESS\"}"))
            .andExpect(status().isForbidden());

        // 分派成员推进：PENDING → IN_PROGRESS → COMPLETED；非法跳跃 → 409
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/tasks/" + taskId + "/advance")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toStatus\":\"COMPLETED\"}"))
            .andExpect(status().isConflict());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/tasks/" + taskId + "/advance")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toStatus\":\"IN_PROGRESS\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/tasks/" + taskId + "/advance")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toStatus\":\"COMPLETED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"));

        // 成员不能取消任务；OWNER 可重新打开
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/tasks/" + taskId + "/transition")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toStatus\":\"PENDING\"}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/tasks/" + taskId + "/transition")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toStatus\":\"PENDING\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"));

        // 本人任务列表
        mockMvc.perform(get("/api/organizations/" + orgId + "/my-tasks").session(member.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void materialLifecycle() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()));
        Long memberMemberId = memberIdOf(orgId, member.username());

        // 普通成员不能创建物资
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/materials")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"横幅\",\"quantity\":2,\"unit\":\"条\"}"))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/materials")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"name\":\"横幅\",\"quantity\":2,\"unit\":\"条\","
                    + "\"assigneeMemberId\":%d,\"note\":\"含支架\"}").formatted(memberMemberId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"));

        MvcResult list = mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId + "/materials")
                .session(member.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andReturn();
        Long materialId = Long.parseLong(com.jayway.jsonpath.JsonPath.read(
            list.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.items[0].materialId"));

        // 非法状态 → 409
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/materials/" + materialId + "/transition")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toStatus\":\"PENDING\"}"))
            .andExpect(status().isConflict());

        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/materials/" + materialId + "/transition")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toStatus\":\"READY\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    void attachmentUploadDownloadDeleteAndRejection() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, member.username()));

        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
        MvcResult upload = mockMvc.perform(multipart(
                    "/api/organizations/" + orgId + "/activities/" + activityId + "/attachments")
                .file(new MockMultipartFile("file", "海报.png", "image/png", png))
                .with(csrf()).session(owner.session()))
            .andExpect(status().isCreated())
            .andReturn();
        Long attachmentId = Long.parseLong(com.jayway.jsonpath.JsonPath.read(
            upload.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.attachmentId"));

        // 伪装扩展名拒绝：txt 内容声称 png
        mockMvc.perform(multipart("/api/organizations/" + orgId + "/activities/" + activityId + "/attachments")
                .file(new MockMultipartFile("file", "假图.png", "image/png",
                    "this is not a png".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .with(csrf()).session(owner.session()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("ATTACHMENT_INVALID"));

        // 成员下载（有效成员可下载）
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId + "/attachments/" + attachmentId + "/download")
                .session(member.session()))
            .andExpect(status().isOk());

        // 上传者删除
        mockMvc.perform(delete("/api/organizations/" + orgId + "/activities/" + activityId + "/attachments/" + attachmentId)
                .with(csrf()).session(owner.session()))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId + "/attachments/" + attachmentId + "/download")
                .session(member.session()))
            .andExpect(status().isNotFound());
    }

    @Test
    void leaveBlockedByOpenDuties() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()));
        Long memberMemberId = memberIdOf(orgId, member.username());

        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/tasks")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"未结任务\",\"assigneeMemberId\":%d}").formatted(memberMemberId)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .andExpect(status().isCreated());

        // 有未完成任务时退出 → 409（P1.4 联动）
        mockMvc.perform(delete("/api/organizations/" + orgId + "/members/me")
                .with(csrf()).session(member.session()))
            .andExpect(status().isConflict());

        // OWNER 改派给他人后，成员可退出
        Long otherMemberId = memberIdOf(orgId, owner.username());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/tasks/"
                + taskIdOf(orgId, member.username()) + "/update")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"未结任务\",\"assigneeMemberId\":%d,\"priority\":\"MEDIUM\"}")
                    .formatted(otherMemberId)))
            .andExpect(status().isOk());
        mockMvc.perform(delete("/api/organizations/" + orgId + "/members/me")
                .with(csrf()).session(member.session()))
            .andExpect(status().isNoContent());
    }

    private Long taskIdOf(Long orgId, String username) {
        return jdbcTemplate.queryForObject(
            "SELECT t.id FROM task t JOIN account a ON a.id = t.created_by JOIN organization_member m "
                + "ON m.id = t.assignee_member_id JOIN account ma ON ma.id = m.account_id "
                + "WHERE t.org_id = ? AND ma.username = ? AND t.status = 'PENDING'",
            Long.class, orgId, username);
    }
}
