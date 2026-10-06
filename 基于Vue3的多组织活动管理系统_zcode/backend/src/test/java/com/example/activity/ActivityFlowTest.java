package com.example.activity;

import org.assertj.core.api.Assertions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.activity.support.ActivityTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 活动草稿链路（P2.1）：OWNER 创建并指定负责人、草稿可见性、编辑权限、字段与时间校验、跨组织 404。
 * 身份经真实注册与登录建立（同既有测试约定）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ActivityFlowTest extends ActivityTestBase {

    private static final String PASSWORD = "Passw0rd123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record TestSession(String username, MockHttpSession session) {
    }

    private TestSession registerAndLogin() throws Exception {
        String username = "a" + Long.toString(System.nanoTime(), 36);
        mockMvc.perform(post("/api/auth/register").with(csrf())
                .with(request -> {
                    request.setRemoteAddr("10.%d.%d.11".formatted(
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
                .content("{\"name\":\"活动测试组织-%d\"}".formatted(System.nanoTime())))
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

    private Long memberIdOf(Long orgId, String username) {
        return jdbcTemplate.queryForObject(
            "SELECT m.id FROM organization_member m JOIN account a ON a.id = m.account_id "
                + "WHERE m.org_id = ? AND a.username = ? AND m.status = 'ACTIVE'",
            Long.class, orgId, username);
    }

    private MvcResult createDraft(TestSession creator, Long orgId, Long ownerMemberId, String body)
        throws Exception {
        return mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(creator.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.formatted(ownerMemberId)))
            .andReturn();
    }

    @Test
    void creationKeyCannotReplayAnotherOwnersActivity() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession otherOwner = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, otherOwner, orgId);
        mockMvc.perform(post("/api/organizations/" + orgId + "/members/"
                + memberIdOf(orgId, otherOwner.username()) + "/promote-owner")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isOk());
        String path = "/api/organizations/" + orgId + "/activities";
        String key = newIdempotencyKey();
        String body = "{\"name\":\"原创建意图\",\"ownerMemberId\":" + memberIdOf(orgId, owner.username()) + "}";
        MvcResult first = mockMvc.perform(post(path).with(csrf()).session(owner.session())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post(path).with(csrf()).session(owner.session())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));
        mockMvc.perform(post(path).with(csrf()).session(otherOwner.session())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        Assertions.assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM activity WHERE org_id = ?", Integer.class, orgId)).isEqualTo(1);
    }

    @Test
    void creationKeyDistinguishesMissingDescriptionFromLiteralNull() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        String path = "/api/organizations/" + orgId + "/activities";
        String key = newIdempotencyKey();
        String body = "{\"name\":\"原活动\",\"ownerMemberId\":" + memberIdOf(orgId, owner.username()) + "}";
        mockMvc.perform(post(path).with(csrf()).session(owner.session())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
        mockMvc.perform(post(path).with(csrf()).session(owner.session())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("}", ",\"description\":\"null\"}")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        Assertions.assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM activity WHERE org_id = ?",
            Integer.class, orgId)).isEqualTo(1);
    }

    @Test
    void ambiguousLegacyCreationKeyRequiresManualVerification() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long memberId = memberIdOf(orgId, owner.username());
        String path = "/api/organizations/" + orgId + "/activities";
        String key = newIdempotencyKey();
        String body = "{\"name\":\"旧歧义活动\",\"ownerMemberId\":" + memberId + "}";
        MvcResult created = mockMvc.perform(post(path).with(csrf()).session(owner.session())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        String legacy = com.example.activity.common.SecureCodes.hash(
            "旧歧义活动\u001f%d\u001fnull\u001fnull\u001fnull\u001fnull\u001fINTERNAL\u001fnull\u001fnull\u001fnull\u001f0".formatted(memberId));
        jdbcTemplate.update("UPDATE activity SET request_fingerprint = ? WHERE id = ?", legacy, id);
        for (String request : new String[] {body, body.replace("}", ",\"description\":\"null\"}")}) {
            mockMvc.perform(post(path).with(csrf()).session(owner.session())
                    .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_RESULT_UNVERIFIABLE"));
        }
        Assertions.assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM activity WHERE org_id = ?",
            Integer.class, orgId)).isEqualTo(1);
        Assertions.assertThat(jdbcTemplate.queryForObject("SELECT request_fingerprint FROM activity WHERE id = ?",
            String.class, id)).isEqualTo(legacy);
    }

    @Test
    void legacyCreationKeyStillReplaysOriginalInputAfterEditing() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long memberId = memberIdOf(orgId, owner.username());
        String path = "/api/organizations/" + orgId + "/activities";
        String key = newIdempotencyKey();
        String body = "{\"name\":\"旧活动\",\"ownerMemberId\":" + memberId
            + ",\"description\":\"原简介\",\"location\":\"原地点\"}";
        MvcResult created = mockMvc.perform(post(path).with(csrf()).session(owner.session())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        // V14原请求的手工展开值；不使用生产指纹函数推导预期。
        String legacy = com.example.activity.common.SecureCodes.hash(
            "旧活动\u001f%d\u001f原简介\u001fnull\u001fnull\u001f原地点\u001fINTERNAL\u001fnull\u001fnull\u001fnull\u001f0".formatted(memberId));
        jdbcTemplate.update("UPDATE activity SET request_fingerprint = ? WHERE id = ?", legacy, id);
        mockMvc.perform(patch(path + "/" + id).with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"已编辑活动\",\"version\":1}"))
            .andExpect(status().isOk());
        mockMvc.perform(post(path).with(csrf()).session(owner.session())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.name").value("已编辑活动"));
        Assertions.assertThat(jdbcTemplate.queryForObject("SELECT request_fingerprint FROM activity WHERE id = ?",
            String.class, id)).isEqualTo(legacy);
    }

    @Test
    void ownerCreatesDraftAssigningMemberAsActivityOwner() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long memberMemberId = memberIdOf(orgId, member.username());

        MvcResult created = createDraft(owner, orgId, memberMemberId, """
            {"name":"迎新讲座","ownerMemberId":%d,"description":"面向新生的技术讲座",
             "startTime":"2026-10-01T14:00:00+08:00","endTime":"2026-10-01T16:00:00+08:00",
             "location":"图书馆报告厅","budget":"500.00","registrationScope":"INTERNAL","capacity":80}
            """)
            ;
        org.assertj.core.api.Assertions.assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String body = created.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat((String) com.jayway.jsonpath.JsonPath.read(body, "$.status")).isEqualTo("DRAFT");
        org.assertj.core.api.Assertions.assertThat((String) com.jayway.jsonpath.JsonPath.read(body, "$.budget")).isEqualTo("500.00");
        org.assertj.core.api.Assertions.assertThat((String) com.jayway.jsonpath.JsonPath.read(body, "$.ownerUsername")).isEqualTo(member.username());

        Integer audits = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'ACTIVITY_DRAFT_CREATED' AND target = ?",
            Integer.class, "activity:" + com.jayway.jsonpath.JsonPath.read(body, "$.id"));
        org.assertj.core.api.Assertions.assertThat(audits).isEqualTo(1);
    }

    @Test
    void onlyOrgOwnerCanCreateAndInvalidOwnerRejected() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long memberMemberId = memberIdOf(orgId, member.username());

        mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"越权创建\",\"ownerMemberId\":%d}".formatted(memberMemberId)))
            .andExpect(status().isForbidden());

        // 活动负责人不属于本组织 → 400
        TestSession outsider = registerAndLogin();
        Long otherOrgId = createOrg(outsider);
        Long foreignMemberId = memberIdOf(otherOrgId, outsider.username());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"无效负责人\",\"ownerMemberId\":%d}".formatted(foreignMemberId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("ACTIVITY_OWNER_INVALID"));

        // S08：缺少 Idempotency-Key → 400（已授权的组织负责人）
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"无防重键\",\"ownerMemberId\":%d}".formatted(memberIdOf(orgId, owner.username()))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void draftVisibilityAndEditingPermissions() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession activityOwner = registerAndLogin();
        TestSession plainMember = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, activityOwner, orgId);
        inviteAndAccept(owner, plainMember, orgId);
        Long activityOwnerMemberId = memberIdOf(orgId, activityOwner.username());

        MvcResult created = createDraft(owner, orgId, activityOwnerMemberId,
            "{\"name\":\"可见性活动\",\"ownerMemberId\":%d}");
        Long activityId = Long.parseLong(com.jayway.jsonpath.JsonPath.read(
            created.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.id"));

        // 组织负责人可见
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId)
                .session(owner.session()))
            .andExpect(status().isOk());
        // 活动负责人可见
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId)
                .session(activityOwner.session()))
            .andExpect(status().isOk());
        // 普通成员与无组织账号：404
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId)
                .session(plainMember.session()))
            .andExpect(status().isNotFound());

        // 列表：OWNER 见草稿，普通成员空
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities").session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1));
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities").session(plainMember.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0));

        // 活动负责人可编辑草稿字段
        mockMvc.perform(patch("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(activityOwner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"name\":\"改名后的活动\"}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("改名后的活动"));

        // 活动负责人不能变更活动负责人指派
        mockMvc.perform(patch("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(activityOwner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"ownerMemberId\":%d}".formatted(memberIdOf(orgId, plainMember.username())))))
            .andExpect(status().isForbidden());

        // 普通成员编辑 → 404（不可见）
        mockMvc.perform(patch("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(plainMember.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"name\":\"越权编辑\"}")))
            .andExpect(status().isNotFound());
    }

    @Test
    void timeAndAmountValidation() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long ownerMemberId = memberIdOf(orgId, owner.username());

        // 结束早于开始
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"name\":\"时间倒置\",\"ownerMemberId\":%d,"
                    + "\"startTime\":\"2026-10-01T16:00:00+08:00\",\"endTime\":\"2026-10-01T14:00:00+08:00\"}")
                    .formatted(ownerMemberId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors[0].field").value("endTime"));

        // 报名截止晚于活动开始
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"name\":\"窗口倒置\",\"ownerMemberId\":%d,"
                    + "\"startTime\":\"2026-10-01T14:00:00+08:00\",\"endTime\":\"2026-10-01T16:00:00+08:00\","
                    + "\"registrationStart\":\"2026-09-20T00:00:00+08:00\","
                    + "\"registrationEnd\":\"2026-10-02T00:00:00+08:00\"}")
                    .formatted(ownerMemberId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors[0].field").value("registrationEnd"));

        // 预算负数与三位小数 → 400；零预算允许
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"负预算\",\"ownerMemberId\":%d,\"budget\":\"-1.00\"}".formatted(ownerMemberId)))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"三位小数\",\"ownerMemberId\":%d,\"budget\":\"1.234\"}".formatted(ownerMemberId)))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"零预算\",\"ownerMemberId\":%d,\"budget\":\"0\"}".formatted(ownerMemberId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.budget").value("0.00"));

        // PATCH 合并校验：单独把结束时间改到开始之前 → 400
        MvcResult created = createDraft(owner, orgId, ownerMemberId, """
            {"name":"合并校验","ownerMemberId":%d,"startTime":"2026-10-01T14:00:00+08:00",
             "endTime":"2026-10-01T16:00:00+08:00"}
            """);
        Long activityId = Long.parseLong(com.jayway.jsonpath.JsonPath.read(
            created.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.id"));
        mockMvc.perform(patch("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"endTime\":\"2026-10-01T13:00:00+08:00\"}")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors[0].field").value("endTime"));
    }

    @Test
    void crossOrgActivityAccessIsNotFound() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession outsider = registerAndLogin();
        Long orgId = createOrg(owner);
        Long ownerMemberId = memberIdOf(orgId, owner.username());
        MvcResult created = createDraft(owner, orgId, ownerMemberId,
            "{\"name\":\"隔离活动\",\"ownerMemberId\":%d}");
        Long activityId = Long.parseLong(com.jayway.jsonpath.JsonPath.read(
            created.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.id"));

        Long otherOrgId = createOrg(outsider);
        mockMvc.perform(get("/api/organizations/" + otherOrgId + "/activities/" + activityId)
                .session(outsider.session()))
            .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/organizations/" + otherOrgId + "/activities/" + activityId)
                .with(csrf()).session(outsider.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"name\":\"越权\"}")))
            .andExpect(status().isNotFound());
    }
}
