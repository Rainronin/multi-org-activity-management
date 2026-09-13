package com.example.activity.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 业务链路测试的公共准备：真实注册+登录建立身份、建组织、发邀请、造出「已确认」活动。
 * 各测试类只保留自己的场景断言，避免在同一套准备流程上复制多份代码。
 */
public abstract class FlowTestSupport extends ActivityTestBase {

    private static final String PASSWORD = "Passw0rd123";

    private static final AtomicInteger IP_SEQ = new AtomicInteger();

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    public record TestSession(String username, MockHttpSession session) {
    }

    /** 注册并登录一个独立账号；来源 IP 逐次递增，避免注册限流把不同测试互相拖垮。 */
    protected TestSession registerAndLogin() throws Exception {
        String username = "u" + Long.toString(System.nanoTime(), 36) + IP_SEQ.incrementAndGet();
        String ip = "10.%d.%d.%d".formatted(10 + (IP_SEQ.get() % 240),
            (username.hashCode() >> 8) & 0xFF, username.hashCode() & 0xFF);
        mockMvc.perform(post("/api/auth/register").with(csrf())
                .with(remoteAddr(ip))
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

    protected static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    protected Long createOrg(TestSession owner) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/organizations").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"测试组织-%d\"}".formatted(System.nanoTime())))
            .andExpect(status().isCreated())
            .andReturn();
        return Long.parseLong(readField(result, "$.id"));
    }

    protected void inviteAndAccept(TestSession owner, TestSession invitee, Long orgId) throws Exception {
        MvcResult invitation = mockMvc.perform(
                post("/api/organizations/" + orgId + "/invitations").with(csrf()).session(owner.session())
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isCreated())
            .andReturn();
        String code = readField(invitation, "$.code");
        mockMvc.perform(post("/api/invitations/accept").with(csrf()).session(invitee.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"%s\"}".formatted(code)))
            .andExpect(status().isOk());
    }

    protected Long memberIdOf(Long orgId, String username) {
        return jdbcTemplate.queryForObject(
            "SELECT m.id FROM organization_member m JOIN account a ON a.id = m.account_id "
                + "WHERE m.org_id = ? AND a.username = ? AND m.status = 'ACTIVE'",
            Long.class, orgId, username);
    }

    protected String readField(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }

    /** 造出处于 APPROVED 状态的活动；报名开关仍为默认关闭，需要时另行开启。 */
    protected Long createApprovedActivity(TestSession owner, Long orgId, Long ownerMemberId, String startTime)
        throws Exception {
        MvcResult created = mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(activityBody("测试活动", ownerMemberId, startTime)))
            .andExpect(status().isCreated())
            .andReturn();
        Long activityId = Long.parseLong(readField(created, "$.id"));
        submitForApproval(owner, orgId, activityId);
        return activityId;
    }

    protected String activityBody(String name, Long ownerMemberId, String startTime) {
        return ("{\"name\":\"%s\",\"ownerMemberId\":%d,\"description\":\"简介\","
            + "\"startTime\":\"%s\",\"endTime\":\"%s\","
            + "\"location\":\"礼堂\",\"registrationScope\":\"INTERNAL\",\"capacity\":10}")
            .formatted(name, ownerMemberId, startTime, startTime.replace("T14:00", "T16:00"));
    }

    /** 提交方案并完成单级确认，使活动进入 APPROVED。 */
    protected void submitForApproval(TestSession owner, Long orgId, Long activityId) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/organizations/" + orgId + "/activities/" + activityId + "/proposal")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"方案\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/submit")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/approve")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk());
    }

    protected void openRegistration(TestSession actor, Long orgId, Long activityId) throws Exception {
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/registration-open")
                .with(csrf()).session(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"open\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.registrationOpen").value(true));
    }

    protected void assertTaskCompletedAt(Long taskId, boolean expected) {
        Boolean actual = jdbcTemplate.queryForObject(
            "SELECT completed_at IS NOT NULL FROM task WHERE id = ?", Boolean.class, taskId);
        assertEquals(expected, actual);
    }
}
