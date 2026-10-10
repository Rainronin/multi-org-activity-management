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

    private MvcResult createWithKey(TestSession actor, String key, String body) throws Exception {
        return mockMvc.perform(post("/api/organizations").with(csrf()).session(actor.session())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn();
    }

    @Test
    void creationKeyReplaysOriginalAndChecksCurrentMembership() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession other = registerAndLogin();
        String key = java.util.UUID.randomUUID().toString();
        String body = "{\"name\":\"创建结果核对\",\"description\":\"原创建内容\"}";
        MvcResult first = createWithKey(owner, key, body);
        org.assertj.core.api.Assertions.assertThat(first.getResponse().getStatus()).isEqualTo(201);
        JsonNode created = objectMapper.readTree(first.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        MvcResult replay = createWithKey(owner, key, body);
        org.assertj.core.api.Assertions.assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        JsonNode replayed = objectMapper.readTree(replay.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        org.assertj.core.api.Assertions.assertThat(replayed.get("id")).isEqualTo(created.get("id"));
        org.assertj.core.api.Assertions.assertThat(replayed.get("joinedAt")).isEqualTo(created.get("joinedAt"));
        MvcResult reused = createWithKey(owner, key, "{\"name\":\"不同创建内容\"}");
        org.assertj.core.api.Assertions.assertThat(reused.getResponse().getStatus()).isEqualTo(409);
        org.assertj.core.api.Assertions.assertThat(reused.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_REUSED");
        JsonNode separate = objectMapper.readTree(createWithKey(other, key, body).getResponse()
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        org.assertj.core.api.Assertions.assertThat(separate.get("id")).isNotEqualTo(created.get("id"));
        org.assertj.core.api.Assertions.assertThat(createWithKey(owner, " ", body).getResponse().getStatus()).isEqualTo(400);
        org.assertj.core.api.Assertions.assertThat(createWithKey(owner, "x".repeat(65), body).getResponse().getStatus()).isEqualTo(400);

        Long orgId = created.get("id").asLong();
        Long creatorId = jdbcTemplate.queryForObject("SELECT id FROM account WHERE username = ?", Long.class, owner.username());
        Long otherId = jdbcTemplate.queryForObject("SELECT id FROM account WHERE username = ?", Long.class, other.username());
        // 仅准备测试库当前身份：核对接口须重新授权，不能重放旧 OWNER 视图。
        jdbcTemplate.update("INSERT INTO organization_member (org_id, account_id, role, status) VALUES (?, ?, 'OWNER', 'ACTIVE')", orgId, otherId);
        jdbcTemplate.update("UPDATE organization_member SET role = 'MEMBER' WHERE org_id = ? AND account_id = ?", orgId, creatorId);
        JsonNode demoted = objectMapper.readTree(createWithKey(owner, key, body).getResponse()
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        org.assertj.core.api.Assertions.assertThat(demoted.get("role").asText()).isEqualTo("MEMBER");
        jdbcTemplate.update("UPDATE organization_member SET status = 'REMOVED' WHERE org_id = ? AND account_id = ?", orgId, creatorId);
        org.assertj.core.api.Assertions.assertThat(createWithKey(owner, key, body).getResponse().getStatus()).isEqualTo(404);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'ORGANIZATION_CREATED' AND target = ?", Integer.class, "organization:" + orgId)).isEqualTo(1);
    }

    @Test
    void concurrentCreationKeyCreatesOneOrganizationAndMembership() throws Exception {
        TestSession owner = registerAndLogin();
        String key = java.util.UUID.randomUUID().toString();
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(6)) {
            var calls = new java.util.ArrayList<java.util.concurrent.Future<MvcResult>>();
            for (int i = 0; i < 6; i++) calls.add(pool.submit(() -> {
                gate.await();
                return createWithKey(owner, key, "{\"name\":\"并发创建核对\"}");
            }));
            gate.countDown();
            var ids = new java.util.HashSet<Long>();
            for (var call : calls) {
                MvcResult result = call.get(30, java.util.concurrent.TimeUnit.SECONDS);
                org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus()).isIn(201, 409);
                if (result.getResponse().getStatus() == 201) ids.add(objectMapper.readTree(
                    result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("id").asLong());
                else org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString()).contains("REQUEST_IN_PROGRESS");
            }
            org.assertj.core.api.Assertions.assertThat(ids).hasSize(1);
            Long orgId = ids.iterator().next();
            org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM organization_member WHERE org_id = ?", Integer.class, orgId)).isEqualTo(1);
            org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'ORGANIZATION_CREATED' AND target = ?", Integer.class, "organization:" + orgId)).isEqualTo(1);
        }
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
