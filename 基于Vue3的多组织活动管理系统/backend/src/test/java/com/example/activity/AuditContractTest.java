package com.example.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.activity.support.FlowTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

/** 跨模块真实写入检查：前后状态与服务端请求号均来自同一笔业务事务。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AuditContractTest extends FlowTestSupport {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.example.activity.common.audit.AuditService auditService;

    @Test
    void capturedFeedbackEligibilityHasActorAndAffectedAccount() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession activityOwner = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, activityOwner, orgId);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, activityOwner.username()), "2027-10-10T14:00:00+08:00");
        String root = "/api/organizations/" + orgId + "/activities/" + activityId;
        for (String action : new String[] {"start-execution", "finish-execution"}) {
            mockMvc.perform(post(root + "/" + action).with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                    .content(versioned(activityId, "{}"))).andExpect(status().isOk());
        }
        Long actorId = jdbcTemplate.queryForObject("SELECT id FROM account WHERE username = ?", Long.class, owner.username());
        Long affectedId = jdbcTemplate.queryForObject("SELECT id FROM account WHERE username = ?", Long.class, activityOwner.username());
        var rows = jdbcTemplate.queryForList("SELECT actor_account_id, detail, request_id FROM audit_log WHERE action = 'FEEDBACK_ELIGIBILITY_CAPTURED' AND target = ?",
            "feedback-eligibility:" + activityId + ":" + affectedId + ":ACTIVITY_OWNER");
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.getFirst().get("actor_account_id")).longValue()).isEqualTo(actorId);
        assertThat(rows.getFirst().get("request_id")).isNotNull();
        var detail = new ObjectMapper().readTree(String.valueOf(rows.getFirst().get("detail")));
        assertThat(detail.path("before").isNull()).isTrue();
        assertThat(detail.path("after").path("accountId").asLong()).isEqualTo(affectedId);
        assertThat(detail.path("after").path("source").asText()).isEqualTo("ACTIVITY_OWNER");
    }

    @Test
    void eligibilityAuditFailureRollsBackExecutionFinish() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()), "2027-10-10T14:00:00+08:00");
        String root = "/api/organizations/" + orgId + "/activities/" + activityId;
        mockMvc.perform(post(root + "/start-execution").with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{}"))).andExpect(status().isOk());
        long version = activityVersion(activityId);
        failAudit("FEEDBACK_ELIGIBILITY_CAPTURED");
        mockMvc.perform(post(root + "/finish-execution").with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{}"))).andExpect(status().isInternalServerError());
        assertThat(activityVersion(activityId)).isEqualTo(version);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM activity WHERE id = ?", String.class, activityId)).isEqualTo("EXECUTING");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM feedback_eligibility WHERE activity_id = ?", Integer.class, activityId)).isZero();
    }

    private void failAudit(String action) {
        org.mockito.Mockito.doThrow(new IllegalStateException("测试审计故障"))
            .when(auditService).record(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(action), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class), org.mockito.ArgumentMatchers.any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER_PROMOTED", "EXPENSE_APPROVED"})
    void auditFailureRollsBackMembershipOrMoney(String action) throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long memberId = memberIdOf(orgId, member.username());
        if ("OWNER_PROMOTED".equals(action)) {
            failAudit(action);
            mockMvc.perform(post("/api/organizations/" + orgId + "/members/" + memberId + "/promote-owner")
                    .with(csrf()).session(owner.session())).andExpect(status().isInternalServerError());
            assertThat(jdbcTemplate.queryForObject("SELECT role FROM organization_member WHERE id = ?", String.class, memberId)).isEqualTo("MEMBER");
        } else {
            Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()), "2027-10-10T14:00:00+08:00");
            String path = "/api/organizations/" + orgId + "/activities/" + activityId + "/expenses";
            var created = mockMvc.perform(post(path).with(csrf()).session(owner.session()).header("Idempotency-Key", newIdempotencyKey())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"回滚核对\",\"amount\":\"10.00\",\"incurredOn\":\"2027-10-01\",\"category\":\"OTHER\",\"noReceiptNote\":\"无票\"}"))
                .andExpect(status().isCreated()).andReturn();
            String id = readField(created, "$.expenseId");
            mockMvc.perform(post(path + "/" + id + "/submit").with(csrf()).session(owner.session())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}")).andExpect(status().isOk());
            failAudit(action);
            mockMvc.perform(post(path + "/" + id + "/confirm").with(csrf()).session(owner.session())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"version\":2,\"overBudgetNote\":\"必要支出\"}"))
                .andExpect(status().isInternalServerError());
            var row = jdbcTemplate.queryForMap("SELECT status, version FROM expense WHERE id = ?", id);
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(((Number) row.get("version")).longValue()).isEqualTo(2L);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND target IN (?, ?)",
            Integer.class, action, "organization:" + orgId, "expense:" + jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM expense WHERE org_id = ?", Long.class, orgId))).isZero();
    }

    @Test
    void activityCancellationAuditFailureRollsBackEveryAffectedRow() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long ownerId = memberIdOf(orgId, owner.username());
        Long activityId = createApprovedActivity(owner, orgId, ownerId, "2027-10-10T14:00:00+08:00");
        String root = "/api/organizations/" + orgId + "/activities/" + activityId;
        openRegistration(owner, orgId, activityId);
        mockMvc.perform(post("/api/activities/" + activityId + "/registrations").with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"测试参与者\"}")).andExpect(status().isCreated());
        mockMvc.perform(post(root + "/tasks").with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"取消核对\",\"assigneeMemberId\":" + ownerId + "}")).andExpect(status().isCreated());
        long lower = jdbcTemplate.queryForObject("SELECT MAX(id) FROM audit_log", Long.class);
        failAudit("TASK_CANCELLED_WITH_ACTIVITY");
        mockMvc.perform(post(root + "/cancel").with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"reason\":\"回滚核对\"}"))).andExpect(status().isInternalServerError());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM activity WHERE id = ?", String.class, activityId)).isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM registration WHERE activity_id = ?", String.class, activityId)).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM task WHERE activity_id = ?", String.class, activityId)).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE id > ?", Integer.class, lower)).isZero();
    }

    @Test
    void uploadedTaskFileCanBeCheckedAfterReassignment() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession uploader = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, uploader, orgId);
        Long ownerId = memberIdOf(orgId, owner.username());
        Long activityId = createApprovedActivity(owner, orgId, ownerId, "2027-10-10T14:00:00+08:00");
        String root = "/api/organizations/" + orgId + "/activities/" + activityId;
        var task = mockMvc.perform(post(root + "/tasks").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"上传核对\",\"assigneeMemberId\":" + memberIdOf(orgId, uploader.username()) + "}"))
            .andExpect(status().isCreated()).andReturn();
        String taskId = readField(task, "$.taskId");
        String key = newIdempotencyKey();
        var file = new MockMultipartFile("file", "核对.txt", "text/plain", "测试资料".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var uploaded = mockMvc.perform(multipart(root + "/tasks/" + taskId + "/attachments").file(file)
                .with(csrf()).session(uploader.session()).header("Idempotency-Key", key))
            .andExpect(status().isCreated()).andReturn();
        mockMvc.perform(post(root + "/tasks/" + taskId + "/update").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"上传核对\",\"assigneeMemberId\":" + ownerId + ",\"version\":1}"))
            .andExpect(status().isOk());
        var result = mockMvc.perform(get(root + "/attachments/by-request-key").session(uploader.session()).param("requestKey", key))
            .andExpect(status().isOk()).andReturn();
        assertThat(readField(result, "$.attachmentId")).isEqualTo(readField(uploaded, "$.attachmentId"));
        mockMvc.perform(get(root + "/attachments/by-request-key").session(owner.session()).param("requestKey", key))
            .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING_APPROVAL", "CANCELLED", "ARCHIVED"})
    void materialOriginalKeyCanBeCheckedWhenActivityIsReadOnly(String state) throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()), "2027-10-10T14:00:00+08:00");
        String path = "/api/organizations/" + orgId + "/activities/" + activityId + "/materials";
        String body = "{\"name\":\"原物资\",\"quantity\":2,\"unit\":\"件\"}";
        String key = newIdempotencyKey();
        var created = mockMvc.perform(post(path).with(csrf()).session(owner.session())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn();
        jdbcTemplate.update("UPDATE activity SET status = ? WHERE id = ?", state, activityId);
        var replay = mockMvc.perform(post(path).with(csrf()).session(owner.session())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn();
        assertThat(readField(replay, "$.materialId")).isEqualTo(readField(created, "$.materialId"));
        mockMvc.perform(post(path).with(csrf()).session(owner.session())
                .header("Idempotency-Key", newIdempotencyKey()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM material WHERE activity_id = ?", Integer.class, activityId)).isEqualTo(1);
    }

    @Test
    void accountMembershipAndExpenseChangesHaveStateAndRequestId() throws Exception {
        long lowerId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM audit_log", Long.class);
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long memberId = memberIdOf(orgId, member.username());
        String members = "/api/organizations/" + orgId + "/members/";
        for (String action : new String[] {"promote-owner", "revoke-owner"}) {
            mockMvc.perform(post(members + memberId + "/" + action).with(csrf()).session(owner.session()))
                .andExpect(status().isOk());
        }
        mockMvc.perform(delete(members + memberId).with(csrf()).session(owner.session()))
            .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/auth/recovery/reissue").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"Passw0rd123\"}"))
            .andExpect(status().isOk());

        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-10-10T14:00:00+08:00");
        String path = "/api/organizations/" + orgId + "/activities/" + activityId + "/expenses";
        String body = "{\"purpose\":\"审计核对\",\"amount\":\"10.00\",\"incurredOn\":\"2027-10-01\","
            + "\"category\":\"OTHER\",\"noReceiptNote\":\"未取得凭证\"}";
        var created = mockMvc.perform(post(path).with(csrf()).session(owner.session())
                .header("Idempotency-Key", newIdempotencyKey()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn();
        String id = readField(created, "$.expenseId");
        for (String action : new String[] {"update", "submit", "reject", "update", "submit", "confirm", "void"}) {
            long version = jdbcTemplate.queryForObject("SELECT version FROM expense WHERE id = ?", Long.class, id);
            String payload = "update".equals(action) ? body.substring(0, body.length() - 1) + ",\"version\":" + version + "}"
                : "{\"version\":" + version + ",\"reason\":\"审计原因\",\"overBudgetNote\":\"必要开支\"}";
            mockMvc.perform(post(path + "/" + id + "/" + action).with(csrf()).session(owner.session())
                    .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk());
        }
        mockMvc.perform(post(path + "/" + id + "/correction").with(csrf()).session(owner.session())
                .header("Idempotency-Key", newIdempotencyKey()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
        long expected = 1;
        var json = new ObjectMapper();
        for (String detail : jdbcTemplate.queryForList("SELECT detail FROM audit_log WHERE target = ? ORDER BY id", String.class, "expense:" + id)) {
            var state = json.readTree(detail);
            assertThat(state.path("after").path("version").asLong()).isEqualTo(expected);
            if (expected == 1) assertThat(state.path("before").isNull()).isTrue();
            else assertThat(state.path("before").path("version").asLong()).isEqualTo(expected - 1);
            expected++;
        }
        assertThat(expected).isEqualTo(9);

        var missing = new ArrayList<String>();
        var mapper = new ObjectMapper();
        for (var row : jdbcTemplate.queryForList("SELECT action, detail, request_id FROM audit_log WHERE id > ? ORDER BY id", lowerId)) {
            var detail = mapper.readTree(String.valueOf(row.get("detail")));
            if (detail == null || !detail.has("before") || !detail.hasNonNull("after")
                || row.get("request_id") == null) missing.add(String.valueOf(row.get("action")));
            assertThat(String.valueOf(row.get("detail"))).doesNotContain("passwordHash", "codeHash", "apiKeyCiphertext");
        }
        assertThat(missing).as("缺失前后状态或请求号的实际关键写入").isEmpty();
    }
}
