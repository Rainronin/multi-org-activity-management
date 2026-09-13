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
 * S07 关键变更冻结回归：已有报名后不得切换报名范围、名额不得低于已通过报名数、
 * 调整期待确认期间任务与物资只读、曾确认后撤回的草稿不得删除、有依赖的草稿不得删除。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ActivityChangeFreezeTest extends FlowTestSupport {

    private String taskIdOf(Long orgId, String title) {
        return String.valueOf(jdbcTemplate.queryForObject(
            "SELECT t.id FROM task t JOIN activity a ON a.id = t.activity_id "
                + "WHERE a.org_id = ? AND t.title = ? ORDER BY t.id DESC LIMIT 1",
            Long.class, orgId, title));
    }

    private void withdrawToDraft(TestSession owner, Long orgId, Long activityId) throws Exception {
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/withdraw-approval")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"安排调整\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.confirmedAt").isNotEmpty());
    }

    @Test
    void scopeLockedAfterAnyRegistrationExists() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2026-12-25T14:00:00+08:00");

        // 无报名时可切换范围（已确认期只允许改简介，需先撤回）
        withdrawToDraft(owner, orgId, activityId);
        mockMvc.perform(patch("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"registrationScope\":\"PUBLIC\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(patch("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"registrationScope\":\"INTERNAL\"}"))
            .andExpect(status().isOk());
        submitForApproval(owner, orgId, activityId);

        // 产生一条报名后撤回再确认，范围切换必须被拒绝
        openRegistration(owner, orgId, activityId);
        mockMvc.perform(post("/api/activities/" + activityId + "/registrations")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"%s\"}".formatted(member.username())))
            .andExpect(status().isCreated());
        withdrawToDraft(owner, orgId, activityId);
        mockMvc.perform(patch("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"registrationScope\":\"PUBLIC\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ACTIVITY_SCOPE_LOCKED"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT registration_scope FROM activity WHERE id = ?", String.class, activityId))
            .isEqualTo("INTERNAL");
        // 锁定与状态无关：重新确认后仍然不能切换
        submitForApproval(owner, orgId, activityId);
        mockMvc.perform(patch("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"registrationScope\":\"PUBLIC\"}"))
            .andExpect(status().isConflict());
    }

    @Test
    void capacityCannotFallBelowApprovedRegistrations() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2026-12-26T14:00:00+08:00");
        openRegistration(owner, orgId, activityId);
        MvcResult applied = mockMvc.perform(post("/api/activities/" + activityId + "/registrations")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"%s\"}".formatted(member.username())))
            .andExpect(status().isCreated())
            .andReturn();
        String registrationId = readField(applied, "$.registrationId");
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/registrations/" + registrationId + "/approve")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isOk());

        // 撤回后调整容量：可调整到与已通过人数一致（容量下限规则：新容量不得小于现有有效报名人数）
        withdrawToDraft(owner, orgId, activityId);
        mockMvc.perform(patch("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"capacity\":1}"))
            .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT capacity FROM activity WHERE id = ?", Integer.class, activityId)).isEqualTo(1);
        // 调整期同样受报名范围锁定约束（即便同时提交了容量）
        mockMvc.perform(patch("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"capacity\":1,\"registrationScope\":\"PUBLIC\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ACTIVITY_SCOPE_LOCKED"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT capacity FROM activity WHERE id = ?", Integer.class, activityId)).isEqualTo(1);
    }

    @Test
    void tasksAndMaterialsAreReadOnlyDuringReconfirmation() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long memberMemberId = memberIdOf(orgId, member.username());
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2026-12-27T14:00:00+08:00");

        // 撤回前：任务与物资都可维护，成员可推进本人任务
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/tasks")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"筹备任务\",\"assigneeMemberId\":%d}").formatted(memberMemberId)))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/materials")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"横幅\",\"quantity\":1,\"unit\":\"条\"}"))
            .andExpect(status().isCreated());
        String taskId = taskIdOf(orgId, "筹备任务");
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/tasks/" + taskId + "/advance")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toStatus\":\"COMPLETED\"}"))
            .andExpect(status().isOk());

        // 撤回到草稿（调整期待确认）：任务与物资全部只读
        withdrawToDraft(owner, orgId, activityId);
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/tasks")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"调整期新增任务\",\"assigneeMemberId\":%d}").formatted(memberMemberId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ACTIVITY_STATE_CONFLICT"));
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/tasks/" + taskId + "/advance")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toStatus\":\"PENDING\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ACTIVITY_STATE_CONFLICT"));
        // 现任任务承担者访问调整期任务：按 S06 未发布可见性返回 404（访问范围问题另记，见开发日志）
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId + "/tasks")
                .session(member.session()))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/materials")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"调整期物资\",\"quantity\":1,\"unit\":\"条\"}"))
            .andExpect(status().isConflict());

        // 重新确认后恢复可写
        submitForApproval(owner, orgId, activityId);
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/tasks")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"重新确认后任务\",\"assigneeMemberId\":%d}").formatted(memberMemberId)))
            .andExpect(status().isCreated());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM task WHERE activity_id = ?", Integer.class, activityId)).isEqualTo(2);
    }

    @Test
    void deleteDraftBlockedByConfirmationHistoryOrDependencies() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);

        // 从未确认、无依赖的初始草稿可以删除
        MvcResult plain = mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(activityBody("初始草稿", memberIdOf(orgId, owner.username()), "2026-12-28T14:00:00+08:00")))
            .andExpect(status().isCreated())
            .andReturn();
        String plainId = readField(plain, "$.id");
        mockMvc.perform(delete("/api/organizations/" + orgId + "/activities/" + plainId)
                .with(csrf()).session(owner.session()))
            .andExpect(status().isNoContent());

        // 曾确认后撤回：不得删除
        Long confirmed = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2026-12-29T14:00:00+08:00");
        withdrawToDraft(owner, orgId, confirmed);
        mockMvc.perform(delete("/api/organizations/" + orgId + "/activities/" + confirmed)
                .with(csrf()).session(owner.session()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ACTIVITY_DELETE_CONFLICT"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM activity WHERE id = ?", Integer.class, confirmed)).isEqualTo(1);

        // 有任务依赖的初始草稿：不得删除，并说明依赖类型
        MvcResult withTask = mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(activityBody("有依赖草稿", memberIdOf(orgId, owner.username()), "2026-12-30T14:00:00+08:00")))
            .andExpect(status().isCreated())
            .andReturn();
        String withTaskId = readField(withTask, "$.id");
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + withTaskId + "/tasks")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"依赖任务\",\"assigneeMemberId\":%d}")
                    .formatted(memberIdOf(orgId, owner.username()))))
            .andExpect(status().isCreated());
        mockMvc.perform(delete("/api/organizations/" + orgId + "/activities/" + withTaskId)
                .with(csrf()).session(owner.session()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ACTIVITY_HAS_DEPENDENCIES"))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("任务")));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM activity WHERE id = ?", Integer.class, withTaskId)).isEqualTo(1);
    }
}
