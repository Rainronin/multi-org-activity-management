package com.example.activity;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.activity.support.ActivityTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 活动状态机（P2.2/P2.3）：方案保存、提交完备性校验、撤回、单级确认（自行/他人留痕）、
 * 驳回原因、各状态下的编辑限制、草稿删除。身份经真实注册与登录建立。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ActivityStateFlowTest extends ActivityTestBase {

    private static final String PASSWORD = "Passw0rd123";

    @Autowired
    private MockMvc mockMvc;

    private record TestSession(String username, MockHttpSession session) {
    }

    private TestSession registerAndLogin() throws Exception {
        String username = "s" + Long.toString(System.nanoTime(), 36);
        mockMvc.perform(post("/api/auth/register").with(csrf())
                .with(request -> {
                    request.setRemoteAddr("10.%d.%d.21".formatted(
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
                .content("{\"name\":\"状态机组织-%d\"}".formatted(System.nanoTime())))
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

    private Long createMinimalDraft(TestSession owner, Long orgId, Long ownerMemberId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"待完善活动\",\"ownerMemberId\":%d}".formatted(ownerMemberId)))
            .andExpect(status().isCreated())
            .andReturn();
        return Long.parseLong(com.jayway.jsonpath.JsonPath.read(
            created.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.id"));
    }


    @Test
    void proposalSaveSubmitRejectCycle() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long ownerMemberId = memberIdOf(orgId, owner.username());
        Long activityId = createMinimalDraft(owner, orgId, ownerMemberId);

        // 信息不完备提交 → 400 且列出缺失项
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/submit")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("SUBMISSION_INCOMPLETE"));

        // 保存方案（活动负责人可为 OWNER 本人）
        mockMvc.perform(put("/api/organizations/" + orgId + "/activities/" + activityId + "/proposal")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"content\":\"一、活动流程……二、分工安排……\"}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.proposal").value("一、活动流程……二、分工安排……"));

        // 补全信息后提交
        mockMvc.perform(patch("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, ("{\"description\":\"新生交流活动\",\"startTime\":\"2026-10-01T14:00:00+08:00\","
                    + "\"endTime\":\"2026-10-01T16:00:00+08:00\",\"location\":\"图书馆报告厅\"}"))))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/submit")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        // 待确认期间字段编辑 → 409
        mockMvc.perform(patch("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"location\":\"改地点\"}")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ACTIVITY_STATE_CONFLICT"));

        // 自行确认：提交者与确认者同一人 → SELF
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/approve")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"note\":\"内容完整，同意\"}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.confirmType").value("SELF"));
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId + "/confirmations")
                .session(owner.session()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].kind").value("PROPOSAL"));

        // 已确认活动：组织负责人可改简介；关键内容 409
        mockMvc.perform(patch("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"description\":\"更新后的简介\"}")))
            .andExpect(status().isOk());
        mockMvc.perform(patch("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"location\":\"新地点\"}")))
            .andExpect(status().isConflict());

        // P2.3 撤回到草稿后可修改关键内容
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/withdraw-approval")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"reason\":\"场地变更需重新确认\"}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DRAFT"));
        mockMvc.perform(patch("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"location\":\"新报告厅\"}")))
            .andExpect(status().isOk());
    }

    @Test
    void activityOwnerSubmitsAndOrgOwnerApprovesAsOther() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession activityOwner = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, activityOwner, orgId);
        Long activityOwnerMemberId = memberIdOf(orgId, activityOwner.username());
        Long activityId = createMinimalDraft(owner, orgId, activityOwnerMemberId);

        // 活动负责人保存方案并提交
        mockMvc.perform(put("/api/organizations/" + orgId + "/activities/" + activityId + "/proposal")
                .with(csrf()).session(activityOwner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"content\":\"方案内容\"}")))
            .andExpect(status().isOk());
        mockMvc.perform(patch("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(activityOwner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, ("{\"description\":\"简介\",\"startTime\":\"2026-10-01T14:00:00+08:00\","
                    + "\"endTime\":\"2026-10-01T16:00:00+08:00\",\"location\":\"报告厅\"}"))))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/submit")
                .with(csrf()).session(activityOwner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        // 活动负责人不能审批
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/approve")
                .with(csrf()).session(activityOwner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{}")))
            .andExpect(status().isForbidden());

        // 组织负责人审批他人提交 → OTHER
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/approve")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.confirmType").value("OTHER"));
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId + "/confirmations")
                .session(activityOwner.session()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].kind").value("PROPOSAL"))
            .andExpect(jsonPath("$.items[0].content").value("方案内容"));

        // 已确认后活动负责人仅能改简介之外的内容 → 409（非组织负责人）
        mockMvc.perform(patch("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(activityOwner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"description\":\"尝试\"}")))
            .andExpect(status().isConflict());
    }

    @Test
    void withdrawThenRejectWithReasonThenDeleteDraft() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long memberMemberId = memberIdOf(orgId, member.username());
        Long activityId = createMinimalDraft(owner, orgId, memberMemberId);

        mockMvc.perform(put("/api/organizations/" + orgId + "/activities/" + activityId + "/proposal")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"content\":\"方案\"}")))
            .andExpect(status().isOk());
        mockMvc.perform(patch("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, ("{\"description\":\"简介\",\"startTime\":\"2026-10-01T14:00:00+08:00\","
                    + "\"endTime\":\"2026-10-01T16:00:00+08:00\",\"location\":\"操场\"}"))))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/submit")
                .with(csrf()).session(member.session()))
            .andExpect(status().isOk());

        // 提交者撤回
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/withdraw")
                .with(csrf()).session(member.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DRAFT"));

        // 再次提交后驳回：原因写入 review_note
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/submit")
                .with(csrf()).session(member.session()))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/reject")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"reason\":\"预算明细不足，请补充\"}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.reviewNote").value("预算明细不足，请补充"));

        // 从未通过立项确认的草稿仍可删除（普通成员不能删他人草稿由 OWNER 入口限制）
        Long anotherActivityId = createMinimalDraft(owner, orgId, memberIdOf(orgId, owner.username()));
        mockMvc.perform(delete("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(owner.session()))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + anotherActivityId)
                .session(owner.session()))
            .andExpect(status().isOk());
        mockMvc.perform(delete("/api/organizations/" + orgId + "/activities/" + anotherActivityId)
                .with(csrf()).session(owner.session()))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + anotherActivityId)
                .session(owner.session()))
            .andExpect(status().isNotFound());
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
}
