package com.example.activity;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.example.activity.common.RequestIdFilter;
import com.example.activity.common.audit.AuditService;
import java.io.IOException;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import com.example.activity.support.FlowTestSupport;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接口合同检查：标识字符串、ProblemDetail 错误结构、requestId、CSRF 边界与字段校验。
 * 使用真实服务入口验证（开发规范 2.2、2.3、2.4）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ErrorContractTest extends FlowTestSupport {

    @Autowired
    private AuditService auditService;

    /** 仅测试用探针端点：验证写请求的 CSRF 与校验错误结构，不进入生产代码。 */
    @TestConfiguration
    static class ProbeConfiguration {

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    @RequestMapping("/api/probe")
    static class ProbeController {

        public record EchoRequest(@NotBlank String name) {
        }

        @PostMapping("/echo")
        public EchoRequest echo(@Valid @RequestBody EchoRequest body) {
            return body;
        }

        @PostMapping("/failure")
        public void failure(@RequestBody EchoRequest body) {
            IllegalStateException failure = new IllegalStateException(body.name(), new IOException(body.name()));
            failure.addSuppressed(new IllegalArgumentException(body.name()));
            throw failure;
        }
    }

    @Test
    void resourceAndActorIdentifiersAreStringsAndAbsentActorsStayNull() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long memberId = memberIdOf(orgId, owner.username());
        Long accountId = jdbcTemplate.queryForObject(
            "SELECT account_id FROM organization_member WHERE id = ?", Long.class, memberId);
        String activitiesPath = "/api/organizations/" + orgId + "/activities";
        MvcResult draft = mockMvc.perform(post(activitiesPath).with(csrf()).session(owner.session())
                .header("Idempotency-Key", newIdempotencyKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(activityBody("输出标识合同", memberId, "2027-09-05T14:00:00+08:00")))
            .andExpect(status().isCreated()).andReturn();
        Long activityId = Long.parseLong(readField(draft, "$.id"));
        String activityPath = activitiesPath + "/" + activityId;
        submitForApproval(owner, orgId, activityId);

        MvcResult task = mockMvc.perform(post(activityPath + "/tasks").with(csrf()).session(owner.session())
                .header("Idempotency-Key", newIdempotencyKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"合同任务\",\"assigneeMemberId\":%d}".formatted(memberId)))
            .andExpect(status().isCreated()).andReturn();
        MvcResult material = mockMvc.perform(post(activityPath + "/materials")
                .with(csrf()).session(owner.session()).header("Idempotency-Key", newIdempotencyKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"合同物资\",\"quantity\":1,\"unit\":\"件\"}"))
            .andExpect(status().isCreated()).andReturn();
        openRegistration(owner, orgId, activityId);
        MvcResult registration = mockMvc.perform(post("/api/activities/" + activityId + "/registrations")
                .with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"合同报名\"}"))
            .andExpect(status().isCreated()).andReturn();
        mockMvc.perform(post(activityPath + "/registrations/" + readField(registration, "$.registrationId")
                + "/approve").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(registrationVersioned(readField(registration, "$.registrationId"), "{}")))
            .andExpect(status().isOk());

        String expensesPath = activityPath + "/expenses";
        MvcResult expenseDraft = mockMvc.perform(post(expensesPath).with(csrf()).session(owner.session())
                .header("Idempotency-Key", newIdempotencyKey()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"合同支出\",\"amount\":\"1.00\",\"incurredOn\":\"2027-09-05\","
                    + "\"category\":\"OTHER\",\"noReceiptNote\":\"合同检查无票说明\"}"))
            .andExpect(status().isCreated()).andReturn();
        String expenseId = readField(expenseDraft, "$.expenseId");
        mockMvc.perform(post(expensesPath + "/" + expenseId + "/submit").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
            .andExpect(status().isOk());
        MvcResult expense = mockMvc.perform(post(expensesPath + "/" + expenseId + "/confirm")
                .with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":2,\"overBudgetNote\":\"合同核对超支说明\"}"))
            .andExpect(status().isOk()).andReturn();

        mockMvc.perform(post(activityPath + "/start-execution").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(versioned(activityId, "{}")))
            .andExpect(status().isOk());
        MvcResult record = mockMvc.perform(post(activityPath + "/execution-records")
                .with(csrf()).session(owner.session()).header("Idempotency-Key", newIdempotencyKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"PROGRESS\",\"content\":\"合同核对执行记录\"}"))
            .andExpect(status().isCreated()).andReturn();
        mockMvc.perform(post(activityPath + "/finish-execution").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(versioned(activityId, "{}")))
            .andExpect(status().isOk());
        MvcResult feedback = mockMvc.perform(post(activityPath + "/feedback")
                .with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":5,\"comment\":\"合同反馈\"}"))
            .andExpect(status().isOk()).andReturn();
        mockMvc.perform(put(activityPath + "/summary").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"summary\":\"合同核对总结\"}")))
            .andExpect(status().isOk());
        MvcResult activity = mockMvc.perform(post(activityPath + "/summary/confirm")
                .with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{}")))
            .andExpect(status().isOk()).andReturn();

        String invitationsPath = "/api/organizations/" + orgId + "/invitations";
        mockMvc.perform(post(invitationsPath).with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isCreated());
        MvcResult invitations = mockMvc.perform(get(invitationsPath).session(owner.session()))
            .andExpect(status().isOk()).andReturn();

        // AI 状态是独立历史夹具；只核对真实查询与采用响应，不在输出合同测试中调用模型。
        jdbcTemplate.update("INSERT INTO ai_config (org_id, protocol, endpoint, model, auth_scheme, "
                + "operator_account_id) VALUES (?, 'OPENAI_CHAT_COMPLETIONS', 'https://example.com/v1/chat/completions', "
                + "'contract-model', 'BEARER', ?)", orgId, accountId);
        String configPath = "/api/organizations/" + orgId + "/ai/config";
        MvcResult untestedConfig = mockMvc.perform(get(configPath).session(owner.session()))
            .andExpect(status().isOk()).andReturn();
        jdbcTemplate.update("UPDATE ai_config SET tested_by = ?, tested_at = CURRENT_TIMESTAMP(6), "
            + "tested_revision = connection_revision WHERE org_id = ?", accountId, orgId);
        MvcResult config = mockMvc.perform(get(configPath).session(owner.session()))
            .andExpect(status().isOk()).andReturn();
        String generationKey = newIdempotencyKey();
        jdbcTemplate.update("INSERT INTO ai_generation (org_id, activity_id, type, operator_account_id, "
                + "status, request_key, request_fingerprint, output_text) "
                + "VALUES (?, ?, 'ACTIVITY_SUMMARY', ?, 'SUCCEEDED', ?, ?, '合同草稿')",
            orgId, activityId, accountId, generationKey, "0".repeat(64));
        Long generationId = jdbcTemplate.queryForObject(
            "SELECT id FROM ai_generation WHERE org_id = ? AND request_key = ?", Long.class, orgId, generationKey);
        String generationPath = activityPath + "/ai/generations/" + generationId;
        MvcResult unadopted = mockMvc.perform(get(generationPath).session(owner.session()))
            .andExpect(status().isOk()).andReturn();
        MvcResult generation = mockMvc.perform(post(generationPath + "/adopt")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isOk()).andReturn();

        assertAll("资源与操作者标识必须是字符串，尚未发生的动作必须保留 null",
            () -> jsonPath("$.submittedBy").isString().match(activity),
            () -> jsonPath("$.confirmedBy").isString().match(activity),
            () -> jsonPath("$.summaryConfirmedBy").isString().match(activity),
            () -> jsonPath("$.recordedBy").isString().match(record),
            () -> jsonPath("$.submittedBy").isString().match(expense),
            () -> jsonPath("$.decidedBy").isString().match(expense),
            () -> jsonPath("$.activityId").isString().match(task),
            () -> jsonPath("$.activityId").isString().match(material),
            () -> jsonPath("$.accountId").isString().match(registration),
            () -> jsonPath("$.accountId").isString().match(feedback),
            () -> jsonPath("$.items[0].createdBy").isString().match(invitations),
            () -> jsonPath("$.testedBy").isString().match(config),
            () -> jsonPath("$.adoptedBy").isString().match(generation),
            () -> jsonPath("$.submittedBy").value(nullValue()).match(draft),
            () -> jsonPath("$.confirmedBy").value(nullValue()).match(draft),
            () -> jsonPath("$.summaryConfirmedBy").value(nullValue()).match(draft),
            () -> jsonPath("$.submittedBy").value(nullValue()).match(expenseDraft),
            () -> jsonPath("$.decidedBy").value(nullValue()).match(expenseDraft),
            () -> jsonPath("$.testedBy").value(nullValue()).match(untestedConfig),
            () -> jsonPath("$.adoptedBy").value(nullValue()).match(unadopted),
            () -> jsonPath("$.version").isNumber().match(activity),
            () -> jsonPath("$.capacity").isNumber().match(activity),
            () -> jsonPath("$.version").isNumber().match(expense),
            () -> jsonPath("$.quantity").isNumber().match(material),
            () -> jsonPath("$.items[0].usedCount").isNumber().match(invitations));
    }

    @Test
    void malformedPathAndQueryParametersReturn400() throws Exception {
        for (String value : new String[] {"invalid", "9223372036854775808"}) {
            mockMvc.perform(get("/api/public/activities/" + value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("activityId"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
            mockMvc.perform(get("/api/public/activities").param("page", value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("page"));
        }
        // 认证仍先于参数处理，非法参数不能绕过登录边界。
        mockMvc.perform(get("/api/organizations/invalid"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/api/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.database").value("UP"));
    }

    @Test
    void unknownResourceWithoutSessionReturnsProblemDetail401() throws Exception {
        mockMvc.perform(get("/api/organizations"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Content-Type", "application/problem+json;charset=UTF-8"))
            .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
            .andExpect(jsonPath("$.requestId").isNotEmpty())
            .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void csrfEndpointIsAnonymousAndReturnsRawToken() throws Exception {
        mockMvc.perform(get("/api/auth/csrf"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
            .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void writeWithoutCsrfIsRejectedWithCsrfInvalid() throws Exception {
        mockMvc.perform(post("/api/probe/echo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"活动\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void validationFailureReturnsFieldErrors() throws Exception {
        mockMvc.perform(post("/api/probe/echo")
                .with(csrf()).with(user("tester"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\" \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
    }

    @Test
    void validProbeRequestReturnsEcho() throws Exception {
        mockMvc.perform(post("/api/probe/echo")
                .with(csrf()).with(user("tester"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"活动\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("活动"));
    }

    @Test
    void unknownPathWithSessionReturns404() throws Exception {
        mockMvc.perform(get("/api/probe/missing").with(user("tester")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void auditKeepsOriginalServerRequestIdOnReplayAndAllowsNonHttpEntries() throws Exception {
        TestSession actor = registerAndLogin();
        String key = newIdempotencyKey();
        String body = "{\"name\":\"审计请求关联-" + System.nanoTime() + "\"}";
        MvcResult created = mockMvc.perform(post("/api/organizations").with(csrf()).session(actor.session())
                .header("Idempotency-Key", key).header("X-Request-Id", "client-cannot-select-audit-id")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn();
        String target = "organization:" + readField(created, "$.id");
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
            + "WHERE table_schema = DATABASE() AND table_name = 'audit_log' AND column_name = 'request_id'", Integer.class),
            "审计必须有可关联运行日志的服务端请求标识");
        String requestId = created.getResponse().getHeader("X-Request-Id");
        assertFalse(requestId.equals("client-cannot-select-audit-id"));
        assertEquals(requestId, jdbcTemplate.queryForObject(
            "SELECT request_id FROM audit_log WHERE target = ? AND action = 'ORGANIZATION_CREATED'", String.class, target));
        MvcResult replay = mockMvc.perform(post("/api/organizations").with(csrf()).session(actor.session())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn();
        assertEquals(readField(created, "$.id"), readField(replay, "$.id"));
        assertFalse(requestId.equals(replay.getResponse().getHeader("X-Request-Id")));
        assertEquals(requestId, jdbcTemplate.queryForObject(
            "SELECT request_id FROM audit_log WHERE target = ? AND action = 'ORGANIZATION_CREATED'", String.class, target));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE target = ? AND action = 'ORGANIZATION_CREATED'", Integer.class, target));
        String nonHttpTarget = "test:" + key;
        auditService.record(null, "TEST", "NON_HTTP_AUDIT", nonHttpTarget, null, null);
        assertEquals(null, jdbcTemplate.queryForObject("SELECT request_id FROM audit_log WHERE target = ?", String.class, nonHttpTarget));
    }

    @Test
    void requestLogsCoverMvcAndSecurityExitsWithoutPrivateValues() throws Exception {
        String secret = "DO_NOT_LOG_PRIVATE_PAYLOAD";
        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            assertCompletion(mockMvc.perform(get("/api/health").param("password", secret)
                    .header("Cookie", "private=" + secret).header("X-Request-Id", secret))
                .andExpect(status().isOk()).andReturn(), logs, "/api/health", 200, "NONE", Level.INFO);
            assertCompletion(mockMvc.perform(get("/api/organizations/" + secret))
                .andExpect(status().isUnauthorized()).andReturn(), logs, "UNMAPPED", 401, "AUTH_REQUIRED", Level.WARN);
            assertCompletion(mockMvc.perform(post("/api/probe/echo").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"" + secret + "\"}"))
                .andExpect(status().isForbidden()).andReturn(), logs, "UNMAPPED", 403, "CSRF_INVALID", Level.WARN);
            assertCompletion(mockMvc.perform(post("/api/probe/echo").with(user("tester")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest()).andReturn(), logs, "/api/probe/echo", 400, "VALIDATION_FAILED", Level.WARN);
            assertCompletion(mockMvc.perform(post("/api/probe/echo").with(user("tester")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(secret))
                .andExpect(status().isBadRequest()).andReturn(), logs, "/api/probe/echo", 400, "MALFORMED_REQUEST_BODY", Level.WARN);
            assertCompletion(mockMvc.perform(get("/api/public/" + secret))
                .andExpect(status().isNotFound()).andReturn(), logs, "/**", 404, "RESOURCE_NOT_FOUND", Level.WARN);
            assertCompletion(mockMvc.perform(post("/api/health").with(user("tester")).with(csrf()))
                .andExpect(status().isMethodNotAllowed()).andReturn(), logs, "UNMAPPED", 405, "METHOD_NOT_ALLOWED", Level.WARN);
            for (int attempt = 0; attempt < 11; attempt++) {
                MvcResult login = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", secret).param("password", secret).with(remoteAddr("10.254.253.252")))
                    .andExpect(status().is(attempt < 10 ? 401 : 429)).andReturn();
                assertCompletion(login, logs, "/api/auth/login", attempt < 10 ? 401 : 429,
                    attempt < 10 ? "AUTH_INVALID_CREDENTIALS" : "RATE_LIMITED", Level.WARN);
            }
            MvcResult failure = mockMvc.perform(post("/api/probe/failure").with(user("tester")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + secret + "\"}"))
                .andExpect(status().isInternalServerError()).andReturn();
            ILoggingEvent error = assertCompletion(failure, logs, "/api/probe/failure", 500, "INTERNAL_ERROR", Level.ERROR);
            assertNotNull(error.getThrowableProxy(), "500 必须保留可定位的脱敏堆栈");
            assertTrue(ThrowableProxyUtil.asString(error.getThrowableProxy()).contains(IllegalStateException.class.getName()));
            assertTrue(ThrowableProxyUtil.asString(error.getThrowableProxy()).contains(IOException.class.getName()));
            assertEquals(1, logs.list.stream().filter(event -> event.getLevel() == Level.ERROR).count(),
                "未预期异常只能在边界记录一次");
            for (ILoggingEvent event : logs.list) {
                assertFalse(event.getFormattedMessage().contains(secret), "日志不得包含路径／参数／凭据／正文");
                if (event.getThrowableProxy() != null) {
                    assertFalse(ThrowableProxyUtil.asString(event.getThrowableProxy()).contains(secret),
                        "异常消息及原因／suppressed 不得泄露正文");
                }
            }
        } finally {
            ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
        }
    }

    @Test
    void unexpectedExceptionMessagesAreExcludedFromLogs() throws Exception {
        String secret = "DO_NOT_LOG_PRIVATE_PAYLOAD";
        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            mockMvc.perform(post("/api/probe/failure").with(user("tester")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + secret + "\"}"))
                .andExpect(status().isInternalServerError());
            var errors = logs.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
            assertEquals(1, errors.size());
            assertFalse(ThrowableProxyUtil.asString(errors.getFirst().getThrowableProxy()).contains(secret),
                "受保护正文不能经异常及其原因泄露到日志");
        } finally {
            ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
        }
    }

    @Test
    void failuresOutsideMvcStillLogOnceAndClearRequestContext() {
        ListAppender<ILoggingEvent> logs = captureLogs();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/private-text");
        MockHttpServletResponse response = new MockHttpServletResponse();
        IOException failure = new IOException("DO_NOT_LOG_PRIVATE_PAYLOAD");
        // 原因环不应让记录故障本身无限递归。
        failure.initCause(new IllegalStateException("DO_NOT_LOG_PRIVATE_PAYLOAD", failure));
        try {
            assertEquals(failure, assertThrows(IOException.class, () -> new RequestIdFilter().doFilter(
                request, response, (ignoredRequest, ignoredResponse) -> { throw failure; })));
            var events = logs.list.stream().filter(event -> event.getLoggerName().equals(RequestIdFilter.class.getName())).toList();
            assertEquals(1, events.size(), "过滤器链异常也要有一次完成记录");
            ILoggingEvent event = events.getFirst();
            assertEquals(Level.ERROR, event.getLevel());
            assertTrue(event.getFormattedMessage().contains("status=500"));
            assertTrue(event.getFormattedMessage().contains("code=INTERNAL_ERROR"));
            assertFalse(ThrowableProxyUtil.asString(event.getThrowableProxy()).contains("DO_NOT_LOG_PRIVATE_PAYLOAD"));
            assertEquals(response.getHeader("X-Request-Id"), event.getMDCPropertyMap().get("requestId"));
            assertEquals(null, MDC.get("requestId"));
        } finally {
            ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
        }
    }

    private ListAppender<ILoggingEvent> captureLogs() {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
        return logs;
    }

    private ILoggingEvent assertCompletion(MvcResult result, ListAppender<ILoggingEvent> logs,
        String route, int httpStatus, String code, Level level) {
        String requestId = result.getResponse().getHeader("X-Request-Id");
        var events = logs.list.stream().filter(event -> event.getLoggerName().equals(RequestIdFilter.class.getName())
            && requestId.equals(event.getMDCPropertyMap().get("requestId"))).toList();
        assertEquals(1, events.size(), "每次请求必须只有一条完成记录");
        ILoggingEvent event = events.getFirst();
        assertEquals(level, event.getLevel());
        assertTrue(event.getTimeStamp() > 0);
        assertTrue(event.getFormattedMessage().contains("route=" + route + " "));
        assertTrue(event.getFormattedMessage().contains("method=" + result.getRequest().getMethod() + " "));
        assertTrue(event.getFormattedMessage().contains("status=" + httpStatus + " "));
        assertTrue(event.getFormattedMessage().contains("code=" + code));
        assertTrue(event.getFormattedMessage().matches(".*durationMs=\\d+.*"));
        return event;
    }
}
