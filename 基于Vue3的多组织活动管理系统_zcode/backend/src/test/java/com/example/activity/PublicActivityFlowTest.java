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
