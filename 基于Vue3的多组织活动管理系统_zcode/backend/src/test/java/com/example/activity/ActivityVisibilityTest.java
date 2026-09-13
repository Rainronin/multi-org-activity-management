package com.example.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.activity.support.FlowTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

/**
 * S06 回归：活动列表、详情与任务／物资／附件子资源使用同一可见性。
 * 覆盖三组反例：普通成员查待确认列表、负责人查本人草稿、未发布子资源直达。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ActivityVisibilityTest extends FlowTestSupport {

    @Test
    void pendingApprovalIsNotListedForPlainMembers() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long ownerMemberId = memberIdOf(orgId, owner.username());

        Long pending = createDraftActivityOnly(owner, orgId, ownerMemberId);
        // 提交需要先有方案文稿（P2.2 完备性校验）
        mockMvc.perform(put("/api/organizations/" + orgId + "/activities/" + pending + "/proposal")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(pending, "{\"content\":\"方案\"}")))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + pending + "/submit")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isOk());
        Long approved = createApprovedActivity(owner, orgId, ownerMemberId, "2026-12-02T14:00:00+08:00");

        // 普通成员按待确认筛选：既不加总数，也不出现在列表
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities")
                .param("status", "PENDING_APPROVAL").session(member.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0))
            .andExpect(jsonPath("$.items.length()").value(0));

        // 默认列表同样不含未确认活动
        MvcResult defaultList = mockMvc.perform(get("/api/organizations/" + orgId + "/activities")
                .session(member.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andReturn();
        assertThat(readField(defaultList, "$.items[0].id")).isEqualTo(String.valueOf(approved));

        // 组织负责人仍可见待确认活动，且总数与条目一致
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities")
                .param("status", "PENDING_APPROVAL").session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items.length()").value(1));

        // 不指定状态＝“全部状态”：组织负责人同时看到已确认与未发布活动（列表页“全部状态”入口）
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities").session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.items.length()").value(2));

        // 普通成员不指定状态时看到已确认活动与本人负责的未发布活动，不含他人未发布活动
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities").session(member.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].id").value(String.valueOf(approved)));

        // 详情与列表结论一致
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + pending).session(member.session()))
            .andExpect(status().isNotFound());
    }

    @Test
    void activityOwnerSeesOwnDraftInListButNotOthers() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long ownerMemberId = memberIdOf(orgId, owner.username());
        Long memberMemberId = memberIdOf(orgId, member.username());

        // 本人负责的草稿：列表与详情都可见（S06 原缺陷为统一返回空列表）
        Long ownDraft = createDraftActivityOnly(owner, orgId, memberMemberId);
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities")
                .param("status", "DRAFT").session(member.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].id").value(String.valueOf(ownDraft)));
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + ownDraft).session(member.session()))
            .andExpect(status().isOk());

        // 他人负责的草稿：列表与详情都不可见
        Long otherDraft = createDraftActivityOnly(owner, orgId, ownerMemberId);
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + otherDraft).session(member.session()))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities")
                .param("status", "DRAFT").session(member.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].id").value(String.valueOf(ownDraft)));
    }

    private Long createDraftActivityOnly(TestSession owner, Long orgId, Long ownerMemberId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(activityBody("草稿活动", ownerMemberId, "2026-12-04T14:00:00+08:00")))
            .andExpect(status().isCreated())
            .andReturn();
        return Long.parseLong(readField(created, "$.id"));
    }

    @Test
    void unpublishedSubResourcesAreNotReachable() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long ownerMemberId = memberIdOf(orgId, owner.username());

        // F02 后草稿不能新建子资源，因此先造已确认活动、写入子资源，再撤回到草稿（未发布状态）
        Long draft = createApprovedActivity(owner, orgId, ownerMemberId, "2026-12-04T14:00:00+08:00");
        MvcResult task = mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + draft + "/tasks")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"草稿任务\",\"assigneeMemberId\":%d}").formatted(ownerMemberId)))
            .andExpect(status().isCreated())
            .andReturn();
        String taskId = readField(task, "$.taskId");
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + draft + "/materials")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"草稿物资\",\"quantity\":1,\"unit\":\"件\"}"))
            .andExpect(status().isCreated());
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
        MvcResult upload = mockMvc.perform(multipart(
                    "/api/organizations/" + orgId + "/activities/" + draft + "/attachments")
                .file(new MockMultipartFile("file", "草稿资料.png", "image/png", png))
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session()))
            .andExpect(status().isCreated())
            .andReturn();
        String attachmentId = readField(upload, "$.attachmentId");
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + draft + "/withdraw-approval")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(draft, "{\"reason\":\"调整\"}")))
            .andExpect(status().isOk());
        // 调整期待确认期间新增子资源被拒（F02）
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + draft + "/tasks")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"调整期任务\",\"assigneeMemberId\":%d}").formatted(ownerMemberId)))
            .andExpect(status().isConflict());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + draft + "/materials")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"调整期物资\",\"quantity\":1,\"unit\":\"件\"}"))
            .andExpect(status().isConflict());
        mockMvc.perform(multipart("/api/organizations/" + orgId + "/activities/" + draft + "/attachments")
                .file(new MockMultipartFile("file", "调整期资料.png", "image/png", png))
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session()))
            .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/organizations/" + orgId + "/activities/" + draft + "/attachments/" + attachmentId)
                .with(csrf()).session(owner.session()))
            .andExpect(status().isConflict());

        // 普通成员：详情、任务、物资、附件列表与下载全部 404
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + draft).session(member.session()))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + draft + "/tasks").session(member.session()))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + draft + "/materials").session(member.session()))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + draft + "/attachments").session(member.session()))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + draft + "/attachments/"
                + attachmentId + "/download").session(member.session()))
            .andExpect(status().isNotFound());
        // 普通成员也不能经由任务流转接口间接确认任务存在
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + draft + "/tasks/" + taskId + "/advance")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toStatus\":\"IN_PROGRESS\"}"))
            .andExpect(status().isNotFound());

        // 组织负责人：同一资源仍可正常读取
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + draft + "/tasks").session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + draft + "/materials").session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + draft + "/attachments/"
                + attachmentId + "/download").session(owner.session()))
            .andExpect(status().isOk());
    }
}
