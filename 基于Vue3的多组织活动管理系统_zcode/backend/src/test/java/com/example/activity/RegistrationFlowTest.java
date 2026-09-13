package com.example.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.activity.support.ActivityTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** P4 报名链路：申请、幂等、审核、名额并发、取消与重新申请。身份经真实注册+登录建立。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class RegistrationFlowTest extends ActivityTestBase {

    private static final String PASSWORD = "Passw0rd123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record TestSession(String username, MockHttpSession session) {
    }

    private TestSession registerAndLogin() throws Exception {
        String username = "r" + Long.toString(System.nanoTime(), 36);
        mockMvc.perform(post("/api/auth/register").with(csrf())
                .with(request -> {
                    request.setRemoteAddr("10.%d.%d.41".formatted(
                        (username.hashCode() >> 8) & 0xFF, username.hashCode() & 0xFF));
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

    private Long memberIdOf(Long orgId, String username) {
        return jdbcTemplate.queryForObject(
            "SELECT m.id FROM organization_member m JOIN account a ON a.id = m.account_id "
                + "WHERE m.org_id = ? AND a.username = ? AND m.status = 'ACTIVE'",
            Long.class, orgId, username);
    }

    private void inviteAndAccept(TestSession owner, TestSession invitee, Long orgId) throws Exception {
        MvcResult invitation = mockMvc.perform(
                post("/api/organizations/" + orgId + "/invitations").with(csrf()).session(owner.session())
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isCreated())
            .andReturn();
        String code = com.jayway.jsonpath.JsonPath.read(
            invitation.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.code");
        mockMvc.perform(post("/api/invitations/accept").with(csrf()).session(invitee.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"%s\"}".formatted(code)))
            .andExpect(status().isOk());
    }

    private Long createOrg(TestSession owner) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/organizations").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"报名组织-%d\"}".formatted(System.nanoTime())))
            .andExpect(status().isCreated())
            .andReturn();
        return Long.parseLong(com.jayway.jsonpath.JsonPath.read(
            result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.id"));
    }

    private Long createApprovedActivity(TestSession owner, Long orgId, int capacity) throws Exception {
        Long ownerMemberId = memberIdOf(orgId, owner.username());
        MvcResult created = mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"name\":\"报名活动\",\"ownerMemberId\":%d,\"description\":\"D\","
                    + "\"startTime\":\"2026-12-01T14:00:00+08:00\",\"endTime\":\"2026-12-01T16:00:00+08:00\","
                    + "\"location\":\"礼堂\",\"registrationScope\":\"INTERNAL\",\"capacity\":%d}")
                    .formatted(ownerMemberId, capacity)))
            .andExpect(status().isCreated())
            .andReturn();
        Long activityId = Long.parseLong(com.jayway.jsonpath.JsonPath.read(
            created.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.id"));
        mockMvc.perform(put("/api/organizations/" + orgId + "/activities/" + activityId + "/proposal")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"S\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/submit")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/approve")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk());
        // S02：报名开关默认关闭，需负责人明确开启后再申请
        openRegistration(owner, orgId, activityId);
        return activityId;
    }

    private void openRegistration(TestSession actor, Long orgId, Long activityId) throws Exception {
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/registration-open")
                .with(csrf()).session(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"open\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.registrationOpen").value(true));
    }

    private Long apply(TestSession session, Long activityId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/activities/" + activityId + "/registrations")
                .with(csrf()).session(session.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"" + session.username() + "\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        return Long.parseLong(com.jayway.jsonpath.JsonPath.read(
            result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.registrationId"));
    }

    @Test
    void applyApproveDuplicateAndReapply() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long activityId = createApprovedActivity(owner, orgId, 10);

        Long registrationId = apply(member, activityId);

        // 重复申请幂等（同一 registrationId）
        Long dupId = apply(member, activityId);
        assertThat(dupId).isEqualTo(registrationId);

        // OWNER 审批通过
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                + "/registrations/" + registrationId + "/approve")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"));

        // 本人取消
        mockMvc.perform(post("/api/activities/" + activityId + "/registrations/cancel-self")
                .with(csrf()).session(member.session()))
            .andExpect(status().isNoContent());

        // 重新申请（更新主记录，同一 registrationId）
        Long reAppliedId = apply(member, activityId);
        assertThat(reAppliedId).isEqualTo(registrationId);

        // 本人报名列表
        mockMvc.perform(get("/api/me/registrations").session(member.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void concurrentApproveRacesLastSlot() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivity(owner, orgId, 1);

        TestSession a = registerAndLogin();
        TestSession b = registerAndLogin();
        inviteAndAccept(owner, a, orgId);
        inviteAndAccept(owner, b, orgId);

        Long regA = apply(a, activityId);
        Long regB = apply(b, activityId);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> results = new ArrayList<>();
        results.add(pool.submit(() -> {
            barrier.await();
            return mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                    + "/registrations/" + regA + "/approve")
                .with(csrf()).session(owner.session()))
                .andReturn().getResponse().getStatus();
        }));
        results.add(pool.submit(() -> {
            barrier.await();
            return mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId
                    + "/registrations/" + regB + "/approve")
                .with(csrf()).session(owner.session()))
                .andReturn().getResponse().getStatus();
        }));
        int approved = 0;
        for (Future<Integer> future : results) {
            int status = future.get();
            assertThat(status).isIn(200, 409);
            if (status == 200) approved++;
        }
        pool.shutdownNow();
        assertThat(approved).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM registration WHERE activity_id = ? AND status = 'APPROVED'",
            Integer.class, activityId)).isEqualTo(1);
    }
}
