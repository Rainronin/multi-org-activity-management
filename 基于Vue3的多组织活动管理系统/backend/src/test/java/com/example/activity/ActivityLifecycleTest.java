package com.example.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.activity.support.FlowTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

/**
 * P6 生命周期回归：启动／结束执行与执行记录、活动取消与遗留处理、反馈资格与汇总、
 * 人工总结与来源变化、归档条件与归档后只读、重新打开。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ActivityLifecycleTest extends FlowTestSupport {

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.example.activity.common.audit.AuditService auditService;

    private static final byte[] PNG = new byte[] {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    private String path(Long orgId, Long activityId) {
        return "/api/organizations/" + orgId + "/activities/" + activityId;
    }

    private java.util.Map<String, Object> auditState(Long activityId) {
        var row = jdbcTemplate.queryForMap("SELECT status, version, owner_member_id, registration_open, "
            + "summary_confirmed_at IS NOT NULL AS summary_confirmed, reopen_count FROM activity WHERE id = ?", activityId);
        return java.util.Map.of("status", row.get("status"), "version", ((Number) row.get("version")).longValue(),
            "ownerMemberId", ((Number) row.get("owner_member_id")).longValue(),
            "registrationOpen", row.get("registration_open"), "summaryConfirmed", Boolean.TRUE.equals(row.get("summary_confirmed"))
                || row.get("summary_confirmed") instanceof Number flag && flag.intValue() != 0,
            "reopenCount", ((Number) row.get("reopen_count")).intValue());
    }

    private void assertActivityAudit(Long orgId, Long activityId, String action,
        java.util.Map<String, Object> before, java.util.Map<String, Object> after) throws Exception {
        var row = jdbcTemplate.queryForMap("SELECT detail, request_id FROM audit_log WHERE target = ? AND action = ? ORDER BY id DESC LIMIT 1",
            "activity:" + activityId, action);
        var detail = new com.fasterxml.jackson.databind.ObjectMapper().readTree((String) row.get("detail"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(detail.get("before")).as(action + " 的变更前事实").isEqualTo(mapper.readTree(mapper.writeValueAsString(before)));
        assertThat(detail.get("after")).as(action + " 的已提交事实").isEqualTo(mapper.readTree(mapper.writeValueAsString(after)));
        assertThat(detail.get("orgId").asLong()).isEqualTo(orgId);
        assertThat((String) row.get("request_id")).matches("[0-9a-f-]{36}");
        assertThat((String) row.get("detail")).doesNotContain("审计正文排除样本");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void auditFailureRollsBackCancellationOrConfirmationAndAllowsOriginalRetry(boolean cancelled) throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()), "2027-08-11T14:00:00+08:00");
        String endpoint = path(orgId, activityId);
        Long registrationId = null;
        Long taskId = null;
        if (cancelled) {
            TestSession member = registerAndLogin();
            inviteAndAccept(owner, member, orgId);
            openRegistration(owner, orgId, activityId);
            applyAs(member, activityId, 201);
            registrationId = jdbcTemplate.queryForObject("SELECT id FROM registration WHERE activity_id = ?", Long.class, activityId);
            taskId = Long.parseLong(readField(mockMvc.perform(post(endpoint + "/tasks").with(csrf()).session(owner.session())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"事务回滚任务\",\"assigneeMemberId\":"
                        + memberIdOf(orgId, member.username()) + "}"))
                .andExpect(status().isCreated()).andReturn(), "$.taskId"));
        } else {
            startExecution(owner, orgId, activityId, 200);
            finishExecution(owner, orgId, activityId, 200);
            mockMvc.perform(put(endpoint + "/summary").with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                    .content(versioned(activityId, "{\"summary\":\"事务回滚核对\"}"))).andExpect(status().isOk());
        }
        var before = auditState(activityId);
        long auditCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target = ?", Long.class, "activity:" + activityId);
        long snapshots = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM activity_confirmation WHERE activity_id = ?", Long.class, activityId);
        long notifications = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE activity_id = ?", Long.class, activityId);
        Long actorId = jdbcTemplate.queryForObject("SELECT id FROM account WHERE username = ?", Long.class, owner.username());
        String action = cancelled ? "ACTIVITY_CANCELLED" : "ACTIVITY_SUMMARY_CONFIRMED";
        String suffix = cancelled ? "/cancel" : "/summary/confirm";
        String body = versioned(activityId, cancelled ? "{\"reason\":\"场地关闭\"}" : "{}");
        doThrow(new IllegalStateException("测试审计写入失败")).when(auditService)
            .record(eq(actorId), eq("ACTIVITY"), eq(action), eq("activity:" + activityId), nullable(String.class), any());
        mockMvc.perform(post(endpoint + suffix).with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isInternalServerError());
        assertThat(auditState(activityId)).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target = ?", Long.class, "activity:" + activityId)).isEqualTo(auditCount);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM activity_confirmation WHERE activity_id = ?", Long.class, activityId)).isEqualTo(snapshots);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE activity_id = ?", Long.class, activityId)).isEqualTo(notifications);
        if (cancelled) {
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM registration WHERE id = ?", String.class, registrationId)).isEqualTo("PENDING");
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM task WHERE id = ?", String.class, taskId)).isEqualTo("PENDING");
        }
        doCallRealMethod().when(auditService)
            .record(eq(actorId), eq("ACTIVITY"), eq(action), eq("activity:" + activityId), nullable(String.class), any());
        mockMvc.perform(post(endpoint + suffix).with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());
        assertActivityAudit(orgId, activityId, action, before, auditState(activityId));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target = ?", Long.class, "activity:" + activityId)).isEqualTo(auditCount + 1);
        if (cancelled) {
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM registration WHERE id = ?", String.class, registrationId)).isEqualTo("CANCELLED_ORG");
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM task WHERE id = ?", String.class, taskId)).isEqualTo("CANCELLED");
        } else {
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM activity_confirmation WHERE activity_id = ?", Long.class, activityId)).isEqualTo(snapshots + 1);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lifecycleAuditsRecordCommittedStatesAndVersions(boolean cancelled) throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-08-09T14:00:00+08:00");
        String endpoint = path(orgId, activityId);
        var before = auditState(activityId);
        if (cancelled) {
            mockMvc.perform(post(endpoint + "/cancel").with(csrf()).session(owner.session())
                    .contentType(MediaType.APPLICATION_JSON).content(versioned(activityId, "{\"reason\":\"场地关闭\"}")))
                .andExpect(status().isOk());
            assertActivityAudit(orgId, activityId, "ACTIVITY_CANCELLED", before, auditState(activityId));
            mockMvc.perform(post(endpoint + "/cancel").with(csrf()).session(owner.session())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"迟到重试\",\"version\":" + before.get("version") + "}"))
                .andExpect(status().isOk());
            assertActivityAudit(orgId, activityId, "ACTIVITY_CANCELLED", before, auditState(activityId));
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target = ? AND action = 'ACTIVITY_CANCELLED'",
                Long.class, "activity:" + activityId)).isEqualTo(1);
        } else {
            startExecution(owner, orgId, activityId, 200);
            assertActivityAudit(orgId, activityId, "ACTIVITY_EXECUTION_STARTED", before, auditState(activityId));
            before = auditState(activityId);
            finishExecution(owner, orgId, activityId, 200);
            assertActivityAudit(orgId, activityId, "ACTIVITY_EXECUTION_FINISHED", before, auditState(activityId));
        }
        before = auditState(activityId);
        mockMvc.perform(post(endpoint + "/execution-records").header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"HANDLING\",\"content\":\"审计正文排除样本\"}"))
            .andExpect(status().isCreated());
        assertActivityAudit(orgId, activityId, "EXECUTION_RECORD_ADDED", before, auditState(activityId));
        assertThat(jdbcTemplate.queryForObject("SELECT JSON_EXTRACT(detail, '$.recordId') FROM audit_log WHERE target = ? AND action = 'EXECUTION_RECORD_ADDED'",
            String.class, "activity:" + activityId)).isNotNull();
        before = auditState(activityId);
        mockMvc.perform(put(endpoint + "/summary").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(versioned(activityId, "{\"summary\":\"审计正文排除样本\"}")))
            .andExpect(status().isOk());
        assertActivityAudit(orgId, activityId, "ACTIVITY_SUMMARY_SAVED", before, auditState(activityId));
        before = auditState(activityId);
        mockMvc.perform(post(endpoint + "/summary/confirm").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(versioned(activityId, "{}")))
            .andExpect(status().isOk());
        assertActivityAudit(orgId, activityId, "ACTIVITY_SUMMARY_CONFIRMED", before, auditState(activityId));
        before = auditState(activityId);
        archive(owner, orgId, activityId, 200);
        var archived = auditState(activityId);
        assertActivityAudit(orgId, activityId, "ACTIVITY_ARCHIVED", before, archived);
        mockMvc.perform(post(endpoint + "/archive").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + before.get("version") + "}"))
            .andExpect(status().isOk());
        assertActivityAudit(orgId, activityId, "ACTIVITY_ARCHIVED", before, archived);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target = ? AND action = 'ACTIVITY_ARCHIVED'",
            Long.class, "activity:" + activityId)).isEqualTo(1);
        mockMvc.perform(post(endpoint + "/reopen").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(versioned(activityId, "{\"reason\":\"需要补正\"}")))
            .andExpect(status().isOk());
        assertActivityAudit(orgId, activityId, "ACTIVITY_REOPENED", archived, auditState(activityId));
        long auditCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target = ?", Long.class, "activity:" + activityId);
        var unchanged = auditState(activityId);
        mockMvc.perform(put(endpoint + "/summary").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"summary\":\"过期正文\",\"version\":" + archived.get("version") + "}"))
            .andExpect(status().isConflict());
        assertThat(auditState(activityId)).isEqualTo(unchanged);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target = ?", Long.class, "activity:" + activityId)).isEqualTo(auditCount);
    }

    @Test
    void draftAndApprovalAuditsUseActualVersionsIncludingTwoUpdates() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        String body = activityBody("审计核对活动", memberIdOf(orgId, owner.username()), "2027-08-10T14:00:00+08:00");
        MvcResult created = mockMvc.perform(post("/api/organizations/" + orgId + "/activities").header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn();
        Long activityId = Long.parseLong(readField(created, "$.id"));
        String endpoint = path(orgId, activityId);
        assertActivityAudit(orgId, activityId, "ACTIVITY_DRAFT_CREATED", null, auditState(activityId));
        var before = auditState(activityId);
        mockMvc.perform(patch(endpoint).with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"ownerMemberId\":" + memberIdOf(orgId, member.username()) + ",\"description\":\"审计正文排除样本\"}")))
            .andExpect(status().isOk());
        assertActivityAudit(orgId, activityId, "ACTIVITY_DRAFT_UPDATED", before, auditState(activityId));
        before = auditState(activityId);
        mockMvc.perform(put(endpoint + "/proposal").with(csrf()).session(member.session()).contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"content\":\"审计正文排除样本\"}")))
            .andExpect(status().isOk());
        assertActivityAudit(orgId, activityId, "ACTIVITY_PROPOSAL_SAVED", before, auditState(activityId));
        before = auditState(activityId);
        mockMvc.perform(post(endpoint + "/submit").with(csrf()).session(member.session())).andExpect(status().isOk());
        assertActivityAudit(orgId, activityId, "ACTIVITY_SUBMITTED", before, auditState(activityId));
        before = auditState(activityId);
        mockMvc.perform(post(endpoint + "/withdraw").with(csrf()).session(member.session())).andExpect(status().isOk());
        assertActivityAudit(orgId, activityId, "ACTIVITY_WITHDRAWN", before, auditState(activityId));
        mockMvc.perform(post(endpoint + "/submit").with(csrf()).session(member.session())).andExpect(status().isOk());
        before = auditState(activityId);
        mockMvc.perform(post(endpoint + "/reject").with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"reason\":\"请完善安排\"}")))
            .andExpect(status().isOk());
        assertActivityAudit(orgId, activityId, "ACTIVITY_REJECTED", before, auditState(activityId));
        mockMvc.perform(post(endpoint + "/submit").with(csrf()).session(member.session())).andExpect(status().isOk());
        before = auditState(activityId);
        mockMvc.perform(post(endpoint + "/approve").with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{}"))).andExpect(status().isOk());
        assertActivityAudit(orgId, activityId, "ACTIVITY_APPROVED", before, auditState(activityId));
        assertThat(jdbcTemplate.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(detail, '$.confirmType')) FROM audit_log WHERE target = ? AND action = 'ACTIVITY_APPROVED'",
            String.class, "activity:" + activityId)).isEqualTo("OTHER");
        before = auditState(activityId);
        openRegistration(owner, orgId, activityId);
        assertActivityAudit(orgId, activityId, "ACTIVITY_REGISTRATION_OPENED", before, auditState(activityId));
        before = auditState(activityId);
        mockMvc.perform(post(endpoint + "/withdraw-approval").with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"reason\":\"改期\"}"))).andExpect(status().isOk());
        assertThat(versionOf(activityId)).isEqualTo((long) before.get("version") + 2);
        assertActivityAudit(orgId, activityId, "ACTIVITY_APPROVAL_WITHDRAWN", before, auditState(activityId));
        submitForApproval(owner, orgId, activityId);
        before = auditState(activityId);
        mockMvc.perform(post(endpoint + "/registration-open").with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"open\":false}")).andExpect(status().isOk());
        assertActivityAudit(orgId, activityId, "ACTIVITY_REGISTRATION_CLOSED", before, auditState(activityId));
        MvcResult disposable = mockMvc.perform(post("/api/organizations/" + orgId + "/activities").header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn();
        Long deletedId = Long.parseLong(readField(disposable, "$.id"));
        before = auditState(deletedId);
        mockMvc.perform(delete(path(orgId, deletedId)).with(csrf()).session(owner.session())).andExpect(status().isNoContent());
        assertActivityAudit(orgId, deletedId, "ACTIVITY_DRAFT_DELETED", before, null);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unpublishedActivitiesCanBeCancelledWithoutBecomingVisible(boolean pending) throws Exception {
        TestSession owner = registerAndLogin();
        TestSession assignee = registerAndLogin();
        TestSession other = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, assignee, orgId);
        inviteAndAccept(owner, other, orgId);
        String name = "未发布取消-" + System.nanoTime();
        MvcResult created = mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .with(csrf()).session(owner.session()).header("Idempotency-Key", newIdempotencyKey())
                .contentType(MediaType.APPLICATION_JSON).content(activityBody(name,
                    memberIdOf(orgId, assignee.username()), "2027-10-08T14:00:00+08:00").replace("INTERNAL", "PUBLIC")
                    .replace("\"capacity\":10", "\"capacity\":10,\"registrationStart\":\"2027-10-01T10:00:00+08:00\","
                        + "\"registrationEnd\":\"2027-10-08T13:00:00+08:00\"")))
            .andExpect(status().isCreated()).andReturn();
        Long activityId = Long.parseLong(readField(created, "$.id"));
        String endpoint = path(orgId, activityId);
        mockMvc.perform(put(endpoint + "/proposal").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(versioned(activityId, "{\"content\":\"未发布方案\"}")))
            .andExpect(status().isOk());
        if (pending) {
            mockMvc.perform(post(endpoint + "/submit").with(csrf()).session(owner.session()))
                .andExpect(status().isOk());
        }
        long version = versionOf(activityId);
        mockMvc.perform(post(endpoint + "/cancel").with(csrf()).session(assignee.session())
                .contentType(MediaType.APPLICATION_JSON).content(versioned(activityId, "{\"reason\":\"负责人申请取消\"}")))
            .andExpect(status().isForbidden());
        mockMvc.perform(post(endpoint + "/cancel").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(versioned(activityId, "{\"reason\":\" \"}")))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post(endpoint + "/cancel").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"取消\",\"version\":" + (version + 10) + "}"))
            .andExpect(status().isConflict());
        mockMvc.perform(post(endpoint + "/cancel").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(versioned(activityId, "{\"reason\":\" 初期取消 \"}")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancelReason").value("初期取消"))
            .andExpect(jsonPath("$.registrationOpen").value(false))
            .andExpect(jsonPath("$.confirmedAt").doesNotExist())
            .andExpect(jsonPath("$.proposal").value("未发布方案"));
        assertThat(versionOf(activityId)).isEqualTo(version + 1);
        mockMvc.perform(post(endpoint + "/cancel").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"重试\",\"version\":" + version + "}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.cancelReason").value("初期取消"));
        assertThat(versionOf(activityId)).isEqualTo(version + 1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target = ? AND action = 'ACTIVITY_CANCELLED'",
            Long.class, "activity:" + activityId)).isEqualTo(1);
        mockMvc.perform(get(endpoint).session(assignee.session())).andExpect(status().isOk());
        for (String suffix : new String[] {"", "/tasks", "/materials", "/expenses", "/execution-records", "/feedback", "/confirmations"}) {
            mockMvc.perform(get(endpoint + suffix).session(other.session())).andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities").session(other.session())
                .param("status", "CANCELLED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get("/api/public/activities/" + activityId)).andExpect(status().isNotFound());
        mockMvc.perform(put(endpoint + "/summary").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(versioned(activityId, "{\"summary\":\"初期取消，无遗留事项\"}")))
            .andExpect(status().isOk());
        mockMvc.perform(post(endpoint + "/summary/confirm").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(versioned(activityId, "{}")))
            .andExpect(status().isOk());
        mockMvc.perform(post(endpoint + "/archive").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(versioned(activityId, "{}")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
        mockMvc.perform(get(endpoint).session(other.session())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities").session(other.session())
                .param("status", "ARCHIVED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get("/api/public/activities/" + activityId)).andExpect(status().isNotFound());
    }

    /** 带预算的已确认活动：费用确认会按预算判断是否要求超支说明，归档场景需要非零预算。 */
    private Long createApprovedActivityWithBudget(
        TestSession owner, Long orgId, Long ownerMemberId, String startTime, String budget) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"name\":\"生命周期核对活动\",\"ownerMemberId\":%d,\"description\":\"简介\","
                    + "\"startTime\":\"%s\",\"endTime\":\"%s\",\"location\":\"礼堂\","
                    + "\"registrationScope\":\"INTERNAL\",\"capacity\":10,\"budget\":\"%s\"}")
                    .formatted(ownerMemberId, startTime, startTime.replace("T14:00", "T16:00"), budget)))
            .andExpect(status().isCreated())
            .andReturn();
        Long activityId = Long.parseLong(readField(created, "$.id"));
        submitForApproval(owner, orgId, activityId);
        return activityId;
    }

    private long versionOf(Long activityId) {
        return jdbcTemplate.queryForObject("SELECT version FROM activity WHERE id = ?", Long.class, activityId);
    }

    private String statusOf(Long activityId) {
        return jdbcTemplate.queryForObject("SELECT status FROM activity WHERE id = ?", String.class, activityId);
    }

    private void applyAs(TestSession actor, Long activityId, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/activities/" + activityId + "/registrations")
                .with(csrf()).session(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"%s\"}".formatted(actor.username())))
            .andExpect(status().is(expectedStatus));
    }

    private String approvedRegistration(TestSession owner, Long orgId, Long activityId, Long registrationId)
        throws Exception {
        mockMvc.perform(post(path(orgId, activityId) + "/registrations/" + registrationId + "/approve")
                .with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content(registrationVersioned(registrationId, "{}")))
            .andExpect(status().isOk());
        return String.valueOf(registrationId);
    }

    private void startExecution(TestSession actor, Long orgId, Long activityId, int expectedStatus)
        throws Exception {
        mockMvc.perform(post(path(orgId, activityId) + "/start-execution")
                .with(csrf()).session(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + versionOf(activityId) + "}"))
            .andExpect(status().is(expectedStatus));
    }

    private void finishExecution(TestSession actor, Long orgId, Long activityId, int expectedStatus)
        throws Exception {
        mockMvc.perform(post(path(orgId, activityId) + "/finish-execution")
                .with(csrf()).session(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + versionOf(activityId) + "}"))
            .andExpect(status().is(expectedStatus));
    }

    private void archive(TestSession actor, Long orgId, Long activityId, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post(path(orgId, activityId) + "/archive")
                .with(csrf()).session(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + versionOf(activityId) + "}"))
            .andReturn();
        // 归档条件不满足时把后端给出的阻塞原因带进断言消息，便于定位
        assertThat(result.getResponse().getStatus())
            .as("归档响应：%s", result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo(expectedStatus);
    }

    private void saveAndConfirmSummary(TestSession actor, Long orgId, Long activityId) throws Exception {
        mockMvc.perform(put(path(orgId, activityId) + "/summary")
                .with(csrf()).session(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"summary\":\"目标达成情况与问题改进项：由张三跟进物料回收。\",\"version\":"
                    + versionOf(activityId) + "}"))
            .andExpect(status().isOk());
        mockMvc.perform(post(path(orgId, activityId) + "/summary/confirm")
                .with(csrf()).session(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + versionOf(activityId) + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.summaryConfirmedAt").isNotEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void confirmationSnapshotsKeepContentAcrossReopenAndCorrection(boolean cancelled) throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        TestSession outsider = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-08-08T14:00:00+08:00");
        String endpoint = path(orgId, activityId);
        // 确认正文独立于当前草稿；读取历史仍校验当前权限。
        mockMvc.perform(get(endpoint + "/confirmations").session(member.session()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].kind").value("PROPOSAL"))
            .andExpect(jsonPath("$.items[0].content").value("方案"))
            .andExpect(jsonPath("$.items[0].id").isString())
            .andExpect(jsonPath("$.items[0].confirmedBy").isString())
            .andExpect(jsonPath("$.items[0].activityVersion").isNumber());
        mockMvc.perform(get(endpoint + "/confirmations")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(endpoint + "/confirmations").session(outsider.session()))
            .andExpect(status().isNotFound());
        Long anotherOrg = createOrg(owner);
        mockMvc.perform(get(path(anotherOrg, activityId) + "/confirmations").session(owner.session()))
            .andExpect(status().isNotFound());
        if (cancelled) {
            mockMvc.perform(post(endpoint + "/cancel").with(csrf()).session(owner.session())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(versioned(activityId, "{\"reason\":\"场地不可用\"}")))
                .andExpect(status().isOk());
        } else {
            startExecution(owner, orgId, activityId, 200);
            finishExecution(owner, orgId, activityId, 200);
        }
        saveAndConfirmSummary(owner, orgId, activityId);
        long confirmedVersion = versionOf(activityId);
        mockMvc.perform(post(endpoint + "/summary/confirm").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + (confirmedVersion - 1) + "}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EDIT_CONFLICT"));
        archive(owner, orgId, activityId, 200);
        mockMvc.perform(post(endpoint + "/reopen").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"reason\":\"补充更正依据\"}")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(cancelled ? "CANCELLED" : "REVIEWING"))
            .andExpect(jsonPath("$.summaryConfirmedAt").isEmpty())
            .andExpect(jsonPath("$.registrationOpen").value(false));
        archive(owner, orgId, activityId, 409);
        mockMvc.perform(put(endpoint + "/summary").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"summary\":\"第二次正文：已补充更正依据\"}")))
            .andExpect(status().isOk());
        mockMvc.perform(get(endpoint + "/confirmations").session(owner.session()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.items[0].content").value("目标达成情况与问题改进项：由张三跟进物料回收。"))
            .andExpect(jsonPath("$.items[0].kind").value(cancelled ? "CANCEL_NOTE" : "SUMMARY"));
        mockMvc.perform(post(endpoint + "/summary/confirm").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(versioned(activityId, "{}")))
            .andExpect(status().isOk());
        archive(owner, orgId, activityId, 200);
        mockMvc.perform(get(endpoint + "/confirmations?pageSize=1").session(owner.session()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].content").value("第二次正文：已补充更正依据"))
            .andExpect(jsonPath("$.items[0].kind").value(cancelled ? "CANCEL_NOTE" : "SUMMARY"))
            .andExpect(jsonPath("$.items[0].reopenReason").value("补充更正依据"))
            .andExpect(jsonPath("$.items[0].reopenCount").value(1))
            .andExpect(jsonPath("$.items[0].sourceHash").isNotEmpty())
            .andExpect(jsonPath("$.items[0].confirmedByUsername").value(owner.username()));
        mockMvc.perform(delete("/api/organizations/" + orgId + "/members/me").with(csrf()).session(member.session()))
            .andExpect(status().isNoContent());
        mockMvc.perform(get(endpoint + "/confirmations").session(member.session()))
            .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"tasks", "materials", "expenses"})
    void sourceVersionChangesRequireReviewEvenWhenStatisticsStayTheSame(String resource) throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long memberId = memberIdOf(orgId, owner.username());
        Long activityId = createApprovedActivity(owner, orgId, memberId, "2027-08-09T14:00:00+08:00");
        startExecution(owner, orgId, activityId, 200);
        finishExecution(owner, orgId, activityId, 200);
        String body = switch (resource) {
            case "tasks" -> "{\"title\":\"原任务\",\"assigneeMemberId\":" + memberId + "}";
            case "materials" -> "{\"name\":\"原物资\",\"quantity\":1,\"unit\":\"份\"}";
            default -> "{\"purpose\":\"原费用\",\"amount\":\"10.00\",\"incurredOn\":\"2027-08-09\","
                + "\"category\":\"OTHER\",\"noReceiptNote\":\"无票说明\"}";
        };
        MvcResult created = mockMvc.perform(post(path(orgId, activityId) + "/" + resource)
                .header("Idempotency-Key", newIdempotencyKey()).with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn();
        String field = switch (resource) {
            case "tasks" -> "taskId";
            case "materials" -> "materialId";
            default -> "expenseId";
        };
        String id = readField(created, "$." + field);
        saveAndConfirmSummary(owner, orgId, activityId);
        mockMvc.perform(get(path(orgId, activityId)).session(owner.session()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.summaryNeedsReview").value(false));
        String changed = body.replace("原", "更正");
        changed = changed.substring(0, changed.length() - 1) + ",\"version\":1}";
        mockMvc.perform(post(path(orgId, activityId) + "/" + resource + "/" + id + "/update")
                .with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON).content(changed))
            .andExpect(status().isOk());
        mockMvc.perform(get(path(orgId, activityId)).session(owner.session()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.summaryNeedsReview").value(true));
        mockMvc.perform(post(path(orgId, activityId) + "/archive").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(versioned(activityId, "{}")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("来源已变化")));
        saveAndConfirmSummary(owner, orgId, activityId);
        mockMvc.perform(get(path(orgId, activityId)).session(owner.session()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.summaryNeedsReview").value(false));
    }

    @Test
    void unreadyMaterialRequiresDispositionBeforeNormalArchive() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long memberId = memberIdOf(orgId, owner.username());
        Long activityId = createApprovedActivity(owner, orgId, memberId, "2027-08-10T14:00:00+08:00");
        MvcResult created = mockMvc.perform(post(path(orgId, activityId) + "/materials").with(csrf())
                .session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"待归还物资\",\"quantity\":1,\"unit\":\"件\"}"))
            .andExpect(status().isCreated()).andReturn();
        String materialId = readField(created, "$.materialId");
        startExecution(owner, orgId, activityId, 200);
        finishExecution(owner, orgId, activityId, 200);
        saveAndConfirmSummary(owner, orgId, activityId);
        mockMvc.perform(post(path(orgId, activityId) + "/archive").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(versioned(activityId, "{}")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("未处置物资")));
        mockMvc.perform(post(path(orgId, activityId) + "/materials/" + materialId + "/update").with(csrf())
                .session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"待归还物资\",\"quantity\":1,\"unit\":\"件\",\"assigneeMemberId\":"
                    + memberId + ",\"note\":\"活动后次日归还，负责人负责核对交接\",\"version\":1}"))
            .andExpect(status().isOk());
        mockMvc.perform(put(path(orgId, activityId) + "/summary").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(versioned(activityId,
                    "{\"summary\":\"活动结束，待归还物资由" + owner.username() + "负责次日归还并核对交接。\"}")))
            .andExpect(status().isOk());
        mockMvc.perform(post(path(orgId, activityId) + "/summary/confirm").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(versioned(activityId, "{}")))
            .andExpect(status().isOk());
        archive(owner, orgId, activityId, 200);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM material WHERE id = ?", String.class, materialId))
            .isEqualTo("PENDING"); // 归档不伪造物资已就绪。
    }

    @Test
    void unreadyMaterialCannotKeepFormerMemberAsDispositionOwner() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-08-10T14:00:00+08:00");
        MvcResult created = mockMvc.perform(post(path(orgId, activityId) + "/materials").with(csrf())
                .session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"待归还物资\",\"quantity\":1,\"unit\":\"件\",\"assigneeMemberId\":"
                    + memberIdOf(orgId, member.username()) + ",\"note\":\"次日由原负责人归还\"}"))
            .andExpect(status().isCreated()).andReturn();
        String materialPath = path(orgId, activityId) + "/materials/" + readField(created, "$.materialId");
        mockMvc.perform(post(materialPath + "/transition").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"toStatus\":\"READY\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(delete("/api/organizations/" + orgId + "/members/me").with(csrf()).session(member.session()))
            .andExpect(status().isNoContent());
        mockMvc.perform(post(materialPath + "/transition").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"toStatus\":\"PENDING\"}"))
            .andExpect(status().isOk());
        startExecution(owner, orgId, activityId, 200);
        finishExecution(owner, orgId, activityId, 200);
        saveAndConfirmSummary(owner, orgId, activityId);
        mockMvc.perform(post(path(orgId, activityId) + "/archive").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(versioned(activityId, "{}")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ARCHIVE_BLOCKED"))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("未处置物资")));
        assertThat(statusOf(activityId)).isEqualTo("REVIEWING");
    }

    @Test
    void executionRecordKeyReplaysOnceAndRejectsChangedContent() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-09-05T14:00:00+08:00");
        startExecution(owner, orgId, activityId, 200);
        String key = newIdempotencyKey();
        String body = "{\"category\":\"PROGRESS\",\"content\":\"执行记录核对\"}";
        MvcResult created = mockMvc.perform(post(path(orgId, activityId) + "/execution-records")
                .header("Idempotency-Key", key).with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn();
        String recordId = readField(created, "$.recordId");
        mockMvc.perform(post(path(orgId, activityId) + "/execution-records")
                .header("Idempotency-Key", key).with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.recordId").value(recordId));
        mockMvc.perform(post(path(orgId, activityId) + "/execution-records")
                .header("Idempotency-Key", key).with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(body.replace("执行记录核对", "另一个内容")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM execution_record WHERE activity_id = ?",
            Integer.class, activityId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'EXECUTION_RECORD_ADDED' "
            + "AND target = ?", Integer.class, "activity:" + activityId)).isEqualTo(1);
        finishExecution(owner, orgId, activityId, 200);
        saveAndConfirmSummary(owner, orgId, activityId);
        archive(owner, orgId, activityId, 200);
        mockMvc.perform(post(path(orgId, activityId) + "/execution-records")
                .header("Idempotency-Key", key).with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.recordId").value(recordId));
        mockMvc.perform(post(path(orgId, activityId) + "/execution-records")
                .header("Idempotency-Key", newIdempotencyKey()).with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACTIVITY_STATE_CONFLICT"));
    }

    @Test
    void executionRecordReplayStillChecksActorScopeAndCurrentPermission() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long memberId = memberIdOf(orgId, member.username());
        Long activityId = createApprovedActivity(owner, orgId, memberId, "2027-09-05T14:00:00+08:00");
        startExecution(owner, orgId, activityId, 200);
        String key = newIdempotencyKey();
        String body = "{\"category\":\"PROGRESS\",\"content\":\"按原操作者核对\"}";
        mockMvc.perform(post(path(orgId, activityId) + "/execution-records")
                .header("Idempotency-Key", key).with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mockMvc.perform(post(path(orgId, activityId) + "/execution-records")
                .header("Idempotency-Key", key).with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        Long otherActivity = createApprovedActivity(owner, orgId, memberId, "2027-09-06T14:00:00+08:00");
        startExecution(owner, orgId, otherActivity, 200);
        mockMvc.perform(post(path(orgId, otherActivity) + "/execution-records")
                .header("Idempotency-Key", key).with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        for (String invalid : new String[] { "", "x".repeat(65) }) {
            mockMvc.perform(post(path(orgId, activityId) + "/execution-records")
                    .header("Idempotency-Key", invalid).with(csrf()).session(owner.session())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/organizations/" + orgId + "/members/" + memberId + "/promote-owner")
                .with(csrf()).session(owner.session())).andExpect(status().isOk());
        mockMvc.perform(post("/api/organizations/" + orgId + "/members/" + memberIdOf(orgId, owner.username())
                + "/revoke-owner").with(csrf()).session(member.session())).andExpect(status().isOk());
        mockMvc.perform(post(path(orgId, activityId) + "/execution-records")
                .header("Idempotency-Key", key).with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM execution_record WHERE activity_id = ?",
            Integer.class, activityId)).isEqualTo(1);
    }

    @Test
    void concurrentExecutionRecordsRespectKeysAndReturnTheirOwnRecords() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-09-05T14:00:00+08:00");
        startExecution(owner, orgId, activityId, 200);
        String key = newIdempotencyKey();
        var start = new java.util.concurrent.CountDownLatch(1);
        var results = new java.util.ArrayList<java.util.concurrent.Future<MvcResult>>();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(6)) {
            for (int i = 0; i < 6; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(post(path(orgId, activityId) + "/execution-records")
                            .header("Idempotency-Key", key).with(csrf()).session(owner.session())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"category\":\"PROGRESS\",\"content\":\"并发同键\"}"))
                        .andReturn();
                }));
            }
            start.countDown();
            for (var result : results) {
                MvcResult response = result.get(20, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(response.getResponse().getStatus()).isIn(201, 409);
                if (response.getResponse().getStatus() == 409) {
                    assertThat(readField(response, "$.code")).isEqualTo("REQUEST_IN_PROGRESS");
                }
            }
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM execution_record WHERE activity_id = ?",
                Integer.class, activityId)).isEqualTo(1);
            results.clear();
            for (int i = 0; i < 6; i++) {
                String content = "并发不同键" + i;
                results.add(pool.submit(() -> mockMvc.perform(post(path(orgId, activityId) + "/execution-records")
                        .header("Idempotency-Key", newIdempotencyKey()).with(csrf()).session(owner.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"ISSUE\",\"content\":\"" + content + "\"}"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.content").value(content)).andReturn()));
            }
            for (var result : results) result.get(20, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM execution_record WHERE activity_id = ?",
            Integer.class, activityId)).isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'EXECUTION_RECORD_ADDED' "
            + "AND target = ?", Integer.class, "activity:" + activityId)).isEqualTo(7);
    }

    @Test
    void concurrentFirstFeedbackReturnsConflictInsteadOfServerError() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-09-05T14:00:00+08:00");
        startExecution(owner, orgId, activityId, 200);
        finishExecution(owner, orgId, activityId, 200);
        var start = new java.util.concurrent.CountDownLatch(1);
        var results = new java.util.ArrayList<java.util.concurrent.Future<MvcResult>>();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(post(path(orgId, activityId) + "/feedback")
                            .with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rating\":5,\"comment\":\"并发首次提交\",\"version\":0}"))
                        .andReturn();
                }));
            }
            start.countDown();
            for (var result : results) {
                var response = result.get(20, java.util.concurrent.TimeUnit.SECONDS).getResponse();
                assertThat(response.getStatus()).isIn(200, 409);
                if (response.getStatus() == 409) {
                    assertThat(response.getContentAsString()).containsAnyOf("FEEDBACK_CONFLICT", "EDIT_CONFLICT");
                }
            }
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM feedback WHERE activity_id = ?",
            Integer.class, activityId)).isEqualTo(1);
    }

    @Test
    void startExecutionRequiresHandledRegistrationsAndFreezesSelfService() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-08-01T14:00:00+08:00");
        openRegistration(owner, orgId, activityId);
        applyAs(member, activityId, 201);
        Long registrationId = jdbcTemplate.queryForObject(
            "SELECT id FROM registration WHERE activity_id = ? AND account_id = (SELECT account_id FROM "
                + "organization_member WHERE org_id = ? AND id = ?)",
            Long.class, activityId, orgId, memberIdOf(orgId, member.username()));

        // 仍有待审报名：拒绝启动，并指出剩余条数
        mockMvc.perform(post(path(orgId, activityId) + "/start-execution")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + versionOf(activityId) + "}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("REGISTRATION_PENDING_REMAINS"));
        assertThat(statusOf(activityId)).isEqualTo("APPROVED");

        approvedRegistration(owner, orgId, activityId, registrationId);
        startExecution(owner, orgId, activityId, 200);
        assertThat(statusOf(activityId)).isEqualTo("EXECUTING");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT started_at IS NOT NULL FROM activity WHERE id = ?", Boolean.class, activityId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM feedback_eligibility WHERE activity_id = ? AND source = 'REGISTRATION_APPROVED'",
            Integer.class, activityId)).isEqualTo(1);

        // 启动执行后停止新报名与自助取消（不生成到场记录，也不靠报名开关自动跳转）
        applyAs(registerAndLogin(), activityId, 404);
        mockMvc.perform(post("/api/activities/" + activityId + "/registrations/cancel-self")
                .with(csrf()).session(member.session()).contentType(MediaType.APPLICATION_JSON)
                .content(registrationForAccountVersioned(activityId, member.username(), "{}")))
            .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM registration WHERE activity_id = ? AND status = 'APPROVED'",
            Integer.class, activityId)).isEqualTo(1);

        // 执行期仍可按权限维护任务与实际费用
        mockMvc.perform(post(path(orgId, activityId) + "/tasks")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"执行期任务\",\"assigneeMemberId\":%d}")
                    .formatted(memberIdOf(orgId, owner.username()))))
            .andExpect(status().isCreated());
        mockMvc.perform(post(path(orgId, activityId) + "/expenses")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"执行期支出\",\"amount\":10.00,\"incurredOn\":\"2027-08-01\","
                    + "\"category\":\"OTHER\",\"noReceiptNote\":\"无票说明\"}"))
            .andExpect(status().isCreated());
    }

    @Test
    void executionRecordsAreManagerOnlyAndAppendOnly() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-08-02T14:00:00+08:00");
        startExecution(owner, orgId, activityId, 200);

        String record = "{\"category\":\"PROGRESS\",\"content\":\"上午完成场地布置\"}";
        // 普通成员不能填写执行记录
        mockMvc.perform(post(path(orgId, activityId) + "/execution-records")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON).content(record))
            .andExpect(status().isForbidden());
        mockMvc.perform(post(path(orgId, activityId) + "/execution-records")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(record))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.category").value("PROGRESS"));
        mockMvc.perform(post(path(orgId, activityId) + "/execution-records")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"DEVIATION\",\"content\":\"实际到场比预告少 3 人，已调整座位\"}"))
            .andExpect(status().isCreated());
        mockMvc.perform(get(path(orgId, activityId) + "/execution-records")
                .session(member.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2));

        // 未知类型被拒
        mockMvc.perform(post(path(orgId, activityId) + "/execution-records")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"UNKNOWN\",\"content\":\"x\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void feedbackFollowsSnapshotEligibilityAndStopsAtArchive() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        TestSession other = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        inviteAndAccept(owner, other, orgId);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-08-03T14:00:00+08:00");
        openRegistration(owner, orgId, activityId);
        applyAs(member, activityId, 201);
        Long registrationId = jdbcTemplate.queryForObject(
            "SELECT id FROM registration WHERE activity_id = ?", Long.class, activityId);
        approvedRegistration(owner, orgId, activityId, registrationId);
        startExecution(owner, orgId, activityId, 200);

        // 执行中尚未进入复盘：反馈未开放
        mockMvc.perform(post(path(orgId, activityId) + "/feedback")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":5,\"comment\":\"不错\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("FEEDBACK_CLOSED"));

        finishExecution(owner, orgId, activityId, 200);
        assertThat(statusOf(activityId)).isEqualTo("REVIEWING");

        // 有资格的报名者可以提交并修改，每账号一份
        mockMvc.perform(post(path(orgId, activityId) + "/feedback")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":5,\"comment\":\"流程顺畅\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rating").value(5));
        mockMvc.perform(post(path(orgId, activityId) + "/feedback")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(feedbackVersioned(activityId, member.username(), "{\"rating\":3,\"comment\":\"场地指示可以更清楚\"}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rating").value(3));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM feedback WHERE activity_id = ?", Integer.class, activityId)).isEqualTo(1);

        // 无资格成员被拒
        mockMvc.perform(post(path(orgId, activityId) + "/feedback")
                .with(csrf()).session(other.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":4,\"comment\":\"路过\"}"))
            .andExpect(status().isForbidden());

        // 汇总：份数与平均分；无权限成员只看到自己的一份
        mockMvc.perform(get(path(orgId, activityId) + "/feedback").session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.averageRating").value("3.0"))
            .andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(get(path(orgId, activityId) + "/feedback").session(other.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.items.length()").value(0))
            .andExpect(jsonPath("$.canSubmit").value(false));

        // 归档后反馈永久关闭
        saveAndConfirmSummary(owner, orgId, activityId);
        archive(owner, orgId, activityId, 200);
        mockMvc.perform(post(path(orgId, activityId) + "/feedback")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":1,\"comment\":\"归档后修改\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("FEEDBACK_CLOSED"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT rating FROM feedback WHERE activity_id = ?", Integer.class, activityId)).isEqualTo(3);

        // 重新打开不恢复反馈入口
        mockMvc.perform(post(path(orgId, activityId) + "/reopen")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"需要补充改进项\",\"version\":" + versionOf(activityId) + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REVIEWING"))
            .andExpect(jsonPath("$.summaryConfirmedAt").isEmpty());
        archive(owner, orgId, activityId, 409);
        mockMvc.perform(post(path(orgId, activityId) + "/feedback")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":2,\"comment\":\"重开后\"}"))
            .andExpect(status().isConflict());
    }

    @Test
    void cancelClosesRegistrationsTasksAndNotifiesAffectedAccounts() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        TestSession other = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        inviteAndAccept(owner, other, orgId);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-08-04T14:00:00+08:00");
        openRegistration(owner, orgId, activityId);
        applyAs(member, activityId, 201);
        applyAs(other, activityId, 201);
        Long approved = jdbcTemplate.queryForObject(
            "SELECT id FROM registration WHERE activity_id = ? ORDER BY id LIMIT 1", Long.class, activityId);
        approvedRegistration(owner, orgId, activityId, approved);
        mockMvc.perform(post(path(orgId, activityId) + "/tasks")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"取消前任务\",\"assigneeMemberId\":%d}")
                    .formatted(memberIdOf(orgId, member.username()))))
            .andExpect(status().isCreated());

        // 活动负责人不能最终确认取消
        mockMvc.perform(post(path(orgId, activityId) + "/cancel")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"场地不可用\",\"version\":" + versionOf(activityId) + "}"))
            .andExpect(status().isForbidden());
        // 缺少原因被拒
        mockMvc.perform(post(path(orgId, activityId) + "/cancel")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\" \",\"version\":" + versionOf(activityId) + "}"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post(path(orgId, activityId) + "/cancel")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"场地不可用\",\"version\":" + versionOf(activityId) + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancelReason").value("场地不可用"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM registration WHERE activity_id = ? AND status IN ('PENDING','APPROVED')",
            Integer.class, activityId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM task WHERE activity_id = ? AND status IN ('PENDING','IN_PROGRESS')",
            Integer.class, activityId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notification WHERE activity_id = ? AND type = 'ACTIVITY_CANCELLED'",
            Integer.class, activityId)).isEqualTo(2);
        // 取消后不再接受报名，重复取消不产生第二次业务结果
        applyAs(registerAndLogin(), activityId, 404);
        mockMvc.perform(post(path(orgId, activityId) + "/cancel")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"再次取消\",\"version\":" + versionOf(activityId) + "}"))
            .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notification WHERE activity_id = ? AND type = 'ACTIVITY_CANCELLED'",
            Integer.class, activityId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT cancel_reason FROM activity WHERE id = ?", String.class, activityId))
            .isEqualTo("场地不可用");
    }

    @Test
    void archiveRequiresSummaryAndClosesAllWrites() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivityWithBudget(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-08-05T14:00:00+08:00", "100.00");
        startExecution(owner, orgId, activityId, 200);
        finishExecution(owner, orgId, activityId, 200);

        // 未确认总结：拒绝归档
        archive(owner, orgId, activityId, 409);
        // 待确认费用：拒绝归档
        mockMvc.perform(post(path(orgId, activityId) + "/expenses")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"待确认支出\",\"amount\":20.00,\"incurredOn\":\"2027-08-05\","
                    + "\"category\":\"OTHER\",\"noReceiptNote\":\"无票说明\"}"))
            .andExpect(status().isCreated());
        String expenseId = String.valueOf(jdbcTemplate.queryForObject(
            "SELECT id FROM expense WHERE activity_id = ?", Long.class, activityId));
        mockMvc.perform(post(path(orgId, activityId) + "/expenses/" + expenseId + "/submit")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1}"))
            .andExpect(status().isOk());
        Long expenseVersion = jdbcTemplate.queryForObject(
            "SELECT version FROM expense WHERE id = ?", Long.class, Long.parseLong(expenseId));
        mockMvc.perform(post(path(orgId, activityId) + "/expenses/" + expenseId + "/confirm")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + expenseVersion + "}"))
            .andExpect(status().isOk());

        saveAndConfirmSummary(owner, orgId, activityId);
        // 未结束任务：拒绝归档
        MvcResult task = mockMvc.perform(post(path(orgId, activityId) + "/tasks")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"未结束任务\",\"assigneeMemberId\":%d}")
                    .formatted(memberIdOf(orgId, owner.username()))))
            .andExpect(status().isCreated())
            .andReturn();
        String taskId = readField(task, "$.taskId");
        mockMvc.perform(post(path(orgId, activityId) + "/archive")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + versionOf(activityId) + "}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("未结束任务")));

        // 取消任务后归档成功
        mockMvc.perform(post(path(orgId, activityId) + "/tasks/" + taskId + "/transition")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toStatus\":\"CANCELLED\"}"))
            .andExpect(status().isOk());
        // 任务变化属于总结来源变化：重新确认后再归档（计划书 6.4）
        saveAndConfirmSummary(owner, orgId, activityId);
        archive(owner, orgId, activityId, 200);
        assertThat(statusOf(activityId)).isEqualTo("ARCHIVED");

        // 归档后普通写入全部关闭
        long taskVersion = jdbcTemplate.queryForObject("SELECT version FROM task WHERE id = ?", Long.class, taskId);
        mockMvc.perform(post(path(orgId, activityId) + "/tasks/" + taskId + "/transition")
                .with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"toStatus\":\"PENDING\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACTIVITY_STATE_CONFLICT"));
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM task WHERE id = ?", Long.class, taskId))
            .isEqualTo(taskVersion);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM task WHERE id = ?", String.class, taskId))
            .isEqualTo("CANCELLED");
        mockMvc.perform(post(path(orgId, activityId) + "/tasks")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"归档后任务\",\"assigneeMemberId\":%d}")
                    .formatted(memberIdOf(orgId, owner.username()))))
            .andExpect(status().isConflict());
        mockMvc.perform(multipart(path(orgId, activityId) + "/attachments")
                .file(new MockMultipartFile("file", "归档后资料.png", "image/png", PNG))
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session()))
            .andExpect(status().isConflict());
        mockMvc.perform(post(path(orgId, activityId) + "/expenses")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"归档后支出\",\"amount\":5.00,\"incurredOn\":\"2027-08-05\","
                    + "\"category\":\"OTHER\",\"noReceiptNote\":\"无票说明\"}"))
            .andExpect(status().isConflict());
        mockMvc.perform(post(path(orgId, activityId) + "/execution-records")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"PROGRESS\",\"content\":\"归档后补记\"}"))
            .andExpect(status().isConflict());
    }

    @Test
    void summarySourceChangeBlocksArchiveUntilReconfirmed() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-08-06T14:00:00+08:00");
        startExecution(owner, orgId, activityId, 200);
        finishExecution(owner, orgId, activityId, 200);
        mockMvc.perform(post(path(orgId, activityId) + "/feedback").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":5,\"comment\":\"原建议\"}"))
            .andExpect(status().isOk());
        saveAndConfirmSummary(owner, orgId, activityId);

        // 评分与份数不变，文字建议更正也应使已确认来源失效。
        mockMvc.perform(post(path(orgId, activityId) + "/feedback").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(feedbackVersioned(activityId, owner.username(),
                    "{\"rating\":5,\"comment\":\"更正建议\"}")))
            .andExpect(status().isOk());
        mockMvc.perform(post(path(orgId, activityId) + "/archive").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(versioned(activityId, "{}")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("来源已变化")));
        saveAndConfirmSummary(owner, orgId, activityId);

        // 确认后新增任务：来源变化，归档被拒并要求重新核对
        MvcResult task = mockMvc.perform(post(path(orgId, activityId) + "/tasks")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"总结后新增任务\",\"assigneeMemberId\":%d}")
                    .formatted(memberIdOf(orgId, member.username()))))
            .andExpect(status().isCreated())
            .andReturn();
        String taskId = readField(task, "$.taskId");
        mockMvc.perform(post(path(orgId, activityId) + "/archive")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + versionOf(activityId) + "}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("来源已变化")));

        mockMvc.perform(post(path(orgId, activityId) + "/tasks/" + taskId + "/transition")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toStatus\":\"CANCELLED\"}"))
            .andExpect(status().isOk());
        saveAndConfirmSummary(owner, orgId, activityId);
        archive(owner, orgId, activityId, 200);
    }

    @Test
    void cancelledActivityArchivesWithLeftoversAndKeepsHistory() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-08-07T14:00:00+08:00");
        startExecution(owner, orgId, activityId, 200);
        finishExecution(owner, orgId, activityId, 200);
        // 取消前保留一份反馈作为历史
        mockMvc.perform(post(path(orgId, activityId) + "/feedback")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":4,\"comment\":\"执行中的真实记录\"}"))
            .andExpect(status().isOk());

        mockMvc.perform(post(path(orgId, activityId) + "/cancel")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"天气原因取消\",\"version\":" + versionOf(activityId) + "}"))
            .andExpect(status().isOk());
        assertThat(statusOf(activityId)).isEqualTo("CANCELLED");

        // 取消后保留历史反馈且不再收集；取消说明可作为总结内容
        mockMvc.perform(get(path(orgId, activityId) + "/feedback").session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.canSubmit").value(false));
        mockMvc.perform(post(path(orgId, activityId) + "/feedback")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":1,\"comment\":\"取消后补\"}"))
            .andExpect(status().isConflict());
        // 取消后仍可处理遗留费用
        mockMvc.perform(post(path(orgId, activityId) + "/expenses")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"取消遗留费用\",\"amount\":15.00,\"incurredOn\":\"2027-08-07\","
                    + "\"category\":\"OTHER\",\"noReceiptNote\":\"无票说明\"}"))
            .andExpect(status().isCreated());

        saveAndConfirmSummary(owner, orgId, activityId);
        archive(owner, orgId, activityId, 200);
        assertThat(statusOf(activityId)).isEqualTo("ARCHIVED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM feedback WHERE activity_id = ?", Integer.class, activityId)).isEqualTo(1);
        mockMvc.perform(post(path(orgId, activityId) + "/reopen")
                .with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"reason\":\"补充取消遗留处置\"}")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void voucherOfConfirmedExpenseCannotBeDeletedAndArchivedDocsStayReadOnly() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivityWithBudget(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-08-08T14:00:00+08:00", "100.00");

        MvcResult activityDoc = mockMvc.perform(multipart(path(orgId, activityId) + "/attachments")
                .file(new MockMultipartFile("file", "活动资料.png", "image/png", PNG))
                .header("Idempotency-Key", newIdempotencyKey()).with(csrf()).session(owner.session()))
            .andExpect(status().isCreated()).andReturn();
        MvcResult task = mockMvc.perform(post(path(orgId, activityId) + "/tasks")
                .header("Idempotency-Key", newIdempotencyKey()).with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"资料归档核对\",\"assigneeMemberId\":%d}")
                    .formatted(memberIdOf(orgId, owner.username()))))
            .andExpect(status().isCreated()).andReturn();
        String taskId = readField(task, "$.taskId");
        MvcResult taskDoc = mockMvc.perform(multipart(path(orgId, activityId) + "/tasks/" + taskId + "/attachments")
                .file(new MockMultipartFile("file", "任务资料.png", "image/png", PNG))
                .header("Idempotency-Key", newIdempotencyKey()).with(csrf()).session(owner.session()))
            .andExpect(status().isCreated()).andReturn();
        var ordinaryDocIds = java.util.List.of(readField(activityDoc, "$.attachmentId"),
            readField(taskDoc, "$.attachmentId"));

        MvcResult expense = mockMvc.perform(post(path(orgId, activityId) + "/expenses")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"票据核对\",\"amount\":30.00,\"incurredOn\":\"2027-08-08\","
                    + "\"category\":\"MATERIAL\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        String expenseId = readField(expense, "$.expenseId");
        MvcResult voucher = mockMvc.perform(multipart(path(orgId, activityId) + "/expenses/" + expenseId + "/attachments")
                .file(new MockMultipartFile("file", "收据.png", "image/png", PNG))
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session()))
            .andExpect(status().isCreated())
            .andReturn();
        String attachmentId = readField(voucher, "$.attachmentId");

        // 未确认费用的票据可以删除
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete(path(orgId, activityId) + "/attachments/" + attachmentId)
                .with(csrf()).session(owner.session()))
            .andExpect(status().isNoContent());

        // 重新上传票据并确认费用后，票据不可删除
        MvcResult voucher2 = mockMvc.perform(multipart(path(orgId, activityId) + "/expenses/" + expenseId + "/attachments")
                .file(new MockMultipartFile("file", "收据2.png", "image/png", PNG))
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session()))
            .andExpect(status().isCreated())
            .andReturn();
        String keptId = readField(voucher2, "$.attachmentId");
        mockMvc.perform(post(path(orgId, activityId) + "/expenses/" + expenseId + "/submit")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1}"))
            .andExpect(status().isOk());
        Long expenseVersion = jdbcTemplate.queryForObject(
            "SELECT version FROM expense WHERE id = ?", Long.class, Long.parseLong(expenseId));
        mockMvc.perform(post(path(orgId, activityId) + "/expenses/" + expenseId + "/confirm")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + expenseVersion + "}"))
            .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete(path(orgId, activityId) + "/attachments/" + keptId)
                .with(csrf()).session(owner.session()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("已确认")));

        mockMvc.perform(post(path(orgId, activityId) + "/tasks/" + taskId + "/transition")
                .with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"toStatus\":\"CANCELLED\"}"))
            .andExpect(status().isOk());
        // 归档后普通活动资料、任务资料和已确认票据都不能直接删除。
        startExecution(owner, orgId, activityId, 200);
        finishExecution(owner, orgId, activityId, 200);
        saveAndConfirmSummary(owner, orgId, activityId);
        archive(owner, orgId, activityId, 200);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete(path(orgId, activityId) + "/attachments/" + keptId)
                .with(csrf()).session(owner.session()))
            .andExpect(status().isConflict());
        for (String docId : ordinaryDocIds) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .delete(path(orgId, activityId) + "/attachments/" + docId)
                    .with(csrf()).session(owner.session()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACTIVITY_STATE_CONFLICT"));
            MvcResult download = mockMvc.perform(get(path(orgId, activityId) + "/attachments/" + docId + "/download")
                    .session(owner.session()))
                .andExpect(status().isOk()).andReturn();
            assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(PNG);
            assertThat(jdbcTemplate.queryForObject("SELECT deleted_at IS NULL FROM attachment WHERE id = ?",
                Boolean.class, docId)).isTrue();
        }
        // 重新打开回到复盘，但票据仍属已确认费用，仍不可删除
        mockMvc.perform(post(path(orgId, activityId) + "/reopen")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"补充改进项\",\"version\":" + versionOf(activityId) + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REVIEWING"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete(path(orgId, activityId) + "/attachments/" + keptId)
                .with(csrf()).session(owner.session()))
            .andExpect(status().isConflict());
        // 正常资料可在授权重开后删除，已确认票据仍按原费用保留。
        for (String docId : ordinaryDocIds) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .delete(path(orgId, activityId) + "/attachments/" + docId)
                    .with(csrf()).session(owner.session()))
                .andExpect(status().isNoContent());
            mockMvc.perform(get(path(orgId, activityId) + "/attachments/" + docId + "/download")
                    .session(owner.session()))
                .andExpect(status().isNotFound());
        }
    }

    @Test
    void executionAndArchiveRejectOrdinaryActivityPatch() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-09-10T14:00:00+08:00");
        startExecution(owner, orgId, activityId, 200);
        assertActivityPatchRejected(owner, orgId, activityId);
        finishExecution(owner, orgId, activityId, 200);
        assertActivityPatchRejected(owner, orgId, activityId);
        saveAndConfirmSummary(owner, orgId, activityId);
        archive(owner, orgId, activityId, 200);
        assertActivityPatchRejected(owner, orgId, activityId);
    }

    private void assertActivityPatchRejected(TestSession actor, Long orgId, Long activityId) throws Exception {
        long version = versionOf(activityId);
        String name = jdbcTemplate.queryForObject("SELECT name FROM activity WHERE id = ?", String.class, activityId);
        mockMvc.perform(patch(path(orgId, activityId)).with(csrf()).session(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"name\":\"不应保存\"}")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ACTIVITY_STATE_CONFLICT"));
        assertThat(versionOf(activityId)).isEqualTo(version);
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM activity WHERE id = ?", String.class, activityId))
            .isEqualTo(name);
    }

    @Test
    void archivedMaterialsRejectUpdateAndTransition() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-09-11T14:00:00+08:00");
        MvcResult created = mockMvc.perform(post(path(orgId, activityId) + "/materials")
                .with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"物料\",\"quantity\":3,\"unit\":\"份\"}"))
            .andExpect(status().isCreated()).andReturn();
        String materialId = readField(created, "$.materialId");
        String materialPath = path(orgId, activityId) + "/materials/" + materialId;
        mockMvc.perform(post(materialPath + "/transition").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"toStatus\":\"READY\"}"))
            .andExpect(status().isOk());
        startExecution(owner, orgId, activityId, 200);
        // 执行期仍允许维护物资，避免把修复扩大成过度禁止。
        Long version = jdbcTemplate.queryForObject("SELECT version FROM material WHERE id = ?", Long.class, materialId);
        mockMvc.perform(post(materialPath + "/update").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"物料\",\"quantity\":4,\"unit\":\"份\",\"version\":" + version + "}"))
            .andExpect(status().isOk());
        finishExecution(owner, orgId, activityId, 200);
        saveAndConfirmSummary(owner, orgId, activityId);
        archive(owner, orgId, activityId, 200);
        Long archivedVersion = jdbcTemplate.queryForObject("SELECT version FROM material WHERE id = ?", Long.class, materialId);
        mockMvc.perform(post(materialPath + "/update").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"不应保存\",\"quantity\":99,\"unit\":\"份\",\"version\":" + archivedVersion + "}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACTIVITY_STATE_CONFLICT"));
        mockMvc.perform(post(materialPath + "/transition").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"toStatus\":\"PENDING\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACTIVITY_STATE_CONFLICT"));
        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM material WHERE id = ?", Integer.class, materialId)).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM material WHERE id = ?", String.class, materialId)).isEqualTo("READY");
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM material WHERE id = ?", Long.class, materialId)).isEqualTo(archivedVersion);
    }

    @Test
    void voucherDownloadRequiresManagerButOrdinaryDocumentsRemainReadable() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession manager = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, manager, orgId);
        inviteAndAccept(owner, member, orgId);
        Long activityId = createApprovedActivityWithBudget(owner, orgId, memberIdOf(orgId, manager.username()),
            "2027-09-12T14:00:00+08:00", "100.00");
        MvcResult expense = mockMvc.perform(post(path(orgId, activityId) + "/expenses")
                .header("Idempotency-Key", newIdempotencyKey()).with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"物料\",\"amount\":10.00,\"incurredOn\":\"2027-09-12\",\"category\":\"MATERIAL\"}"))
            .andExpect(status().isCreated()).andReturn();
        String receiptPath = path(orgId, activityId) + "/expenses/" + readField(expense, "$.expenseId") + "/attachments";
        MvcResult receipt = mockMvc.perform(multipart(receiptPath)
                .file(new MockMultipartFile("file", "receipt.png", "image/png", PNG))
                .header("Idempotency-Key", newIdempotencyKey()).with(csrf()).session(owner.session()))
            .andExpect(status().isCreated()).andReturn();
        String download = path(orgId, activityId) + "/attachments/" + readField(receipt, "$.attachmentId") + "/download";
        mockMvc.perform(get(receiptPath).session(member.session())).andExpect(status().isNotFound());
        mockMvc.perform(get(download).session(member.session())).andExpect(status().isNotFound());
        for (TestSession allowed : java.util.List.of(owner, manager)) {
            mockMvc.perform(get(receiptPath).session(allowed.session())).andExpect(status().isOk());
            MvcResult bytes = mockMvc.perform(get(download).session(allowed.session())).andExpect(status().isOk()).andReturn();
            assertThat(bytes.getResponse().getContentAsByteArray()).isEqualTo(PNG);
        }
        MvcResult document = mockMvc.perform(multipart(path(orgId, activityId) + "/attachments")
                .file(new MockMultipartFile("file", "arrangement.png", "image/png", PNG))
                .header("Idempotency-Key", newIdempotencyKey()).with(csrf()).session(owner.session()))
            .andExpect(status().isCreated()).andReturn();
        mockMvc.perform(get(path(orgId, activityId) + "/attachments/" + readField(document, "$.attachmentId") + "/download")
                .session(member.session())).andExpect(status().isOk());
    }
}
