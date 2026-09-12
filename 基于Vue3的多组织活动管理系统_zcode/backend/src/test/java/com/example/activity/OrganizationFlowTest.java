package com.example.activity;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import com.example.activity.support.ActivityTestBase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 组织链路（P1.2）：全部经真实注册与登录入口建立身份——
 * 创建者成为首位负责人、本人目录、有效成员视角详情、跨组织越权 404。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class OrganizationFlowTest extends ActivityTestBase {

    private static final String PASSWORD = "Passw0rd123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private record TestSession(String username, MockHttpSession session) {
    }

    private TestSession registerAndLogin() throws Exception {
        String username = "u" + Long.toString(System.nanoTime(), 36);
        mockMvc.perform(post("/api/auth/register").with(csrf())
                .with(request -> {
                    // 每个测试身份使用独立来源 IP，避免共享限流窗口影响用例独立性。
                    request.setRemoteAddr("10.%d.%d.%d".formatted(
                        (username.hashCode() >> 8) & 0xFF, username.hashCode() & 0xFF, 7));
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD)))
            .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/auth/login").with(csrf())
                .param("username", username)
                .param("password", PASSWORD))
            .andExpect(status().isNoContent())
            .andReturn();
        return new TestSession(username, (MockHttpSession) login.getRequest().getSession());
    }

    private Long createOrganization(TestSession owner, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/organizations").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\",\"description\":\"测试组织\"}".formatted(name)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.role").value("OWNER"))
            .andReturn();
        return Long.parseLong(com.jayway.jsonpath.JsonPath.read(
            result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.id"));
    }

    @Test
    void creatorBecomesOwnerAndDetailVisibleToMember() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrganization(owner, "测试组织-" + System.nanoTime());

        mockMvc.perform(get("/api/organizations/" + orgId).session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("OWNER"))
            .andExpect(jsonPath("$.memberCount").value(1))
            .andExpect(jsonPath("$.name").isNotEmpty());

        Integer audits = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'ORGANIZATION_CREATED' AND target = ?",
            Integer.class, "organization:" + orgId);
        org.assertj.core.api.Assertions.assertThat(audits).isEqualTo(1);
    }

    @Test
    void nonMemberGetsSame404AsUnknownOrganization() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession outsider = registerAndLogin();
        Long orgId = createOrganization(owner, "越权检查-" + System.nanoTime());

        MvcResult outsiderResult = mockMvc.perform(get("/api/organizations/" + orgId).session(outsider.session()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andReturn();
        MvcResult unknownResult = mockMvc.perform(get("/api/organizations/999999999").session(outsider.session()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andReturn();

        // 两种 404 响应体一致，不泄露组织存在性
        String outsiderBody = outsiderResult.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String unknownBody = unknownResult.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        com.fasterxml.jackson.databind.node.ObjectNode outsiderNode =
            (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(outsiderBody);
        com.fasterxml.jackson.databind.node.ObjectNode unknownNode =
            (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(unknownBody);
        outsiderNode.remove("requestId");
        unknownNode.remove("requestId");
        // instance 为请求方自身路径，剔除后两种 404 响应体必须一致
        outsiderNode.remove("instance");
        unknownNode.remove("instance");
        org.assertj.core.api.Assertions.assertThat(outsiderNode).isEqualTo(unknownNode);
    }

    @Test
    void directoryListsOnlyOwnMembershipsInJoinOrder() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        String suffix = Long.toString(System.nanoTime(), 36);
        Long orgA = createOrganization(owner, "甲组织-" + suffix);
        Long orgB = createOrganization(owner, "乙组织-" + suffix);

        // 组织负责人把成员拉入组织：P1.3 才有邀请流程；此处直接以数据层准备成员关系（仅测试库），
        // 目录接口本身仍走真实登录入口验证。
        Long memberAccountId = jdbcTemplate.queryForObject(
            "SELECT id FROM account WHERE username = ?", Long.class, member.username());
        jdbcTemplate.update(
            "INSERT INTO organization_member (org_id, account_id, role, status) VALUES (?, ?, 'MEMBER', 'ACTIVE')",
            orgA, memberAccountId);

        MvcResult directory = mockMvc.perform(get("/api/me/organizations").session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].id").value(String.valueOf(orgA)))
            .andExpect(jsonPath("$.items[0].role").value("OWNER"))
            .andExpect(jsonPath("$.items[1].id").value(String.valueOf(orgB)))
            .andReturn();
        JsonNode items = objectMapper.readTree(directory.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        org.assertj.core.api.Assertions.assertThat(items.get("items").size()).isEqualTo(2);

        mockMvc.perform(get("/api/me/organizations").session(member.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(String.valueOf(orgA)))
            .andExpect(jsonPath("$.items[0].role").value("MEMBER"));
    }
}
