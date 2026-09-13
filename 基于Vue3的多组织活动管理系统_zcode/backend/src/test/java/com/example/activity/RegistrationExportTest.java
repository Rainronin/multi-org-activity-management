package com.example.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
 * S11 名单导出回归：权限与状态筛选沿用管理名单规则；CSV 记录导出操作；
 * 用户填入的姓名／备注不会被表格软件当公式执行（单元格前置单引号）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class RegistrationExportTest extends FlowTestSupport {

    private String exportCsv(TestSession actor, Long orgId, Long activityId, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId
                + "/registrations/export")
                .session(actor.session()))
            .andExpect(status().is(expectedStatus))
            .andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void exportRequiresManagePermissionAndEscapesFormulaCells() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession delegate = registerAndLogin();
        TestSession member = registerAndLogin();
        TestSession outsider = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, delegate, orgId);
        inviteAndAccept(owner, member, orgId);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, delegate.username()),
            "2027-06-01T14:00:00+08:00");
        openRegistration(delegate, orgId, activityId);

        // 报名者的展示姓名与备注带有公式起始字符
        mockMvc.perform(post("/api/activities/" + activityId + "/registrations")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"=1+1\",\"note\":\"@SUM(A1:A9)\"}"))
            .andExpect(status().isCreated());

        // 无关成员（不属于该组织）既读不到活动也导不出：统一 404，不泄露存在性
        exportCsv(outsider, orgId, activityId, 404);
        // 跨组织导出同样 404
        Long otherOrgId = createOrg(outsider);
        exportCsv(outsider, otherOrgId, activityId, 404);

        // 活动负责人可以导出：BOM、表头、中文状态与转义后的单元格
        MvcResult result = mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId
                + "/registrations/export")
                .session(delegate.session()))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/csv")))
            .andReturn();
        String csv = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFF");
        assertThat(csv).contains("报名ID,账号,展示姓名,状态");
        assertThat(csv).contains("\"'=1+1\"");
        assertThat(csv).contains("\"'@SUM(A1:A9)\"");
        assertThat(csv).contains("待审核");
        // 原始未转义写法不得出现（否则表格软件会执行公式）
        assertThat(csv).doesNotContain("\"=1+1\"");

        // 导出操作留下审计
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'REGISTRATION_EXPORTED' AND target = ?",
            Integer.class, "activity:" + activityId)).isEqualTo(1);

        // 状态筛选沿用管理名单规则：筛 APPROVED 时待审记录不出现
        String approvedOnly = exportCsv(delegate, orgId, activityId, 200);
        MvcResult filtered = mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId
                + "/registrations/export").param("status", "APPROVED").session(delegate.session()))
            .andExpect(status().isOk())
            .andReturn();
        String filteredCsv = filtered.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(approvedOnly).contains("待审核");
        assertThat(filteredCsv).doesNotContain("待审核");
    }
}
