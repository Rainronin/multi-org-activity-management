package com.example.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.activity.support.FlowTestSupport;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

/** 原版本必须绑定本轮报名及反馈，不能只凭复用的主记录 ID 覆盖新意图。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class RegistrationVersionTest extends FlowTestSupport {

    @Test
    void reapplicationRejectsOldReviewAndCancellation() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-12-01T14:00:00+08:00");
        openRegistration(owner, orgId, activityId);
        String applyPath = "/api/activities/" + activityId + "/registrations";
        MvcResult created = write(owner, applyPath, "{\"displayName\":\"原申请\",\"version\":0}", 201);
        String registrationId = readField(created, "$.registrationId");
        String reviewPath = root(orgId, activityId) + "/registrations/" + registrationId;
        write(owner, reviewPath + "/reject", "{\"reason\":\"补充内容\",\"version\":1}", 200);
        write(owner, applyPath, "{\"displayName\":\"新申请\",\"version\":2}", 201);
        write(owner, reviewPath + "/approve", "{\"version\":1}", 409);
        write(owner, reviewPath + "/cancel-org", "{\"reason\":\"旧取消\",\"version\":1}", 409);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM registration WHERE id = ?",
            String.class, registrationId)).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target = ?",
            Integer.class, "registration:" + registrationId)).isEqualTo(3);
        write(owner, reviewPath + "/approve", "{\"version\":3}", 200);
        write(owner, applyPath + "/cancel-self", "{\"version\":3}", 409);
        write(owner, applyPath + "/cancel-self", "{\"version\":4}", 204);
        write(owner, applyPath, "{\"displayName\":\"无版本重申请\"}", 400);
        write(owner, applyPath, "{\"displayName\":\"第三申请\",\"version\":5}", 201);
        mockMvc.perform(get("/api/me/registrations").session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].version").value(6));
        String audit = jdbcTemplate.queryForObject("SELECT detail FROM audit_log WHERE target = ? "
            + "AND action = 'REGISTRATION_REAPPLIED' ORDER BY id DESC LIMIT 1", String.class,
            "registration:" + registrationId);
        assertThat((Integer) com.jayway.jsonpath.JsonPath.read(audit, "$.before.version")).isEqualTo(5);
        assertThat((Integer) com.jayway.jsonpath.JsonPath.read(audit, "$.after.version")).isEqualTo(6);
        assertThat(audit).contains("\"before\"", "\"after\"")
            .doesNotContain("第三申请", "displayName", "note");
        var history = jdbcTemplate.queryForList("SELECT detail FROM audit_log WHERE target = ? ORDER BY id",
            String.class, "registration:" + registrationId);
        assertThat(history).hasSize(6);
        for (int index = 0; index < history.size(); index++) {
            var entry = new com.fasterxml.jackson.databind.ObjectMapper().readTree(history.get(index));
            assertThat(entry.path("after").path("version").asInt()).isEqualTo(index + 1);
            if (index == 0) assertThat(entry.path("before").isNull()).isTrue();
            else assertThat(entry.path("before").path("version").asInt()).isEqualTo(index);
        }
        write(owner, reviewPath + "/approve", "{\"version\":6}", 200);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE activity_id = ? "
            + "AND type = 'REGISTRATION_APPROVED'", Integer.class, activityId)).isEqualTo(2);
    }

    @Test
    void missingRegistrationVersionCannotBypassReviewOrCancellation() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-12-02T14:00:00+08:00");
        openRegistration(owner, orgId, activityId);
        String applyPath = "/api/activities/" + activityId + "/registrations";
        MvcResult created = write(owner, applyPath, "{\"displayName\":\"申请\"}", 201);
        String reviewPath = root(orgId, activityId) + "/registrations/" + readField(created, "$.registrationId");
        write(owner, reviewPath + "/approve", "{}", 400);
        write(owner, reviewPath + "/reject", "{\"reason\":\"无版本驳回\"}", 400);
        write(owner, reviewPath + "/cancel-org", "{\"reason\":\"无版本取消\"}", 400);
        write(owner, applyPath + "/cancel-self", "{}", 400);
    }

    @Test
    void onlyOneFeedbackWriterCanSaveTheLoadedVersion() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivity(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-12-03T14:00:00+08:00");
        String activityPath = root(orgId, activityId);
        write(owner, activityPath + "/start-execution", versioned(activityId, "{}"), 200);
        write(owner, activityPath + "/finish-execution", versioned(activityId, "{}"), 200);
        String feedbackPath = activityPath + "/feedback";
        write(owner, feedbackPath, "{\"rating\":5,\"comment\":\"原反馈\",\"version\":0}", 200);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                return mockMvc.perform(post(feedbackPath).with(csrf()).session(owner.session())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"rating\":4,\"comment\":\"第一份修改\",\"version\":1}"))
                    .andReturn().getResponse().getStatus();
            });
            var second = pool.submit(() -> {
                start.await();
                return mockMvc.perform(post(feedbackPath).with(csrf()).session(owner.session())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"rating\":3,\"comment\":\"第二份修改\",\"version\":1}"))
                    .andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertThat(java.util.List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 409);
        }
        write(owner, feedbackPath, "{\"rating\":1,\"comment\":\"无版本覆盖\"}", 400);
        mockMvc.perform(get(feedbackPath).session(owner.session()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.myVersion").value(2));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target = ? "
            + "AND action IN ('FEEDBACK_SUBMITTED','FEEDBACK_UPDATED')", Integer.class,
            "activity:" + activityId)).isEqualTo(2);
        String audit = jdbcTemplate.queryForObject("SELECT detail FROM audit_log WHERE target = ? "
            + "AND action = 'FEEDBACK_UPDATED'", String.class, "activity:" + activityId);
        assertThat((Integer) com.jayway.jsonpath.JsonPath.read(audit, "$.before.version")).isEqualTo(1);
        assertThat((Integer) com.jayway.jsonpath.JsonPath.read(audit, "$.after.version")).isEqualTo(2);
        assertThat(audit).contains("\"before\"", "\"after\"")
            .doesNotContain("原反馈", "第一份修改", "第二份修改", "comment");
    }

    private String root(Long orgId, Long activityId) {
        return "/api/organizations/" + orgId + "/activities/" + activityId;
    }

    private MvcResult write(TestSession actor, String path, String body, int expected) throws Exception {
        return mockMvc.perform(post(path).with(csrf()).session(actor.session())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().is(expected)).andReturn();
    }
}
