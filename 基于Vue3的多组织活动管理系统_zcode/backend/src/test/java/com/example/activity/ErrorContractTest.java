package com.example.activity;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import com.example.activity.support.ActivityTestBase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接口合同检查：ProblemDetail 错误结构、requestId、CSRF 边界与字段校验
 * 使用真实服务入口验证（开发规范 2.3、2.4）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ErrorContractTest extends ActivityTestBase {

    @Autowired
    private MockMvc mockMvc;

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
}
