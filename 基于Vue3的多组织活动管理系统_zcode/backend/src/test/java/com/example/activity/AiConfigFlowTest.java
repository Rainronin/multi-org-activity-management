package com.example.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.activity.support.FlowTestSupport;
import com.example.activity.support.StubModelServer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * P8.1 组织模型配置回归：密钥加密保存且不回显、版本与 connectionRevision 合同、测试与启用的先后关系、
 * 配置变化使旧测试失效，以及非负责人只读可用状态。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AiConfigFlowTest extends FlowTestSupport {

    private static final String API_KEY = "sk-test-secret-1234567890";
    private static final String MASTER_KEY = Base64.getEncoder()
        .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private static StubModelServer stub;

    @DynamicPropertySource
    static void aiProperties(DynamicPropertyRegistry registry) {
        registry.add("app.ai.allow-private-hosts", () -> true);
        registry.add("app.ai.master-key", () -> MASTER_KEY);
        registry.add("app.ai.master-key-id", () -> "test-key-v1");
        registry.add("app.ai.test-timeout-seconds", () -> 2);
        registry.add("app.ai.max-calls-per-day-per-org", () -> 50);
        registry.add("app.ai.max-calls-per-five-minutes-per-org", () -> 40);
    }

    @BeforeAll
    static void startStub() {
        stub = new StubModelServer();
    }

    @AfterAll
    static void stopStub() {
        stub.close();
    }

    @BeforeEach
    void resetStub() {
        stub.reset();
        // 本类统一验证 Anthropic 协议，桩按该协议返回 content 文本块
        stub.setShape("anthropic");
    }

    private String configPath(Long orgId) {
        return "/api/organizations/" + orgId + "/ai/config";
    }

    private String saveBody(String protocol, String endpoint, String authScheme, String apiKey, Long version) {
        return ("{\"protocol\":\"%s\",\"endpoint\":\"%s\",\"model\":\"stub-model\",\"authScheme\":\"%s\","
            + "\"apiKey\":%s,\"maxOutputTokens\":128,\"outputLimitField\":\"MAX_TOKENS\","
            + "\"timeoutSeconds\":10%s}")
            .formatted(protocol, endpoint, authScheme,
                apiKey == null ? "null" : "\"" + apiKey + "\"",
                version == null ? "" : ",\"version\":" + version);
    }

    private MvcResult save(Long orgId, TestSession owner, String protocol, String endpoint, String apiKey, Long version)
        throws Exception {
        return mockMvc.perform(put(configPath(orgId)).with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveBody(protocol, endpoint, "X_API_KEY", apiKey, version)))
            .andExpect(status().isOk())
            .andReturn();
    }

    /** 配置版本从库中读取，避免依赖响应中 JSON 数字的 Java 具体类型。 */
    private long configVersion(Long orgId) {
        return jdbcTemplate.queryForObject("SELECT version FROM ai_config WHERE org_id = ?", Long.class, orgId);
    }

    @Test
    void configIsEncryptedSavedWithoutEchoAndGuardedByVersion() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        String endpoint = stub.baseUrl() + "/v1/messages";

        MvcResult saved = save(orgId, owner, "ANTHROPIC_MESSAGES", endpoint, API_KEY, null);
        String body = saved.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain(API_KEY).contains("\"hasApiKey\":true", "\"enabled\":false",
            "\"connectionRevision\":1", "\"testedRevision\":null", "\"viewerRole\":\"OWNER\"");
        long version = configVersion(orgId);

        // 密钥只以密文保存：库里既不是明文，也不是它的散列
        byte[] cipher = jdbcTemplate.queryForObject(
            "SELECT api_key_cipher FROM ai_config WHERE org_id = ?", byte[].class, orgId);
        assertThat(cipher).isNotNull();
        assertThat(new String(cipher, StandardCharsets.ISO_8859_1)).doesNotContain(API_KEY);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT api_key_key_id FROM ai_config WHERE org_id = ?", String.class, orgId))
            .isEqualTo("test-key-v1");

        // 查询不回显密钥与密文
        MvcResult read = mockMvc.perform(get(configPath(orgId)).session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasApiKey").value(true))
            .andExpect(jsonPath("$.endpoint").value(endpoint))
            .andReturn();
        assertThat(read.getResponse().getContentAsString(StandardCharsets.UTF_8))
            .doesNotContain(API_KEY).doesNotContain("apiKeyCipher");

        // 保存校验版本：版本不符被拒绝（版本自 0 起）
        mockMvc.perform(put(configPath(orgId)).with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveBody("ANTHROPIC_MESSAGES", endpoint, "X_API_KEY", null, version + 1)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("EDIT_CONFLICT"));
        // 版本缺失同样被拒绝
        mockMvc.perform(put(configPath(orgId)).with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveBody("ANTHROPIC_MESSAGES", endpoint, "X_API_KEY", null, null)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors[0].field").value("version"));

        // 未通过当前版本测试不能启用
        mockMvc.perform(post(configPath(orgId) + "/enable").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("AI_TEST_REQUIRED"));

        // 修改协议或地址必须重新输入密钥，不允许把旧密钥静默发到新地址
        mockMvc.perform(put(configPath(orgId)).with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveBody("ANTHROPIC_MESSAGES", stub.baseUrl() + "/other/messages", "X_API_KEY", null, version)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors[0].field").value("apiKey"));

        // 结构化校验：凭据不允许写进地址
        mockMvc.perform(put(configPath(orgId)).with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveBody("ANTHROPIC_MESSAGES", "https://user:pass@localhost/v1/messages",
                    "X_API_KEY", API_KEY, version)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("AI_ENDPOINT_REJECTED"));

        // 清除密钥：同时停用并使测试状态失效
        mockMvc.perform(post(configPath(orgId) + "/clear-key").with(csrf())
                .session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasApiKey").value(false))
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.connectionRevision").value(2))
            .andExpect(jsonPath("$.configState").value("NEEDS_KEY"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT api_key_cipher FROM ai_config WHERE org_id = ?", byte[].class, orgId)).isNull();
    }

    @Test
    void saveWithExplicitClearKeyRemovesCipherAndInvalidatesAvailability() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        String endpoint = stub.baseUrl() + "/v1/messages";
        save(orgId, owner, "ANTHROPIC_MESSAGES", endpoint, API_KEY, null);
        mockMvc.perform(post(configPath(orgId) + "/test").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
            .andExpect(status().isOk());
        mockMvc.perform(post(configPath(orgId) + "/enable").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
            .andExpect(status().isOk());
        long version = configVersion(orgId);
        String clearBody = saveBody("ANTHROPIC_MESSAGES", endpoint, "X_API_KEY", null, version)
            .replace("\"apiKey\":null", "\"apiKey\":null,\"clearApiKey\":true");
        mockMvc.perform(put(configPath(orgId)).with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(clearBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasApiKey").value(false))
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.testedRevision").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.connectionRevision").value(2))
            .andExpect(jsonPath("$.version").value(version + 1));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT api_key_cipher IS NULL AND api_key_key_id IS NULL FROM ai_config WHERE org_id = ?",
            Boolean.class, orgId)).isTrue();
        mockMvc.perform(get(configPath(orgId)).session(owner.session()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.hasApiKey").value(false));
        mockMvc.perform(post(configPath(orgId) + "/enable").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + configVersion(orgId) + "}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AI_KEY_MISSING"));
        mockMvc.perform(put(configPath(orgId)).with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(clearBody))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EDIT_CONFLICT"));
        assertThat(configVersion(orgId)).isEqualTo(version + 1);
    }

    @Test
    void connectionTestBindsRevisionThenEnablesAndSurvivesEnable() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        String endpoint = stub.baseUrl() + "/v1/messages";
        save(orgId, owner, "ANTHROPIC_MESSAGES", endpoint, API_KEY, null);
        long version = configVersion(orgId);

        MvcResult tested = mockMvc.perform(post(configPath(orgId) + "/test").with(csrf())
                .session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.testedRevision").value(1))
            .andReturn();
        assertThat(tested.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain(API_KEY);
        // 测试只发送固定短文本，不发送组织资料
        assertThat(stub.lastRequest().body()).contains("连接正常").doesNotContain("测试组织", "简介");

        MvcResult enabled = mockMvc.perform(post(configPath(orgId) + "/enable").with(csrf())
                .session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.testedForCurrentRevision").value(true))
            .andExpect(jsonPath("$.available").value(true))
            // 单纯启用不使刚通过的测试失效：connectionRevision 不变
            .andExpect(jsonPath("$.connectionRevision").value(1))
            .andReturn();
        long afterEnable = configVersion(orgId);

        // 修改调用参数（输出上限）使 revision 递增、需要重新测试、并且不再可用
        mockMvc.perform(put(configPath(orgId)).with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"protocol\":\"ANTHROPIC_MESSAGES\",\"endpoint\":\"%s\",\"model\":\"stub-model\","
                    + "\"authScheme\":\"X_API_KEY\",\"maxOutputTokens\":256,\"outputLimitField\":\"MAX_TOKENS\","
                    + "\"timeoutSeconds\":10,\"version\":%d}").formatted(endpoint, afterEnable)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.connectionRevision").value(2))
            .andExpect(jsonPath("$.testedRevision").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.configState").value("NEEDS_TEST"));
        long afterChange = configVersion(orgId);

        // 旧版本测试通过不能标记新配置：直接启用仍被拒绝
        mockMvc.perform(post(configPath(orgId) + "/enable").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + afterChange + "}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("AI_TEST_REQUIRED"));

        // 密钥沿用（未传 apiKey）：保留原密文，重新测试后即可再次启用
        MvcResult retested = mockMvc.perform(post(configPath(orgId) + "/test").with(csrf())
                .session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + afterChange + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.testedRevision").value(2))
            .andReturn();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT api_key_cipher IS NOT NULL FROM ai_config WHERE org_id = ?", Boolean.class, orgId)).isTrue();
        assertThat(retested.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain(API_KEY);

        // 停用后保留配置，可再次启用；版本号变化同样受乐观锁保护
        mockMvc.perform(post(configPath(orgId) + "/disable").with(csrf())
                .session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + afterChange + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.configState").value("DISABLED"));
        long afterDisable = configVersion(orgId);
        mockMvc.perform(post(configPath(orgId) + "/disable").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + afterChange + "}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("EDIT_CONFLICT"));
        mockMvc.perform(post(configPath(orgId) + "/enable").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + afterDisable + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void testFailureIsReportedAndNeverMarksConfigTested() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        String endpoint = stub.baseUrl() + "/v1/messages";
        save(orgId, owner, "ANTHROPIC_MESSAGES", endpoint, API_KEY, null);
        long version = configVersion(orgId);

        stub.setMode(StubModelServer.Mode.UNAUTHORIZED);
        mockMvc.perform(post(configPath(orgId) + "/test").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("AI_UPSTREAM_AUTH"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT tested_revision FROM ai_config WHERE org_id = ?", Integer.class, orgId)).isNull();
        // 上游 401 只表示模型服务拒绝本次密钥，不影响本站登录状态
        mockMvc.perform(get("/api/me/organizations").session(owner.session())).andExpect(status().isOk());
        // 连接测试同样形成可核对的调用记录（计入额度）
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ai_generation WHERE org_id = ? AND type = 'CONNECTION_TEST'",
            Long.class, orgId)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM ai_generation WHERE org_id = ? AND type = 'CONNECTION_TEST'", String.class, orgId))
            .isEqualTo("FAILED");
    }

    @Test
    void nonOwnerReadsOnlyAvailabilityAndCannotConfigure() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        TestSession outsider = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        String endpoint = stub.baseUrl() + "/v1/messages";
        save(orgId, owner, "ANTHROPIC_MESSAGES", endpoint, API_KEY, null);
        long version = configVersion(orgId);

        mockMvc.perform(get(configPath(orgId)).session(member.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.viewerRole").value("MEMBER"))
            .andExpect(jsonPath("$.model").value("stub-model"))
            .andExpect(jsonPath("$.endpoint").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.version").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.hasApiKey").value(true));

        mockMvc.perform(put(configPath(orgId)).with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveBody("ANTHROPIC_MESSAGES", endpoint, "X_API_KEY", API_KEY, version)))
            .andExpect(status().isForbidden());
        mockMvc.perform(post(configPath(orgId) + "/test").with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post(configPath(orgId) + "/clear-key").with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(get(configPath(orgId)).session(outsider.session()))
            .andExpect(status().isNotFound());
    }
}
