package com.example.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.activity.support.FlowTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

/**
 * F08 站内提示来源回归：报名批准／驳回／本人取消／管理取消与重新确认都要在业务事务内
 * 留下通知意图（计划书 6.7）。展示界面随 P7，本轮只核对来源与接收人。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class NotificationIntentTest extends FlowTestSupport {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void activityCancellationNotifiesTaskAssigneesOnceEvenWithoutRegistration(boolean registered) throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        TestSession completedMember = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        inviteAndAccept(owner, completedMember, orgId);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()), "2027-05-02T14:00:00+08:00");
        String endpoint = "/api/organizations/" + orgId + "/activities/" + activityId;
        for (int i = 0; i < 3; i++) {
            Long assignee = memberIdOf(orgId, i == 2 ? completedMember.username() : member.username());
            String taskId = readField(mockMvc.perform(post(endpoint + "/tasks").with(csrf()).session(owner.session())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"取消通知核对\",\"assigneeMemberId\":" + assignee + "}"))
                .andExpect(status().isCreated()).andReturn(), "$.taskId");
            if (i != 0) {
                mockMvc.perform(post(endpoint + "/tasks/" + taskId + "/transition").with(csrf()).session(owner.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"toStatus\":\"" + (i == 1 ? "IN_PROGRESS" : "COMPLETED") + "\"}"))
                    .andExpect(status().isOk());
            }
        }
        if (registered) {
            openRegistration(owner, orgId, activityId);
            apply(member, activityId);
        }
        var originalVersions = jdbcTemplate.queryForList("SELECT id, version FROM task WHERE activity_id = ? AND status IN ('PENDING', 'IN_PROGRESS') ORDER BY id",
            activityId);
        String body = versioned(activityId, "{\"reason\":\"场地关闭\"}");
        mockMvc.perform(post(endpoint + "/cancel").with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());
        assertThat(intentsOf(activityId, "ACTIVITY_CANCELLED", accountIdOf(orgId, member.username()))).isEqualTo(1);
        assertThat(intentsOf(activityId, "ACTIVITY_CANCELLED", accountIdOf(orgId, completedMember.username()))).isZero();
        for (var task : originalVersions) {
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM task WHERE id = ?", String.class, task.get("id"))).isEqualTo("CANCELLED");
            assertThat(jdbcTemplate.queryForObject("SELECT version FROM task WHERE id = ?", Long.class, task.get("id")))
                .isEqualTo(((Number) task.get("version")).longValue() + 1);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT JSON_EXTRACT(detail, '$.registrations') FROM audit_log WHERE target = ? AND action = 'ACTIVITY_CANCELLED'",
            Integer.class, "activity:" + activityId)).isEqualTo(registered ? 1 : 0);
        assertThat(jdbcTemplate.queryForObject("SELECT payload FROM notification WHERE activity_id = ? AND type = 'ACTIVITY_CANCELLED'",
            String.class, activityId)).contains("场地关闭");
        mockMvc.perform(post(endpoint + "/cancel").with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());
        assertThat(intentsOf(activityId, "ACTIVITY_CANCELLED", accountIdOf(orgId, member.username()))).isEqualTo(1);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/me/notifications").session(member.session()))
            .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.items[?(@.type=='ACTIVITY_CANCELLED')]").value(org.hamcrest.Matchers.hasSize(1)));
    }

    private long intentsOf(Long activityId, String type, Long recipientAccountId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notification WHERE activity_id = ? AND type = ? AND recipient_account_id = ?",
            Long.class, activityId, type, recipientAccountId);
    }

    private Long accountIdOf(Long orgId, String username) {
        return jdbcTemplate.queryForObject(
            "SELECT m.account_id FROM organization_member m JOIN account a ON a.id = m.account_id "
                + "WHERE m.org_id = ? AND a.username = ? AND m.status = 'ACTIVE'",
            Long.class, orgId, username);
    }

    private String apply(TestSession actor, Long activityId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/activities/" + activityId + "/registrations")
                .with(csrf()).session(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(registrationForAccountVersioned(activityId, actor.username(),
                    "{\"displayName\":\"%s\"}".formatted(actor.username()))))
            .andExpect(status().isCreated())
            .andReturn();
        return readField(created, "$.registrationId");
    }

    @Test
    void registrationDecisionsAndCancellationsRecordNotificationIntents() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        TestSession other = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        inviteAndAccept(owner, other, orgId);
        Long ownerMemberId = memberIdOf(orgId, owner.username());
        Long activityId = createApprovedActivity(owner, orgId, ownerMemberId, "2027-05-01T14:00:00+08:00");
        openRegistration(owner, orgId, activityId);

        Long memberAccountId = accountIdOf(orgId, member.username());
        Long ownerAccountId = accountIdOf(orgId, owner.username());

        // 批准 → 报名人收到结果意图
        String approvedRegistration = apply(member, activityId);
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/registrations/" + approvedRegistration + "/approve")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(registrationVersioned(approvedRegistration, "{}")))
            .andExpect(status().isOk());
        assertThat(intentsOf(activityId, "REGISTRATION_APPROVED", memberAccountId)).isEqualTo(1);

        // 驳回 → 报名人收到结果意图（含原因）
        String rejectedRegistration = apply(other, activityId);
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/registrations/" + rejectedRegistration + "/reject")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(registrationVersioned(rejectedRegistration, "{\"reason\":\"名额安排不开\"}")))
            .andExpect(status().isOk());
        Long otherAccountId = accountIdOf(orgId, other.username());
        assertThat(intentsOf(activityId, "REGISTRATION_REJECTED", otherAccountId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT payload FROM notification WHERE activity_id = ? AND type = 'REGISTRATION_REJECTED'",
            String.class, activityId)).contains("名额安排不开");

        // 本人取消 → 活动负责人收到变更意图
        mockMvc.perform(post("/api/activities/" + activityId + "/registrations/cancel-self")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON).content(registrationVersioned(approvedRegistration, "{}")))
            .andExpect(status().isNoContent());
        assertThat(intentsOf(activityId, "REGISTRATION_CANCELLED_SELF", ownerAccountId)).isEqualTo(1);

        // 管理取消 → 报名人收到取消意图
        String cancelledRegistration = apply(other, activityId);
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/registrations/" + cancelledRegistration + "/cancel-org")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(registrationVersioned(cancelledRegistration, "{\"reason\":\"活动缩减规模\"}")))
            .andExpect(status().isOk());
        assertThat(intentsOf(activityId, "REGISTRATION_CANCELLED_ORG", otherAccountId)).isEqualTo(1);

        // 重新确认 → 既有有效报名者收到变更意图
        String keptRegistration = apply(member, activityId);
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/withdraw-approval")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"reason\":\"安排调整\"}")))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/submit")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/approve")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{}")))
            .andExpect(status().isOk());
        assertThat(intentsOf(activityId, "ACTIVITY_RECONFIRMED", memberAccountId)).isEqualTo(1);
        assertThat(intentsOf(activityId, "ACTIVITY_RECONFIRMED", otherAccountId)).isEqualTo(0);
        assertThat(keptRegistration).isNotBlank();
    }
}
