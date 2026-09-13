package com.example.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
 * S08 回归：编辑冲突与创建防重。
 * 覆盖：PATCH／任务／物资的旧版本保存被拒（409 EDIT_CONFLICT）；version 随写操作递增；
 * 创建活动必须携带 Idempotency-Key，同键同内容返回原资源、同键异内容 409。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class EditConflictTest extends FlowTestSupport {

    private Long versionOf(Long activityId) {
        return jdbcTemplate.queryForObject(
            "SELECT version FROM activity WHERE id = ?", Long.class, activityId);
    }

    private MvcResult patchActivity(Long orgId, Long activityId, TestSession actor, String body)
        throws Exception {
        return mockMvc.perform(patch("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();
    }

    @Test
    void staleActivityEditIsRejectedAndVersionIncrements() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long ownerMemberId = memberIdOf(orgId, owner.username());
        MvcResult created = mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(activityBody("冲突活动", ownerMemberId, "2027-02-01T14:00:00+08:00")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.version").value(1))
            .andReturn();
        Long activityId = Long.parseLong(readField(created, "$.id"));

        // 第一方保存成功，版本递增
        MvcResult firstSave = patchActivity(orgId, activityId, owner, "{\"location\":\"第一方改的地点\",\"version\":1}");
        assertThat(firstSave.getResponse().getStatus()).isEqualTo(200);
        assertThat(versionOf(activityId)).isEqualTo(2);

        // 第二方基于旧版本 1 保存：拒绝而不是静默覆盖
        MvcResult staleSave = patchActivity(orgId, activityId, owner, "{\"location\":\"第二方改的地点\",\"version\":1}");
        assertThat(staleSave.getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT location FROM activity WHERE id = ?", String.class, activityId))
            .isEqualTo("第一方改的地点");
        assertThat(versionOf(activityId)).isEqualTo(2);

        // 基于当前版本可以继续保存
        MvcResult secondSave = patchActivity(orgId, activityId, owner,
            "{\"location\":\"第二方刷新后改的地点\",\"version\":2}");
        assertThat(secondSave.getResponse().getStatus()).isEqualTo(200);
        assertThat(versionOf(activityId)).isEqualTo(3);

        // 不携带版本：保持旧客户端可用，仍可保存（合同已在接口说明标注）
        MvcResult noVersion = patchActivity(orgId, activityId, owner, "{\"description\":\"无版本保存\"}");
        assertThat(noVersion.getResponse().getStatus()).isEqualTo(200);
        assertThat(versionOf(activityId)).isEqualTo(4);
    }

    @Test
    void statusActionsAlsoBumpActivityVersion() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-02-02T14:00:00+08:00");
        long afterApproval = versionOf(activityId);

        // 报名开关变更也走同一版本序列
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/registration-open")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"open\":true}"))
            .andExpect(status().isOk());
        assertThat(versionOf(activityId)).isEqualTo(afterApproval + 1);

        // 撤回后基于当前版本编辑正常；版本继续递增
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/withdraw-approval")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"调整\"}"))
            .andExpect(status().isOk());
        long afterWithdraw = versionOf(activityId);
        mockMvc.perform(patch("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"撤回后的简介\"}"))
            .andExpect(status().isOk());
        assertThat(versionOf(activityId)).isEqualTo(afterWithdraw + 1);
    }

    @Test
    void staleTaskAndMaterialEditsAreRejected() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long ownerMemberId = memberIdOf(orgId, owner.username());
        Long activityId = createApprovedActivity(owner, orgId, ownerMemberId, "2027-02-03T14:00:00+08:00");

        // 任务：第一次改派成功（版本 1 → 2），基于旧版本再改派被拒
        MvcResult task = mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/tasks")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"冲突任务\",\"assigneeMemberId\":%d}").formatted(ownerMemberId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.version").value(1))
            .andReturn();
        String taskId = readField(task, "$.taskId");
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/tasks/" + taskId + "/update")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"任务已改名\",\"assigneeMemberId\":%d,\"version\":1}")
                    .formatted(ownerMemberId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/tasks/" + taskId + "/update")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"旧版本覆盖\",\"assigneeMemberId\":%d,\"version\":1}")
                    .formatted(ownerMemberId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("EDIT_CONFLICT"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT title FROM task WHERE id = ?", String.class, Long.parseLong(taskId)))
            .isEqualTo("任务已改名");

        // 物资：同一规则
        MvcResult material = mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/materials")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"冲突物资\",\"quantity\":1,\"unit\":\"条\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.version").value(1))
            .andReturn();
        String materialId = readField(material, "$.materialId");
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/materials/" + materialId + "/update")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"物资已改名\",\"quantity\":2,\"unit\":\"条\",\"version\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/materials/" + materialId + "/update")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"旧版本覆盖\",\"quantity\":9,\"unit\":\"条\",\"version\":1}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("EDIT_CONFLICT"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT name FROM material WHERE id = ?", String.class, Long.parseLong(materialId)))
            .isEqualTo("物资已改名");
    }

    @Test
    void createIsIdempotentPerRequestKey() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long ownerMemberId = memberIdOf(orgId, owner.username());
        String key = newIdempotencyKey();
        String body = activityBody("防重活动", ownerMemberId, "2027-02-04T14:00:00+08:00");

        MvcResult first = mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", key)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn();
        String firstId = readField(first, "$.id");

        // 同键同内容重放：返回原资源，不再建草稿
        MvcResult replay = mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", key)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn();
        assertThat(readField(replay, "$.id")).isEqualTo(firstId);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM activity WHERE org_id = ? AND name = '防重活动'",
            Integer.class, orgId)).isEqualTo(1);

        // 同键异内容：409，不静默改写既有草稿
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", key)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(activityBody("另一个活动", ownerMemberId, "2027-02-05T14:00:00+08:00")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM activity WHERE org_id = ?", Integer.class, orgId)).isEqualTo(1);

        // 换键可正常新建
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(activityBody("另一个活动", ownerMemberId, "2027-02-05T14:00:00+08:00")))
            .andExpect(status().isCreated());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM activity WHERE org_id = ?", Integer.class, orgId)).isEqualTo(2);
    }
}
