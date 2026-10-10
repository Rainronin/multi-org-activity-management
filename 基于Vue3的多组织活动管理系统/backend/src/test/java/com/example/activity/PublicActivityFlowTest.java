package com.example.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.activity.support.FlowTestSupport;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

/**
 * S11 公开浏览回归：未登录只看到“已确认且公开报名”的活动；草稿、待确认与内部活动不可见；
 * 内部字段（预算、方案、审批信息、负责人、任务与附件）不出现在公开响应中。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PublicActivityFlowTest extends FlowTestSupport {

    /** 造一个公开报名的已确认活动（公开列表只收 PUBLIC 范围）。 */
    private Long createApprovedPublicActivity(TestSession owner, Long orgId, Long ownerMemberId, String name)
        throws Exception {
        MvcResult created = mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"name\":\"%s\",\"ownerMemberId\":%d,\"description\":\"公开简介\","
                    + "\"startTime\":\"2027-05-02T14:00:00+08:00\",\"endTime\":\"2027-05-02T16:00:00+08:00\","
                    + "\"location\":\"礼堂\",\"registrationScope\":\"PUBLIC\",\"capacity\":20,"
                    + "\"registrationStart\":\"2027-04-01T00:00:00+08:00\","
                    + "\"registrationEnd\":\"2027-04-30T00:00:00+08:00\"}").formatted(name, ownerMemberId)))
            .andExpect(status().isCreated())
            .andReturn();
        Long activityId = Long.parseLong(readField(created, "$.id"));
        submitForApproval(owner, orgId, activityId);
        return activityId;
    }

    @Test
    void publicListAndDetailExposeOnlyApprovedPublicFields() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long ownerMemberId = memberIdOf(orgId, owner.username());

        // 草稿与内部活动都不应进入公开信息流
        MvcResult draftResult = mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(activityBody("公开核对草稿", ownerMemberId, "2027-05-01T14:00:00+08:00")))
            .andExpect(status().isCreated())
            .andReturn();
        String draftId = readField(draftResult, "$.id");
        Long internalId = createApprovedActivity(owner, orgId, ownerMemberId, "2027-05-02T14:00:00+08:00");
        Long publicId = createApprovedPublicActivity(owner, orgId, ownerMemberId, "公开核对活动");

        // 匿名详情：已确认且公开报名的活动可读且只含公开字段
        MvcResult detail = mockMvc.perform(get("/api/public/activities/" + publicId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(String.valueOf(publicId)))
            .andExpect(jsonPath("$.name").value("公开核对活动"))
            .andExpect(jsonPath("$.organizationName").isNotEmpty())
            .andExpect(jsonPath("$.registrationOpen").exists())
            .andReturn();
        String detailBody = detail.getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (String banned : new String[] {"budget", "proposal", "reviewNote", "ownerMemberId",
            "ownerUsername", "submittedBy", "confirmedBy", "confirmType", "version", "requestKey"}) {
            assertThat(detailBody).doesNotContain(banned);
        }

        // 匿名列表：按活动 id 定位，确认公开活动在列表、草稿与内部活动不在
        MvcResult list = mockMvc.perform(get("/api/public/activities").param("pageSize", "50"))
            .andExpect(status().isOk())
            .andReturn();
        String listBody = list.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(listBody).contains("organizationName").contains("registrationOpen");
        assertThat(listBody).doesNotContain("\"id\":\"" + draftId + "\"");
        assertThat(listBody).doesNotContain("\"id\":\"" + internalId + "\"");

        // 内部活动与草稿不能通过公开详情读到
        mockMvc.perform(get("/api/public/activities/" + internalId))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/public/activities/" + draftId))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/public/activities/99999999"))
            .andExpect(status().isNotFound());

        // 撤回确认后不再公开
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + publicId + "/withdraw-approval")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(publicId, "{\"reason\":\"调整\"}")))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/public/activities/" + publicId))
            .andExpect(status().isNotFound());
    }

    @Test
    void publishedActivityStaysPublicAndExternalParticipantCanGiveFeedback() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession participant = registerAndLogin();
        TestSession stranger = registerAndLogin();
        Long orgId = createOrg(owner);
        Long ownerMemberId = memberIdOf(orgId, owner.username());
        // 报名窗口包含当前时间，才能在测试中真实报名
        MvcResult created = mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"name\":\"复盘期公开活动\",\"ownerMemberId\":%d,\"description\":\"公开简介\","
                    + "\"startTime\":\"2027-05-02T14:00:00+08:00\",\"endTime\":\"2027-05-02T16:00:00+08:00\","
                    + "\"location\":\"礼堂\",\"registrationScope\":\"PUBLIC\",\"capacity\":5,"
                    + "\"registrationStart\":\"2026-01-01T00:00:00+08:00\","
                    + "\"registrationEnd\":\"2027-04-30T00:00:00+08:00\"}").formatted(ownerMemberId)))
            .andExpect(status().isCreated())
            .andReturn();
        Long activityId = Long.parseLong(readField(created, "$.id"));
        submitForApproval(owner, orgId, activityId);
        openRegistration(owner, orgId, activityId);
        // 外部参与者：登录但不加入组织
        MvcResult applied = mockMvc.perform(post("/api/activities/" + activityId + "/registrations")
                .with(csrf()).session(participant.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"外部参与者\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        String registrationId = readField(applied, "$.registrationId");
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/registrations/" + registrationId + "/approve").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(registrationVersioned(registrationId, "{}")))
            .andExpect(status().isOk());

        // 已发布状态（执行中、复盘中）仍可从公开端点读取：外部参与者要能在参与后查询与反馈
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/start-execution")
                .with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{}")))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/public/activities/" + activityId)).andExpect(status().isOk());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/finish-execution")
                .with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{}")))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/public/activities/" + activityId)).andExpect(status().isOk());

        // 匿名读取反馈需要登录：不给匿名者暴露参与者评分
        mockMvc.perform(get("/api/public/activities/" + activityId + "/feedback"))
            .andExpect(status().isUnauthorized());
        // 非参与者（也不是组织成员）：读不到汇总、也提交不了（按 F01 口径统一 404，不泄露活动内情）
        mockMvc.perform(get("/api/public/activities/" + activityId + "/feedback").session(stranger.session()))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/public/activities/" + activityId + "/feedback").with(csrf())
                .session(stranger.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":5}"))
            .andExpect(status().isNotFound());

        // 参与者：只看到汇总与本人一份，可提交与修改
        mockMvc.perform(get("/api/public/activities/" + activityId + "/feedback").session(participant.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(0))
            .andExpect(jsonPath("$.canSubmit").value(true))
            .andExpect(jsonPath("$.mineSubmitted").value(false));
        mockMvc.perform(post("/api/public/activities/" + activityId + "/feedback").with(csrf())
                .session(participant.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":5,\"comment\":\"流程顺畅\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rating").value(5));
        mockMvc.perform(get("/api/public/activities/" + activityId + "/feedback").session(participant.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.mineSubmitted").value(true))
            .andExpect(jsonPath("$.myRating").value(5))
            .andExpect(jsonPath("$.myVersion").value(1))
            .andExpect(jsonPath("$.averageRating").value("5.0"));
        mockMvc.perform(post("/api/public/activities/" + activityId + "/feedback").with(csrf())
                .session(participant.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":4,\"comment\":\"无版本修改\"}"))
            .andExpect(status().isBadRequest());
        // 每账号一份：再次提交是修改而不是新增
        mockMvc.perform(post("/api/public/activities/" + activityId + "/feedback").with(csrf())
                .session(participant.session()).contentType(MediaType.APPLICATION_JSON)
                .content(feedbackVersioned(activityId, participant.username(), "{\"rating\":4,\"comment\":\"补充建议\"}")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(post("/api/public/activities/" + activityId + "/feedback").with(csrf())
                .session(participant.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":1,\"comment\":\"旧版本覆盖\",\"version\":1}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EDIT_CONFLICT"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM feedback WHERE activity_id = ?", Long.class, activityId)).isEqualTo(1L);
    }

    @Test
    void withdrawnActivityIsNoLongerPublic() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long approvedId = createApprovedPublicActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "撤回核对活动");
        mockMvc.perform(get("/api/public/activities/" + approvedId))
            .andExpect(status().isOk());

        // 撤回到草稿（调整期待确认）：未确认内容不再对外展示
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + approvedId + "/withdraw-approval")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(approvedId, "{\"reason\":\"安排调整\"}")))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/public/activities/" + approvedId))
            .andExpect(status().isNotFound());

        // 重新确认后再次公开
        submitForApproval(owner, orgId, approvedId);
        mockMvc.perform(get("/api/public/activities/" + approvedId))
            .andExpect(status().isOk());
    }
}
