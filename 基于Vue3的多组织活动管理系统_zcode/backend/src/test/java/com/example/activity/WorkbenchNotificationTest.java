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

/**
 * P7 工作台与站内提示回归：按身份的卡片口径（进行中活动、任务完成率、名额使用率、经费与反馈）、
 * 待办只列未处理事项、提示列表按当前权限复核（撤权后不下发）、来源操作防重与已读标记。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class WorkbenchNotificationTest extends FlowTestSupport {

    private String workbenchPath(Long orgId) {
        return "/api/organizations/" + orgId + "/workbench";
    }

    private long versionOf(Long activityId) {
        return jdbcTemplate.queryForObject("SELECT version FROM activity WHERE id = ?", Long.class, activityId);
    }

    private void startExecution(TestSession owner, Long orgId, Long activityId) throws Exception {
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/start-execution")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + versionOf(activityId) + "}"))
            .andExpect(status().isOk());
    }

    @Test
    void workbenchCardsFollowRoleAndBusinessScope() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long memberMemberId = memberIdOf(orgId, member.username());
        // 一个已确认活动 + 一个待确认活动
        Long approved = createApprovedActivity(owner, orgId, memberMemberId, "2027-09-01T14:00:00+08:00");
        openRegistration(owner, orgId, approved);
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(activityBody("待确认活动", memberMemberId, "2027-09-02T14:00:00+08:00")))
            .andExpect(status().isCreated());
        Long pendingId = jdbcTemplate.queryForObject(
            "SELECT id FROM activity WHERE org_id = ? AND name = '待确认活动'", Long.class, orgId);
        // 只提交不确认，保持“待确认”状态用于卡片口径核对
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/organizations/" + orgId + "/activities/" + pendingId + "/proposal")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(pendingId, "{\"content\":\"待确认方案\"}")))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + pendingId + "/submit")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isOk());

        // 组织负责人：进行中＝筹备中（不含待确认），复盘待办单列
        mockMvc.perform(get(workbenchPath(orgId)).session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("OWNER"))
            .andExpect(jsonPath("$.cards[?(@.key=='activities-in-progress')].value").value("1"))
            .andExpect(jsonPath("$.cards[?(@.key=='activities-pending')].value").value("1"))
            .andExpect(jsonPath("$.cards[?(@.key=='activities-reviewing')].value").value("0"));

        // 活动负责人（普通成员）聚焦本人负责活动；普通成员只有本人任务与报名
        mockMvc.perform(get(workbenchPath(orgId)).session(member.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("ACTIVITY_OWNER"))
            .andExpect(jsonPath("$.cards[?(@.key=='activities-mine')].value").value("1"));
        TestSession plain = registerAndLogin();
        inviteAndAccept(owner, plain, orgId);
        mockMvc.perform(get(workbenchPath(orgId)).session(plain.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("MEMBER"))
            .andExpect(jsonPath("$.cards.length()").value(0))
            .andExpect(jsonPath("$.myCards[?(@.key=='my-registrations')].value").value("0"));

        // 任务完成率分母为零时显示 —；活动已设名额时使用率按已通过／名额计算
        mockMvc.perform(get(workbenchPath(orgId)).session(owner.session()))
            .andExpect(jsonPath("$.cards[?(@.key=='tasks-completion')].value").value("—"))
            .andExpect(jsonPath("$.cards[?(@.key=='registrations-capacity')].value").value("0.0%"));

        // 报名与任务产生后口径变化：1 条待审报名 + 1 个任务（完成 1/1）
        mockMvc.perform(post("/api/activities/" + approved + "/registrations")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"报名者\"}"))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + approved + "/tasks")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"工作台任务\",\"assigneeMemberId\":%d}").formatted(memberMemberId)))
            .andExpect(status().isCreated());
        mockMvc.perform(get(workbenchPath(orgId)).session(owner.session()))
            .andExpect(jsonPath("$.cards[?(@.key=='registrations-effective')].value").value("1"))
            .andExpect(jsonPath("$.cards[?(@.key=='registrations-capacity')].value").value("0.0%"))
            .andExpect(jsonPath("$.cards[?(@.key=='tasks-completion')].value").value("0.0%"))
            .andExpect(jsonPath("$.cards[?(@.key=='feedback')].value").value("暂无反馈"));
    }

    @Test
    void workbenchTodosListOnlyUnhandledItems() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long memberMemberId = memberIdOf(orgId, member.username());
        Long activityId = createApprovedActivity(owner, orgId, memberMemberId, "2027-09-03T14:00:00+08:00");
        openRegistration(owner, orgId, activityId);
        mockMvc.perform(post("/api/activities/" + activityId + "/registrations")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"待审报名\"}"))
            .andExpect(status().isCreated());
        // 逾期任务进入待办
        String taskId = readField(mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/tasks")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"逾期任务\",\"assigneeMemberId\":%d,\"dueAt\":\"2020-01-01T00:00:00+08:00\"}")
                    .formatted(memberMemberId)))
            .andExpect(status().isCreated())
            .andReturn(), "$.taskId");

        mockMvc.perform(get(workbenchPath(orgId)).session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.todos[?(@.kind=='REGISTRATION_PENDING')]").exists())
            .andExpect(jsonPath("$.todos[?(@.kind=='TASK_OVERDUE')]").exists())
            .andExpect(jsonPath("$.todos[?(@.kind=='TASK_OVERDUE')].path").value(
                org.hamcrest.Matchers.hasItem("/org/" + orgId + "/activities/" + activityId)));

        // 处理完待审报名后不再列为待办
        Long registrationId = jdbcTemplate.queryForObject(
            "SELECT id FROM registration WHERE activity_id = ?", Long.class, activityId);
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/registrations/" + registrationId + "/approve")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isOk());
        mockMvc.perform(get(workbenchPath(orgId)).session(owner.session()))
            .andExpect(jsonPath("$.todos[?(@.kind=='REGISTRATION_PENDING')]").isEmpty());
        // 复盘待办：进入复盘且总结未确认
        startExecution(owner, orgId, activityId);
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/finish-execution")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + versionOf(activityId) + "}"))
            .andExpect(status().isOk());
        mockMvc.perform(get(workbenchPath(orgId)).session(owner.session()))
            .andExpect(jsonPath("$.todos[?(@.kind=='ACTIVITY_REVIEW')].title").value(
                org.hamcrest.Matchers.hasItem("待归档：测试活动")));
        assertThat(taskId).isNotBlank();
    }

    @Test
    void notificationsArePermissionCheckedAndDeduplicated() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long memberMemberId = memberIdOf(orgId, member.username());
        // 活动负责人设为组织负责人本人，便于用例末尾验证成员退出后的提示可见性
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-09-04T14:00:00+08:00");
        openRegistration(owner, orgId, activityId);
        mockMvc.perform(post("/api/activities/" + activityId + "/registrations")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"提示核对\"}"))
            .andExpect(status().isCreated());
        Long registrationId = jdbcTemplate.queryForObject(
            "SELECT id FROM registration WHERE activity_id = ?", Long.class, activityId);
        // 任务分派给成员 → 产生 TASK_ASSIGNED 提示
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/tasks")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"提示任务\",\"assigneeMemberId\":%d}").formatted(memberMemberId)))
            .andExpect(status().isCreated());
        // 批准报名 → 报名人收到结果提示
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/registrations/" + registrationId + "/approve")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/me/notifications").session(member.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.unread").value(2))
            .andExpect(jsonPath("$.items[?(@.type=='REGISTRATION_APPROVED')]").exists())
            .andExpect(jsonPath("$.items[?(@.type=='TASK_ASSIGNED')]").exists())
            .andExpect(jsonPath("$.items[0].activityName").value("测试活动"));

        // 同来源操作重复写意图不产生第二条：再次批准同一报名（状态守卫拒绝）后再核对总数
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/registrations/" + registrationId + "/approve")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isConflict());
        mockMvc.perform(get("/api/me/notifications").session(member.session()))
            .andExpect(jsonPath("$.total").value(2));

        // 标记已读：他人提示与不存在同响应
        Long notificationId = jdbcTemplate.queryForObject(
            "SELECT id FROM notification WHERE recipient_account_id = (SELECT account_id FROM organization_member "
                + "WHERE org_id = ? AND id = ?) ORDER BY id LIMIT 1",
            Long.class, orgId, memberMemberId);
        mockMvc.perform(post("/api/me/notifications/" + notificationId + "/read")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/me/notifications/" + notificationId + "/read")
                .with(csrf()).session(member.session()))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/me/notifications").session(member.session()))
            .andExpect(jsonPath("$.unread").value(1));
        mockMvc.perform(post("/api/me/notifications/read-all")
                .with(csrf()).session(member.session()))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/me/notifications").session(member.session()))
            .andExpect(jsonPath("$.unread").value(0));

        // 成员退出前先结束未完成任务（未结职责是退出前置条件），退出后旧提示不再下发
        String assignedTaskId = String.valueOf(jdbcTemplate.queryForObject(
            "SELECT id FROM task WHERE activity_id = ? ORDER BY id DESC LIMIT 1", Long.class, activityId));
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/tasks/" + assignedTaskId + "/transition")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toStatus\":\"CANCELLED\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/organizations/" + orgId + "/members/me")
                .with(csrf()).session(member.session()))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/me/notifications").session(member.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0))
            .andExpect(jsonPath("$.items.length()").value(0));
    }
}
