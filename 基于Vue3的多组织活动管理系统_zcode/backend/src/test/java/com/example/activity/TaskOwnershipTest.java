package com.example.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * S05 回归：本人任务可直接完成，但普通成员不能取消、重新打开或改派。
 * 覆盖反例：PENDING→COMPLETED 返回 200、成员 transition 403、成员 update 403、成员 advance 取消 409。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class TaskOwnershipTest extends FlowTestSupport {

    private String createTask(TestSession actor, Long orgId, Long activityId, Long assigneeMemberId)
        throws Exception {
        MvcResult created = mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/tasks")
                .with(csrf()).session(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"本人任务\",\"assigneeMemberId\":%d}").formatted(assigneeMemberId)))
            .andExpect(status().isCreated())
            .andReturn();
        return readField(created, "$.taskId");
    }

    private RequestBuilder advance(Long orgId, Long activityId, String taskId, TestSession actor, String toStatus) {
        return post("/api/organizations/" + orgId + "/activities/" + activityId + "/tasks/" + taskId + "/advance")
            .with(csrf()).session(actor.session())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"toStatus\":\"%s\"}".formatted(toStatus));
    }

    private RequestBuilder transition(Long orgId, Long activityId, String taskId, TestSession actor,
        String toStatus) {
        return post("/api/organizations/" + orgId + "/activities/" + activityId + "/tasks/" + taskId + "/transition")
            .with(csrf()).session(actor.session())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"toStatus\":\"%s\"}".formatted(toStatus));
    }

    @Test
    void assigneeCanCompleteOwnTaskAndCannotCancelReopenOrReassign() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession delegate = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, delegate, orgId);
        inviteAndAccept(owner, member, orgId);
        Long memberMemberId = memberIdOf(orgId, member.username());
        Long delegateMemberId = memberIdOf(orgId, delegate.username());

        // 指定 delegate 为活动负责人，由其分派任务给 member
        Long activityId = createApprovedActivity(owner, orgId, delegateMemberId, "2026-12-20T14:00:00+08:00");
        String taskId = createTask(delegate, orgId, activityId, memberMemberId);

        // 非分派成员不能推进他人任务
        mockMvc.perform(advance(orgId, activityId, taskId, delegate, "COMPLETED"))
            .andExpect(status().isForbidden());

        // 本人待处理直接完成（S05 原缺陷为 409），并写入完成时间
        mockMvc.perform(advance(orgId, activityId, taskId, member, "COMPLETED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertTaskCompletedAt(Long.parseLong(taskId), true);

        // 不能取消、重新打开或把已完成任务退回进行中
        mockMvc.perform(advance(orgId, activityId, taskId, member, "CANCELLED"))
            .andExpect(status().isConflict());
        mockMvc.perform(advance(orgId, activityId, taskId, member, "PENDING"))
            .andExpect(status().isConflict());
        mockMvc.perform(advance(orgId, activityId, taskId, member, "IN_PROGRESS"))
            .andExpect(status().isConflict());

        // 成员不能经管理者入口取消或重新打开
        mockMvc.perform(transition(orgId, activityId, taskId, member, "CANCELLED"))
            .andExpect(status().isForbidden());
        mockMvc.perform(transition(orgId, activityId, taskId, member, "PENDING"))
            .andExpect(status().isForbidden());

        // 成员不能改派（含把任务改给活动负责人）
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/tasks/" + taskId + "/update")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"改派尝试\",\"assigneeMemberId\":%d}").formatted(delegateMemberId)))
            .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT assignee_member_id FROM task WHERE id = ?", Long.class, Long.parseLong(taskId)))
            .isEqualTo(memberMemberId);

        // 管理者可以重新打开并改派，成员不再是责任人
        mockMvc.perform(transition(orgId, activityId, taskId, delegate, "PENDING"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"));
        assertTaskCompletedAt(Long.parseLong(taskId), false);
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/tasks/" + taskId + "/update")
                .with(csrf()).session(delegate.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"本人任务\",\"assigneeMemberId\":%d}").formatted(delegateMemberId)))
            .andExpect(status().isOk());
        mockMvc.perform(advance(orgId, activityId, taskId, member, "COMPLETED"))
            .andExpect(status().isForbidden());
    }

    @Test
    void assigneeCanStepThroughInProgressThenComplete() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2026-12-21T14:00:00+08:00");
        String taskId = createTask(owner, orgId, activityId, memberIdOf(orgId, member.username()));

        mockMvc.perform(advance(orgId, activityId, taskId, member, "IN_PROGRESS"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mockMvc.perform(advance(orgId, activityId, taskId, member, "COMPLETED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"));
    }
}
