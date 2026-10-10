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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @Autowired
    private javax.sql.DataSource dataSource;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.example.activity.common.audit.AuditService auditService;

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
                .header("Idempotency-Key", newIdempotencyKey())
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
                .content(versioned(activityId, "{\"content\":\"方案\"}")))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/submit")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/approve")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{}")))
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

    private java.util.Map<String, Object> preparationState(String kind, Long id) {
        var row = jdbcTemplate.queryForMap("SELECT * FROM " + kind + " WHERE id = ?", id);
        var state = new java.util.LinkedHashMap<String, Object>();
        state.put("status", row.get("status"));
        state.put("version", row.get("version"));
        state.put("assigneeMemberId", row.get("assignee_member_id"));
        if ("task".equals(kind)) {
            state.put("priority", row.get("priority"));
            state.put("completed", row.get("completed_at") != null);
        } else {
            state.put("quantity", row.get("quantity"));
            state.put("unit", row.get("unit"));
        }
        return state;
    }

    private void assertPreparationAudit(String kind, Long id, Long orgId, Long activityId, String action,
        java.util.Map<String, Object> before, java.util.Map<String, Object> after, MvcResult response) throws Exception {
        var row = jdbcTemplate.queryForMap("SELECT detail, request_id FROM audit_log WHERE target = ? AND action = ? ORDER BY id DESC LIMIT 1",
            kind + ":" + id, action);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var detail = mapper.readTree((String) row.get("detail"));
        assertThat(detail.has("before") && detail.has("after")).as(action + " 的前后字段存在").isTrue();
        assertThat(detail.get("before")).as(action + " 的变更前事实").isEqualTo(mapper.readTree(mapper.writeValueAsString(before)));
        assertThat(detail.get("after")).as(action + " 的变更后事实").isEqualTo(mapper.readTree(mapper.writeValueAsString(after)));
        assertThat(detail.get("orgId").asLong()).isEqualTo(orgId);
        assertThat(detail.get("activityId").asLong()).isEqualTo(activityId);
        assertThat(row.get("request_id")).isEqualTo(response.getResponse().getHeader("X-Request-Id"));
        assertThat((String) row.get("detail")).doesNotContain("审计正文排除样本");
    }

    @ParameterizedTest
    @ValueSource(strings = {"task", "material"})
    void preparationAuditsRecordAssignmentsAndActualVersions(String kind) throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long ownerMemberId = memberIdOf(orgId, owner.username());
        Long memberId = memberIdOf(orgId, member.username());
        Long activityId = createApprovedActivity(owner, orgId, ownerMemberId);
        String root = "/api/organizations/" + orgId + "/activities/" + activityId + "/" + kind + "s";
        String body = "task".equals(kind)
            ? "{\"title\":\"审计任务\",\"description\":\"审计正文排除样本\",\"assigneeMemberId\":" + ownerMemberId + "}"
            : "{\"name\":\"审计物资\",\"quantity\":2,\"unit\":\"件\",\"note\":\"审计正文排除样本\"}";
        String key = newIdempotencyKey();
        MvcResult created = mockMvc.perform(post(root).with(csrf()).session(owner.session()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
        Long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.getResponse().getContentAsString()).get(kind + "Id").asLong();
        var before = preparationState(kind, id);
        assertPreparationAudit(kind, id, orgId, activityId, kind.toUpperCase() + "_CREATED", null, before, created);
        mockMvc.perform(post(root).with(csrf()).session(owner.session()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target = ?", Long.class, kind + ":" + id)).isEqualTo(1L);
        String changed = "task".equals(kind)
            ? "{\"title\":\"改派任务\",\"description\":\"审计正文排除样本\",\"priority\":\"HIGH\",\"assigneeMemberId\":" + memberId + ",\"version\":1}"
            : "{\"name\":\"改派物资\",\"quantity\":3,\"unit\":\"套\",\"note\":\"审计正文排除样本\",\"assigneeMemberId\":" + memberId + ",\"version\":1}";
        MvcResult updated = mockMvc.perform(post(root + "/" + id + "/update").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(changed)).andExpect(status().isOk()).andReturn();
        assertPreparationAudit(kind, id, orgId, activityId, kind.toUpperCase() + "_UPDATED", before, preparationState(kind, id), updated);
        mockMvc.perform(post(root + "/" + id + "/update").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(changed)).andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target = ?", Long.class, kind + ":" + id)).isEqualTo(2L);
        for (String toStatus : "task".equals(kind) ? java.util.List.of("IN_PROGRESS", "COMPLETED", "PENDING", "CANCELLED")
            : java.util.List.of("IN_PROGRESS", "READY", "PENDING", "CANCELLED")) {
            before = preparationState(kind, id);
            boolean ownAdvance = "task".equals(kind) && ("IN_PROGRESS".equals(toStatus) || "COMPLETED".equals(toStatus));
            MvcResult result = mockMvc.perform(post(root + "/" + id + (ownAdvance ? "/advance" : "/transition"))
                    .with(csrf()).session(ownAdvance ? member.session() : owner.session()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"toStatus\":\"" + toStatus + "\"}")).andExpect(status().isOk()).andReturn();
            assertPreparationAudit(kind, id, orgId, activityId, kind.toUpperCase() + "_STATUS_" + toStatus,
                before, preparationState(kind, id), result);
        }
        assertThat(((Number) preparationState(kind, id).get("version")).longValue()).isEqualTo(6L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"task", "material"})
    void preparationAuditFailureRollsBackCreationAndOriginalKeyCanRetry(String kind) throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long ownerMemberId = memberIdOf(orgId, owner.username());
        Long activityId = createApprovedActivity(owner, orgId, ownerMemberId);
        String root = "/api/organizations/" + orgId + "/activities/" + activityId + "/" + kind + "s";
        String body = "task".equals(kind) ? "{\"title\":\"事务任务\",\"assigneeMemberId\":" + ownerMemberId + "}"
            : "{\"name\":\"事务物资\",\"quantity\":1,\"unit\":\"件\"}";
        String key = newIdempotencyKey();
        long notices = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE activity_id = ?", Long.class, activityId);
        org.mockito.Mockito.doThrow(new IllegalStateException("测试审计写入失败")).when(auditService).record(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("ACTIVITY"),
            org.mockito.ArgumentMatchers.eq(kind.toUpperCase() + "_CREATED"), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());
        mockMvc.perform(post(root).with(csrf()).session(owner.session()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isInternalServerError());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + kind + " WHERE org_id = ? AND request_key = ?", Long.class, orgId, key)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE activity_id = ?", Long.class, activityId)).isEqualTo(notices);
        org.mockito.Mockito.doCallRealMethod().when(auditService).record(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("ACTIVITY"),
            org.mockito.ArgumentMatchers.eq(kind.toUpperCase() + "_CREATED"), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());
        MvcResult retry = mockMvc.perform(post(root).with(csrf()).session(owner.session()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
        Long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(retry.getResponse().getContentAsString()).get(kind + "Id").asLong();
        assertPreparationAudit(kind, id, orgId, activityId, kind.toUpperCase() + "_CREATED", null, preparationState(kind, id), retry);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + kind + " WHERE org_id = ? AND request_key = ?", Long.class, orgId, key)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target = ?", Long.class, kind + ":" + id)).isEqualTo(1L);
        TestSession member = registerAndLogin();
        inviteAndAccept(owner, member, orgId);
        Long memberId = memberIdOf(orgId, member.username());
        String changed = "task".equals(kind) ? "{\"title\":\"事务改派\",\"assigneeMemberId\":" + memberId + ",\"version\":1}"
            : "{\"name\":\"事务改派\",\"quantity\":2,\"unit\":\"件\",\"assigneeMemberId\":" + memberId + ",\"version\":1}";
        var before = preparationState(kind, id);
        notices = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE activity_id = ?", Long.class, activityId);
        org.mockito.Mockito.doThrow(new IllegalStateException("测试审计写入失败")).when(auditService).record(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("ACTIVITY"),
            org.mockito.ArgumentMatchers.eq(kind.toUpperCase() + "_UPDATED"), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());
        mockMvc.perform(post(root + "/" + id + "/update").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(changed)).andExpect(status().isInternalServerError());
        assertThat(preparationState(kind, id)).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE activity_id = ?", Long.class, activityId)).isEqualTo(notices);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target = ?", Long.class, kind + ":" + id)).isEqualTo(1L);
        org.mockito.Mockito.doCallRealMethod().when(auditService).record(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("ACTIVITY"),
            org.mockito.ArgumentMatchers.eq(kind.toUpperCase() + "_UPDATED"), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());
        MvcResult updated = mockMvc.perform(post(root + "/" + id + "/update").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(changed)).andExpect(status().isOk()).andReturn();
        assertPreparationAudit(kind, id, orgId, activityId, kind.toUpperCase() + "_UPDATED", before, preparationState(kind, id), updated);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target = ?", Long.class, kind + ":" + id)).isEqualTo(2L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"task", "material"})
    void queuedPreparationWriteRechecksCommittedAssignmentOrState(String kind) throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long ownerMemberId = memberIdOf(orgId, owner.username());
        Long activityId = createApprovedActivity(owner, orgId, ownerMemberId);
        String root = "/api/organizations/" + orgId + "/activities/" + activityId + "/" + kind + "s";
        String body = "task".equals(kind) ? "{\"title\":\"排队任务\",\"assigneeMemberId\":" + memberIdOf(orgId, member.username()) + "}"
            : "{\"name\":\"排队物资\",\"quantity\":1,\"unit\":\"件\"}";
        MvcResult created = mockMvc.perform(post(root).with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isCreated()).andReturn();
        Long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.getResponse().getContentAsString()).get(kind + "Id").asLong();
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor(); var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT id FROM " + kind + " WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, id);
                try (var rows = lock.executeQuery()) { assertThat(rows.next()).isTrue(); }
            }
            var request = executor.submit(() -> mockMvc.perform(post(root + "/" + id + ("task".equals(kind) ? "/advance" : "/transition"))
                .with(csrf()).session("task".equals(kind) ? member.session() : owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content("task".equals(kind) ? "{\"toStatus\":\"COMPLETED\"}" : "{\"toStatus\":\"READY\"}" )).andReturn());
            boolean waiting = false;
            try {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline && !waiting) {
                    // 只查看本测试账号连接；FULL保留末尾id，不要求额外系统表权限。
                    waiting = jdbcTemplate.queryForList("SHOW FULL PROCESSLIST").stream().anyMatch(thread -> {
                        String sql = String.valueOf(thread.get("Info"));
                        return "Query".equals(thread.get("Command")) && sql.contains(id.toString())
                            && (sql.stripLeading().startsWith("UPDATE " + kind)
                                || sql.contains("FROM " + kind) && sql.contains("FOR UPDATE"));
                    });
                    if (!waiting) Thread.sleep(20);
                }
                assertThat(waiting).as("真实子记录锁等待").isTrue();
                try (var change = connection.prepareStatement("UPDATE " + kind + " SET "
                    + ("task".equals(kind) ? "assignee_member_id = ?" : "status = 'READY'") + ", version = version + 1 WHERE id = ?")) {
                    int index = 1;
                    if ("task".equals(kind)) change.setLong(index++, ownerMemberId);
                    change.setLong(index, id);
                    assertThat(change.executeUpdate()).isEqualTo(1);
                }
                connection.commit();
                var result = request.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).as("排队后须重验承担者／状态").isEqualTo("task".equals(kind) ? 403 : 409);
                assertThat(((Number) preparationState(kind, id).get("version")).longValue()).isEqualTo(2L);
                assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target = ?", Long.class, kind + ":" + id)).isEqualTo(1L);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void sameCreateKeyReturnsOriginalTaskAndMaterial() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long ownerMemberId = memberIdOf(orgId, owner.username());
        Long activityId = createApprovedActivity(owner, orgId, ownerMemberId);
        String root = "/api/organizations/" + orgId + "/activities/" + activityId;
        String taskBody = "{\"title\":\"防重任务\",\"assigneeMemberId\":" + ownerMemberId + "}";
        String taskKey = newIdempotencyKey();
        MvcResult task = mockMvc.perform(post(root + "/tasks").with(csrf()).session(owner.session())
                .header("Idempotency-Key", taskKey).contentType(MediaType.APPLICATION_JSON).content(taskBody))
            .andExpect(status().isCreated()).andReturn();
        String taskId = com.jayway.jsonpath.JsonPath.read(task.getResponse().getContentAsString(), "$.taskId");
        mockMvc.perform(post(root + "/tasks").with(csrf()).session(owner.session())
                .header("Idempotency-Key", taskKey).contentType(MediaType.APPLICATION_JSON).content(taskBody))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.taskId").value(taskId));
        mockMvc.perform(post(root + "/tasks").with(csrf()).session(owner.session())
                .header("Idempotency-Key", taskKey).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"不同任务\",\"assigneeMemberId\":" + ownerMemberId + "}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task WHERE activity_id = ?", Integer.class,
            activityId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE activity_id = ?", Integer.class,
            activityId)).isEqualTo(1);

        String materialBody = "{\"name\":\"防重物资\",\"quantity\":2,\"unit\":\"条\"}";
        String materialKey = newIdempotencyKey();
        MvcResult material = mockMvc.perform(post(root + "/materials").with(csrf()).session(owner.session())
                .header("Idempotency-Key", materialKey).contentType(MediaType.APPLICATION_JSON).content(materialBody))
            .andExpect(status().isCreated()).andReturn();
        String materialId = com.jayway.jsonpath.JsonPath.read(material.getResponse().getContentAsString(), "$.materialId");
        mockMvc.perform(post(root + "/materials").with(csrf()).session(owner.session())
                .header("Idempotency-Key", materialKey).contentType(MediaType.APPLICATION_JSON).content(materialBody))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.materialId").value(materialId));
        mockMvc.perform(post(root + "/materials").with(csrf()).session(owner.session())
                .header("Idempotency-Key", materialKey).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"不同物资\",\"quantity\":2,\"unit\":\"条\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM material WHERE activity_id = ?", Integer.class,
            activityId)).isEqualTo(1);
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

        // 分派成员推进：待处理可直接完成（S05，计划书 6.1）；成员不能取消或重新打开
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/tasks/" + taskId + "/advance")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toStatus\":\"COMPLETED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT completed_at IS NOT NULL FROM task WHERE id = ?", Boolean.class, taskId)).isTrue();

        // 成员自己也不能取消已完成的本人任务
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/tasks/" + taskId + "/advance")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toStatus\":\"CANCELLED\"}"))
            .andExpect(status().isConflict());

        // 成员不能取消或重新打开任务；OWNER 可重新打开
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
        assertThat(jdbcTemplate.queryForObject(
            "SELECT completed_at IS NULL FROM task WHERE id = ?", Boolean.class, taskId)).isTrue();

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
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session()))
            .andExpect(status().isCreated())
            .andReturn();
        Long attachmentId = Long.parseLong(com.jayway.jsonpath.JsonPath.read(
            upload.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.attachmentId"));

        // 伪装扩展名拒绝：txt 内容声称 png
        mockMvc.perform(multipart("/api/organizations/" + orgId + "/activities/" + activityId + "/attachments")
                .file(new MockMultipartFile("file", "假图.png", "image/png",
                    "this is not a png".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .header("Idempotency-Key", newIdempotencyKey())
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
                .content(taskVersioned(taskIdOf(orgId, member.username()),
                    ("{\"title\":\"未结任务\",\"assigneeMemberId\":%d,\"priority\":\"MEDIUM\"}")
                        .formatted(otherMemberId))))
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
