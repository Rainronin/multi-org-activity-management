package com.example.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
 * 账号链路（P1.1）：注册→登录→当前身份→退出、恢复码闭环、重签、改密版本失效、限流。
 * 使用真实 MySQL 测试库，用户名按时间戳唯一化保证可重复执行。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AccountFlowTest extends ActivityTestBase {

    private static final String PASSWORD = "Passw0rd123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String uniqueUsername() {
        return "u" + Long.toString(System.nanoTime(), 36);
    }

    private MvcResult register(String username) throws Exception {
        return mockMvc.perform(post("/api/auth/register").with(csrf())
                .with(request -> {
                    // 注册限流按 IP 计数，测试身份使用独立 IP 保持用例独立。
                    request.setRemoteAddr("10.%d.%d.%d".formatted(
                        (username.hashCode() >> 8) & 0xFF, username.hashCode() & 0xFF, 7));
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.recoveryCode").isNotEmpty())
            .andReturn();
    }

    private String registerAndGetRecoveryCode(String username) throws Exception {
        MvcResult result = register(username);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        return body.get("recoveryCode").asText();
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login").with(csrf())
                .param("username", username)
                .param("password", password))
            .andExpect(status().isNoContent())
            .andReturn();
        return (MockHttpSession) login.getRequest().getSession();
    }

    @Test
    void registerLoginMeLogoutChain() throws Exception {
        String username = uniqueUsername();
        MvcResult registered = register(username);
        JsonNode body = objectMapper.readTree(registered.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long accountId = body.get("id").asLong();

        Integer audits = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'ACCOUNT_REGISTERED' AND target = ?",
            Integer.class, "account:" + accountId);
        assertThat(audits).isEqualTo(1);

        MockHttpSession session = login(username, PASSWORD);

        mockMvc.perform(get("/api/auth/me").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value(username))
            .andExpect(jsonPath("$.id").value(String.valueOf(accountId)));

        mockMvc.perform(post("/api/auth/logout").with(csrf()).session(session))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").session(session))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateUsernameIsRejected() throws Exception {
        String username = uniqueUsername();
        register(username);
        mockMvc.perform(post("/api/auth/register").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("username"));
    }

    @Test
    void wrongPasswordIsUnifiedFailure() throws Exception {
        String username = uniqueUsername();
        register(username);
        mockMvc.perform(post("/api/auth/login").with(csrf())
                .param("username", username)
                .param("password", "WrongPass123"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));

        // 未知账号返回同一错误，不泄露存在性
        mockMvc.perform(post("/api/auth/login").with(csrf())
                .param("username", uniqueUsername())
                .param("password", PASSWORD))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void recoverConsumesCodeRotatesAndInvalidatesOldPassword() throws Exception {
        String username = uniqueUsername();
        String originalCode = registerAndGetRecoveryCode(username);

        // 错误恢复码统一失败
        mockMvc.perform(post("/api/auth/recover").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"recoveryCode\":\"0000-0000-0000-0000-0000-0000-0000-0000\",\"newPassword\":\"%s\"}"
                    .formatted(username, PASSWORD)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));

        MvcResult recovered = mockMvc.perform(post("/api/auth/recover").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"recoveryCode\":\"%s\",\"newPassword\":\"NewPassw0rd456\"}"
                    .formatted(username, originalCode)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recoveryCode").isNotEmpty())
            .andReturn();
        String newCode = objectMapper.readTree(recovered.getResponse()
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("recoveryCode").asText();

        // 旧密码失效、新密码可登录
        mockMvc.perform(post("/api/auth/login").with(csrf())
                .param("username", username).param("password", PASSWORD))
            .andExpect(status().isUnauthorized());
        login(username, "NewPassw0rd456");

        // 已消费恢复码不能重复使用
        mockMvc.perform(post("/api/auth/recover").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"recoveryCode\":\"%s\",\"newPassword\":\"%s\"}"
                    .formatted(username, originalCode, PASSWORD)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));

        // 新码可用于再次恢复（同时验证恢复前旧码未过期语义）
        mockMvc.perform(post("/api/auth/recover").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"recoveryCode\":\"%s\",\"newPassword\":\"%s\"}"
                    .formatted(username, newCode, PASSWORD)))
            .andExpect(status().isOk());
    }

    @Test
    void reissueRequiresPasswordAndRevokesOldCode() throws Exception {
        String username = uniqueUsername();
        registerAndGetRecoveryCode(username);
        MockHttpSession session = login(username, PASSWORD);

        mockMvc.perform(post("/api/auth/recovery/reissue").with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"WrongPass123\"}"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/recovery/reissue").with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\"}".formatted(PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recoveryCode").isNotEmpty());

        Integer activeCodes = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM recovery_credential WHERE account_id = (SELECT id FROM account WHERE username = ?) AND status = 'ACTIVE'",
            Integer.class, username);
        assertThat(activeCodes).isEqualTo(1);
    }

    @Test
    void changePasswordKeepsCurrentSessionAndKillsOldSessions() throws Exception {
        String username = uniqueUsername();
        register(username);
        MockHttpSession session1 = login(username, PASSWORD);
        MockHttpSession session2 = login(username, PASSWORD);

        mockMvc.perform(post("/api/auth/password").with(csrf()).session(session1)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"NewPassw0rd456\"}".formatted(PASSWORD)))
            .andExpect(status().isNoContent());

        // 当前会话完成版本换绑，仍然可用
        mockMvc.perform(get("/api/auth/me").session(session1))
            .andExpect(status().isOk());

        // 旧版本会话失效
        mockMvc.perform(get("/api/auth/me").session(session2))
            .andExpect(status().isUnauthorized());

        // 新密码可再次登录
        login(username, "NewPassw0rd456");
    }

    @Test
    void loginRateLimitReturns429WithRetryAfter() throws Exception {
        String username = uniqueUsername();
        register(username);
        for (int i = 0; i < AuthRateLimit_MAX; i++) {
            mockMvc.perform(post("/api/auth/login").with(csrf())
                    .param("username", username)
                    .param("password", "WrongPass123"))
                .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/auth/login").with(csrf())
                .param("username", username)
                .param("password", PASSWORD))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    private static final int AuthRateLimit_MAX = 10;

    @Test
    void registerValidationReturnsFieldErrors() throws Exception {
        mockMvc.perform(post("/api/auth/register").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"ab\",\"password\":\"short\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors[?(@.field == 'username')]").exists())
            .andExpect(jsonPath("$.fieldErrors[?(@.field == 'password')]").exists());
    }
}
