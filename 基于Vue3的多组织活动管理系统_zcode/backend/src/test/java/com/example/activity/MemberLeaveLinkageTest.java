package com.example.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * S07-B 成员职责与退出联动：活动职责／未完成任务阻断退出；
 * 退出时取消「尚未进入执行的内部活动」待审与有效报名、释放名额、保留原因并写提示意图；
 * 已开始的活动与公开活动报名保留为历史。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class MemberLeaveLinkageTest extends FlowTestSupport {

    private void leave(TestSession member, Long orgId, int expectedStatus) throws Exception {
        mockMvc.perform(delete("/api/organizations/" + orgId + "/members/me")
                .with(csrf()).session(member.session()))
            .andExpect(status().is(expectedStatus));
    }

    private void withdrawApproval(TestSession owner, Long orgId, Long activityId) throws Exception {
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/withdraw-approval")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"reason\":\"调整报名范围\"}")))
            .andExpect(status().isOk());
    }

    private String statusOf(Long registrationId) {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM registration WHERE id = ?", String.class, registrationId);
    }

    @Test
    void leaveBlockedByActivityDutyAndUnblockedAfterOwnerChange() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long memberMemberId = memberIdOf(orgId, member.username());

        // 成员负责一个活动（草稿阶段）：退出被拒并说明仍有负责活动
        MvcResult created = mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(activityBody("成员负责的活动", memberMemberId, "2026-12-31T14:00:00+08:00")))
            .andExpect(status().isCreated())
            .andReturn();
        String activityId = readField(created, "$.id");
        mockMvc.perform(delete("/api/organizations/" + orgId + "/members/me")
                .with(csrf()).session(member.session()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("MEMBER_HAS_ACTIVITY_DUTIES"))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("请先变更活动负责人")));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM organization_member WHERE id = ?", String.class, memberMemberId))
            .isEqualTo("ACTIVE");

        // 组织负责人改派活动负责人后，成员可以退出
        mockMvc.perform(patch("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, ("{\"ownerMemberId\":%d}").formatted(memberIdOf(orgId, owner.username())))))
            .andExpect(status().isOk());
        leave(member, orgId, 204);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM organization_member WHERE id = ?", String.class, memberMemberId))
            .isEqualTo("LEFT");
        // 退出后立即失去内部数据访问权
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities").session(member.session()))
            .andExpect(status().isNotFound());
    }

    @Test
    void leaveCancelsInternalRegistrationsAndReleasesCapacity() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        TestSession other = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        inviteAndAccept(owner, other, orgId);
        Long memberMemberId = memberIdOf(orgId, member.username());
        Long memberAccountId = jdbcTemplate.queryForObject(
            "SELECT account_id FROM organization_member WHERE id = ?", Long.class, memberMemberId);

        // 内部活动，容量 1：member 已通过占满名额，another 待审
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-01-05T14:00:00+08:00");
        openRegistration(owner, orgId, activityId);
        MvcResult applied = mockMvc.perform(post("/api/activities/" + activityId + "/registrations")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"%s\"}".formatted(member.username())))
            .andExpect(status().isCreated())
            .andReturn();
        Long registrationId = Long.parseLong(readField(applied, "$.registrationId"));
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/registrations/" + registrationId + "/approve")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM registration WHERE activity_id = ? AND status = 'APPROVED'",
            Integer.class, activityId)).isEqualTo(1);

        // 退出：报名转为组织取消，保留原因，写入提示意图
        leave(member, orgId, 204);
        assertThat(statusOf(registrationId)).isEqualTo("CANCELLED_ORG");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT cancel_reason FROM registration WHERE id = ?", String.class, registrationId))
            .isEqualTo("成员退出组织，内部报名自动取消");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notification WHERE recipient_account_id = ? "
                + "AND type = 'REGISTRATION_CANCELLED' AND activity_id = ?",
            Integer.class, memberAccountId, activityId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'REGISTRATION_CANCELLED_ON_LEAVE' "
                + "AND target = ?", Integer.class, "registration:" + registrationId)).isEqualTo(1);
        // 名额已释放：有效报名集合不再包含该记录
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM registration WHERE activity_id = ? "
                + "AND status IN ('PENDING','APPROVED')", Integer.class, activityId)).isZero();

        // 退出是幂等的：重复退出不重复产生业务结果
        leave(member, orgId, 204);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notification WHERE recipient_account_id = ? "
                + "AND type = 'REGISTRATION_CANCELLED'",
            Integer.class, memberAccountId)).isEqualTo(1);
    }

    @Test
    void startedActivityAndPublicRegistrationSurviveLeave() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);

        // 已开始的内部活动：报名保留为历史
        Long internalId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-01-06T14:00:00+08:00");
        openRegistration(owner, orgId, internalId);
        MvcResult internalApply = mockMvc.perform(post("/api/activities/" + internalId + "/registrations")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"%s\"}".formatted(member.username())))
            .andExpect(status().isCreated())
            .andReturn();
        Long internalRegistrationId = Long.parseLong(readField(internalApply, "$.registrationId"));
        jdbcTemplate.update("UPDATE activity SET start_time = ?, end_time = ? WHERE id = ?",
            java.time.OffsetDateTime.now().minusHours(2), java.time.OffsetDateTime.now().minusHours(1),
            internalId);

        // 公开活动：报名按外部身份保留
        Long publicId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-01-07T14:00:00+08:00");
        withdrawApproval(owner, orgId, publicId);
        // 公开报名需要名额与报名窗口（P2.2 完备性校验），补齐后重新确认
        mockMvc.perform(patch("/api/organizations/" + orgId + "/activities/" + publicId)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(publicId, ("{\"registrationScope\":\"PUBLIC\",\"capacity\":10,"
                    + "\"registrationStart\":\"2026-09-01T00:00:00+08:00\","
                    + "\"registrationEnd\":\"2027-01-04T00:00:00+08:00\"}"))))
            .andExpect(status().isOk());
        submitForApproval(owner, orgId, publicId);
        openRegistration(owner, orgId, publicId);
        MvcResult publicApply = mockMvc.perform(post("/api/activities/" + publicId + "/registrations")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"%s\"}".formatted(member.username())))
            .andExpect(status().isCreated())
            .andReturn();
        Long publicRegistrationId = Long.parseLong(readField(publicApply, "$.registrationId"));

        leave(member, orgId, 204);

        // 已开始：保留原状态；未开始的内部活动：取消；公开活动报名按外部身份保留
        assertThat(statusOf(internalRegistrationId)).isEqualTo("PENDING");
        assertThat(statusOf(publicRegistrationId)).isEqualTo("PENDING");
        // 本人报名记录仍可查看（不因退出删除业务历史）
        mockMvc.perform(get("/api/me/registrations").session(member.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2));
    }

    private void withdrawToDraft(TestSession owner, Long orgId, Long activityId) throws Exception {
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/withdraw-approval")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"reason\":\"安排调整\"}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void leaveCancelsRegistrationsDuringAdjustmentPeriod() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-07-01T14:00:00+08:00");
        openRegistration(owner, orgId, activityId);
        MvcResult applied = mockMvc.perform(post("/api/activities/" + activityId + "/registrations")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"%s\"}".formatted(member.username())))
            .andExpect(status().isCreated())
            .andReturn();
        Long registrationId = Long.parseLong(readField(applied, "$.registrationId"));
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/registrations/" + registrationId + "/approve")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isOk());

        // F03 原缺陷：活动撤回到草稿后，退出不再取消内部报名（SQL 限 APPROVED）
        withdrawToDraft(owner, orgId, activityId);
        leave(member, orgId, 204);
        assertThat(statusOf(registrationId)).isEqualTo("CANCELLED_ORG");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM registration WHERE activity_id = ? AND status IN ('PENDING','APPROVED')",
            Integer.class, activityId)).isZero();

        // 重新提交待确认同样属于“尚未进入执行”，退出也要取消
        TestSession another = registerAndLogin();
        inviteAndAccept(owner, another, orgId);
        submitForApproval(owner, orgId, activityId);
        openRegistration(owner, orgId, activityId);
        MvcResult second = mockMvc.perform(post("/api/activities/" + activityId + "/registrations")
                .with(csrf()).session(another.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"%s\"}".formatted(another.username())))
            .andExpect(status().isCreated())
            .andReturn();
        Long secondRegistrationId = Long.parseLong(readField(second, "$.registrationId"));
        withdrawToDraft(owner, orgId, activityId);
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/submit")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isOk());
        leave(another, orgId, 204);
        assertThat(statusOf(secondRegistrationId)).isEqualTo("CANCELLED_ORG");
    }
}
