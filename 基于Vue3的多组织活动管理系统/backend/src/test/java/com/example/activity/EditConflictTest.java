package com.example.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * 覆盖：活动／任务／物资的旧版本保存被拒（409 EDIT_CONFLICT）；编辑、方案保存与审批缺版本被拒（400）；
 * 撤回重提后的旧审批被拒；version 随写操作递增；创建活动必须携带 Idempotency-Key，
 * 同键同内容返回原资源、同键异内容 409。
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

        // F05：不携带版本不再兼容保存，直接 400 且不写入
        MvcResult noVersion = patchActivity(orgId, activityId, owner, "{\"description\":\"无版本保存\"}");
        assertThat(noVersion.getResponse().getStatus()).isEqualTo(400);
        assertThat(noVersion.getResponse().getContentAsString()).contains("VALIDATION_FAILED");
        assertThat(versionOf(activityId)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT description FROM activity WHERE id = ?", String.class, activityId))
            .isNotEqualTo("无版本保存");
    }

    /** F05：编辑缺版本即拒绝（活动／任务／物资同一合同）。 */
    @Test
    void missingVersionIsRejectedOnEdits() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long ownerMemberId = memberIdOf(orgId, owner.username());
        Long activityId = createApprovedActivity(owner, orgId, ownerMemberId, "2027-02-06T14:00:00+08:00");

        // 活动编辑缺版本
        mockMvc.perform(patch("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"缺版本\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("version"));

        // 任务改派缺版本
        MvcResult task = mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/tasks")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"缺版本任务\",\"assigneeMemberId\":%d}").formatted(ownerMemberId)))
            .andExpect(status().isCreated())
            .andReturn();
        String taskId = readField(task, "$.taskId");
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/tasks/" + taskId + "/update")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"缺版本改名\",\"assigneeMemberId\":%d}").formatted(ownerMemberId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("version"));

        // 物资更新缺版本
        MvcResult material = mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/materials")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"缺版本物资\",\"quantity\":1,\"unit\":\"条\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        String materialId = readField(material, "$.materialId");
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/materials/" + materialId + "/update")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"缺版本改名\",\"quantity\":2,\"unit\":\"条\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("version"));

        assertThat(jdbcTemplate.queryForObject(
            "SELECT title FROM task WHERE id = ?", String.class, Long.parseLong(taskId)))
            .isEqualTo("缺版本任务");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT name FROM material WHERE id = ?", String.class, Long.parseLong(materialId)))
            .isEqualTo("缺版本物资");
    }

    /** F05：方案保存携带过期版本被拒，不覆盖他人已保存的正文。 */
    @Test
    void staleProposalSaveIsRejected() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long ownerMemberId = memberIdOf(orgId, owner.username());
        MvcResult created = mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(activityBody("方案冲突活动", ownerMemberId, "2027-02-07T14:00:00+08:00")))
            .andExpect(status().isCreated())
            .andReturn();
        Long activityId = Long.parseLong(readField(created, "$.id"));
        Long firstVersion = versionOf(activityId);

        mockMvc.perform(put("/api/organizations/" + orgId + "/activities/" + activityId + "/proposal")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"第一版方案\",\"version\":" + firstVersion + "}"))
            .andExpect(status().isOk());

        // 携带过期版本：409 且正文不变
        mockMvc.perform(put("/api/organizations/" + orgId + "/activities/" + activityId + "/proposal")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"过期覆盖\",\"version\":" + firstVersion + "}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("EDIT_CONFLICT"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT proposal FROM activity WHERE id = ?", String.class, activityId))
            .isEqualTo("第一版方案");

        // 缺版本：400
        mockMvc.perform(put("/api/organizations/" + orgId + "/activities/" + activityId + "/proposal")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"无版本方案\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("version"));

        // 基于当前版本可以继续保存
        mockMvc.perform(put("/api/organizations/" + orgId + "/activities/" + activityId + "/proposal")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"第二版方案\",\"version\":" + versionOf(activityId) + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.proposal").value("第二版方案"));
    }

    /** F05：撤回重提后，基于旧版本发起的审批被拒，不会确认未经复核的新内容。 */
    @Test
    void staleApprovalAfterWithdrawAndResubmitIsRejected() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long ownerMemberId = memberIdOf(orgId, owner.username());
        MvcResult created = mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(activityBody("重提审批活动", ownerMemberId, "2027-02-08T14:00:00+08:00")))
            .andExpect(status().isCreated())
            .andReturn();
        Long activityId = Long.parseLong(readField(created, "$.id"));

        mockMvc.perform(put("/api/organizations/" + orgId + "/activities/" + activityId + "/proposal")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"content\":\"初次方案\"}")))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/submit")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isOk());
        Long reviewedVersion = versionOf(activityId);

        // 撤回 → 改内容 → 重新提交，出现新的待确认版本
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/withdraw")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isOk());
        mockMvc.perform(put("/api/organizations/" + orgId + "/activities/" + activityId + "/proposal")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"content\":\"重提后的方案\"}")))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/submit")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isOk());
        Long resubmittedVersion = versionOf(activityId);
        assertThat(resubmittedVersion).isGreaterThan(reviewedVersion);

        // 旧审批请求（基于撤回前版本）被拒，活动仍为待确认
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/approve")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"过期同意\",\"version\":" + reviewedVersion + "}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("EDIT_CONFLICT"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM activity WHERE id = ?", String.class, activityId))
            .isEqualTo("PENDING_APPROVAL");

        // 缺版本审批同样被拒
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/approve")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors[0].field").value("version"));

        // 基于当前版本审批通过
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/approve")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"重新复核同意\",\"version\":" + resubmittedVersion + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"));

        // 撤回确认也必须携带版本：过期版本不得撤掉新确认
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/withdraw-approval")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"过期撤回\",\"version\":" + reviewedVersion + "}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("EDIT_CONFLICT"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM activity WHERE id = ?", String.class, activityId))
            .isEqualTo("APPROVED");
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/withdraw-approval")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"reason\":\"按当前版本撤回\"}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DRAFT"));
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
                .content(versioned(activityId, "{\"reason\":\"调整\"}")))
            .andExpect(status().isOk());
        long afterWithdraw = versionOf(activityId);
        mockMvc.perform(patch("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"description\":\"撤回后的简介\"}")))
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

        // F06：活动被编辑后，原键＋原请求仍应返回原资源，而不是误判为内容不同
        Long firstActivityId = Long.parseLong(firstId);
        mockMvc.perform(patch("/api/organizations/" + orgId + "/activities/" + firstActivityId)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(firstActivityId, "{\"location\":\"改过的地点\"}")))
            .andExpect(status().isOk());
        MvcResult replayAfterEdit = mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", key)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn();
        assertThat(readField(replayAfterEdit, "$.id")).isEqualTo(firstId);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM activity WHERE org_id = ? AND name = '防重活动'",
            Integer.class, orgId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT location FROM activity WHERE id = ?", String.class, firstActivityId))
            .isEqualTo("改过的地点");
    }

    /** F06：同键并发创建由唯一约束兜底，不返回 500，结果按已提交记录恢复。 */
    @Test
    void concurrentCreateWithSameKeyRecoversCommittedResult() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long ownerMemberId = memberIdOf(orgId, owner.username());
        String key = newIdempotencyKey();
        String body = activityBody("并发防重活动", ownerMemberId, "2027-02-09T14:00:00+08:00");

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.List<java.util.concurrent.Callable<Integer>> calls = java.util.List.of(
                () -> createStatusCode(orgId, key, body, owner),
                () -> createStatusCode(orgId, key, body, owner));
            java.util.List<java.util.concurrent.Future<Integer>> results = pool.invokeAll(calls);
            for (java.util.concurrent.Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(201);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM activity WHERE org_id = ? AND name = '并发防重活动'",
            Integer.class, orgId)).isEqualTo(1);
    }

    private Integer createStatusCode(Long orgId, String key, String body, TestSession owner) {
        try {
            return mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                    .header("Idempotency-Key", key)
                    .with(csrf()).session(owner.session())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andReturn().getResponse().getStatus();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
