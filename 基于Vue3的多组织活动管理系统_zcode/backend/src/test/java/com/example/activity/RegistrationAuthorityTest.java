package com.example.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.activity.support.FlowTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

/**
 * S02／S04 回归：报名开关与时间／状态约束，以及指定活动负责人的名单与管理取消权限。
 * 覆盖三组反例：开关关闭不能申请、开始后不能申请／批准／本人取消、活动负责人不再 403。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class RegistrationAuthorityTest extends FlowTestSupport {

    private void switchRegistration(TestSession actor, Long orgId, Long activityId, boolean open,
        int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/registration-open")
                .with(csrf()).session(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"open\":%s}".formatted(open)))
            .andExpect(status().is(expectedStatus));
    }

    private int applyStatus(TestSession session, Long activityId) throws Exception {
        return mockMvc.perform(post("/api/activities/" + activityId + "/registrations")
                .with(csrf()).session(session.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"%s\"}".formatted(session.username())))
            .andReturn().getResponse().getStatus();
    }

    private Long apply(TestSession session, Long activityId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/activities/" + activityId + "/registrations")
                .with(csrf()).session(session.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"%s\"}".formatted(session.username())))
            .andExpect(status().isCreated())
            .andReturn();
        return Long.parseLong(readField(result, "$.registrationId"));
    }

    /**
     * 把已确认活动推进到「已开始」。开始时间属于计划书 6.1 的关键变更，
     * 合法路径是撤回草稿后修改再重新确认，而撤回会清空报名，无法同时保留开始前的报名记录，
     * 因此这里直接改状态列构造时间边界场景。
     */
    private void makeActivityStarted(Long orgId, Long activityId) {
        jdbcTemplate.update("UPDATE activity SET start_time = ?, end_time = ? WHERE id = ? AND org_id = ?",
            java.time.OffsetDateTime.now().minusHours(1), java.time.OffsetDateTime.now().plusHours(1),
            activityId, orgId);
    }

    @Test
    void registrationSwitchGatesNewApplicationsOnly() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2026-12-10T14:00:00+08:00");

        // 默认关闭：申请被拒（S02 原缺陷为无窗口即可报名）
        assertThat(applyStatus(member, activityId)).isEqualTo(400);
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId).session(member.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.registrationOpen").value(false));

        // 负责人开启后可申请
        switchRegistration(owner, orgId, activityId, true, 200);
        Long registrationId = apply(member, activityId);

        // 开关只约束新申请：关闭后申请被拒，但既有申请仍可审核（后端架构 5.2）
        switchRegistration(owner, orgId, activityId, false, 200);
        assertThat(applyStatus(member, activityId)).isEqualTo(400);
        switchRegistration(owner, orgId, activityId, true, 200);
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/registrations/" + registrationId + "/approve")
                .with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content(registrationVersioned(registrationId, "{}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"));

        // 关闭后既有有效报名仍可由本人查看，不因开关关闭被隐藏；已通过报名不能重复批准
        switchRegistration(owner, orgId, activityId, false, 200);
        mockMvc.perform(get("/api/me/registrations").session(member.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].status").value("APPROVED"));
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/registrations/" + registrationId + "/approve")
                .with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content(registrationVersioned(registrationId, "{}")))
            .andExpect(status().isConflict());
    }

    @Test
    void startedActivityRejectsApplyApproveAndSelfCancel() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        TestSession another = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        inviteAndAccept(owner, another, orgId);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2026-12-11T14:00:00+08:00");
        openRegistration(owner, orgId, activityId);
        Long registrationId = apply(member, activityId);
        Long pendingRegistrationId = apply(another, activityId);

        // 活动开始后：不能新申请、不能批准、不能本人取消、不能管理取消
        makeActivityStarted(orgId, activityId);
        assertThat(applyStatus(member, activityId)).isEqualTo(400);
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/registrations/" + registrationId + "/approve")
                .with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content(registrationVersioned(registrationId, "{}")))
            .andExpect(status().isConflict());
        mockMvc.perform(post("/api/activities/" + activityId + "/registrations/cancel-self")
                .with(csrf()).session(member.session()).contentType(MediaType.APPLICATION_JSON)
                .content(registrationVersioned(registrationId, "{}")))
            .andExpect(status().isConflict());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/registrations/" + pendingRegistrationId + "/cancel-org")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(registrationVersioned(pendingRegistrationId, "{\"reason\":\"开始后清理\"}")))
            .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM registration WHERE id = ?", String.class, registrationId)).isEqualTo("PENDING");

        // 开始后仍可驳回待审，用于清空待审申请（计划书 6.2）
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/registrations/" + registrationId + "/reject")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(registrationVersioned(registrationId, "{\"reason\":\"已开始，未获批准\"}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void activityOwnerCanReadListAndCancelOrg() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession delegate = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, delegate, orgId);
        inviteAndAccept(owner, member, orgId);

        // 指派 delegate 为该活动负责人（S04 原缺陷：名单与管理取消 403）
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, delegate.username()),
            "2026-12-12T14:00:00+08:00");
        openRegistration(delegate, orgId, activityId);
        Long registrationId = apply(member, activityId);

        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId + "/registrations")
                .session(delegate.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/registrations/" + registrationId + "/approve")
                .with(csrf()).session(delegate.session()).contentType(MediaType.APPLICATION_JSON)
                .content(registrationVersioned(registrationId, "{}")))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/registrations/" + registrationId + "/cancel-org")
                .with(csrf()).session(delegate.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(registrationVersioned(registrationId, "{\"reason\":\"报名者请假\"}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED_ORG"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT cancel_reason FROM registration WHERE id = ?", String.class, registrationId))
            .isEqualTo("报名者请假");
        mockMvc.perform(get("/api/me/registrations").session(member.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].accountUsername").value(member.username()))
            .andExpect(jsonPath("$.items[0].status").value("CANCELLED_ORG"));

        // 无关成员仍不能读取名单
        TestSession outsider = registerAndLogin();
        inviteAndAccept(owner, outsider, orgId);
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId + "/registrations")
                .session(outsider.session()))
            .andExpect(status().isForbidden());
    }

    /** F09：管理名单按服务端分页返回，total 与筛选条件同口径，第 2 页能取到后续申请。 */
    @Test
    void rosterIsPagedAndReportsTotal() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession first = registerAndLogin();
        TestSession second = registerAndLogin();
        TestSession third = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, first, orgId);
        inviteAndAccept(owner, second, orgId);
        inviteAndAccept(owner, third, orgId);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-07-10T14:00:00+08:00");
        openRegistration(owner, orgId, activityId);
        apply(first, activityId);
        apply(second, activityId);
        apply(third, activityId);

        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId + "/registrations")
                .param("page", "1").param("pageSize", "2").session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.pageSize").value(2))
            .andExpect(jsonPath("$.items.length()").value(2));
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId + "/registrations")
                .param("page", "2").param("pageSize", "2").session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.items.length()").value(1));

        // 状态筛选与 total 同口径：筛选后没有已通过记录时 total 归零
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId + "/registrations")
                .param("status", "APPROVED").session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0))
            .andExpect(jsonPath("$.items.length()").value(0));
    }
}
