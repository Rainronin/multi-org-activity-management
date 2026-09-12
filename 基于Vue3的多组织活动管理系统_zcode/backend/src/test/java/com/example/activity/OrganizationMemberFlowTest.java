package com.example.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
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
 * 成员与邀请链路（P1.3）：真实注册+登录建立身份——
 * 邀请创建/接受/幂等/失效、成员列表、退出与移除的最后负责人保护、授予/撤销/移交。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class OrganizationMemberFlowTest extends ActivityTestBase {

    private static final String PASSWORD = "Passw0rd123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private record TestSession(String username, MockHttpSession session) {
    }

    private TestSession registerAndLogin() throws Exception {
        String username = "u" + Long.toString(System.nanoTime(), 36);
        mockMvc.perform(post("/api/auth/register").with(csrf())
                .with(request -> {
                    request.setRemoteAddr("10.%d.%d.9".formatted(
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

    private Long createOrg(TestSession owner) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/organizations").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"成员测试-%d\"}".formatted(System.nanoTime())))
            .andExpect(status().isCreated())
            .andReturn();
        return Long.parseLong(com.jayway.jsonpath.JsonPath.read(
            result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.id"));
    }

    private String createInvitation(TestSession owner, Long orgId, Integer maxUses) throws Exception {
        String body = maxUses == null
            ? "{}"
            : "{\"maxUses\":%d}".formatted(maxUses);
        MvcResult result = mockMvc.perform(post("/api/organizations/" + orgId + "/invitations")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").isNotEmpty())
            .andReturn();
        return com.jayway.jsonpath.JsonPath.read(
            result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.code");
    }

    private void acceptInvitation(TestSession invitee, String code) throws Exception {
        mockMvc.perform(post("/api/invitations/accept").with(csrf()).session(invitee.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"%s\"}".formatted(code)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("MEMBER"));
    }

    private Long memberIdOf(Long orgId, String username) {
        return jdbcTemplate.queryForObject(
            "SELECT m.id FROM organization_member m JOIN account a ON a.id = m.account_id "
                + "WHERE m.org_id = ? AND a.username = ? AND m.status = 'ACTIVE'",
            Long.class, orgId, username);
    }

    @Test
    void invitationLifecycleAcceptAndIdempotency() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession invitee = registerAndLogin();
        Long orgId = createOrg(owner);
        String code = createInvitation(owner, orgId, null);

        acceptInvitation(invitee, code);
        // 重复接受幂等：仍返回 MEMBER，不新增关系
        mockMvc.perform(post("/api/invitations/accept").with(csrf()).session(invitee.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"%s\"}".formatted(code)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("MEMBER"));
        Integer rows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM organization_member WHERE org_id = ? AND status = 'ACTIVE'", Integer.class, orgId);
        assertThat(rows).isEqualTo(2);
        Integer usedCount = jdbcTemplate.queryForObject(
            "SELECT used_count FROM organization_invitation WHERE org_id = ? AND status = 'ACTIVE'",
            Integer.class, orgId);
        assertThat(usedCount).isEqualTo(1);

        // 无效邀请码统一 400
        mockMvc.perform(post("/api/invitations/accept").with(csrf()).session(invitee.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"0000-0000-0000-0000-0000-0000-0000-0000\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
    }

    @Test
    void maxUsesExhaustedBecomesInvalid() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession a = registerAndLogin();
        TestSession b = registerAndLogin();
        Long orgId = createOrg(owner);
        String code = createInvitation(owner, orgId, 1);

        acceptInvitation(a, code);
        mockMvc.perform(post("/api/invitations/accept").with(csrf()).session(b.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"%s\"}".formatted(code)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
    }

    @Test
    void expiredInvitationIsInvalid() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession invitee = registerAndLogin();
        Long orgId = createOrg(owner);
        String code = createInvitation(owner, orgId, null);
        // 测试库直接将有效期改到过去（仅测试数据准备）
        jdbcTemplate.update(
            "UPDATE organization_invitation SET expires_at = TIMESTAMPADD(DAY, -1, NOW(6)) WHERE org_id = ?",
            orgId);
        mockMvc.perform(post("/api/invitations/accept").with(csrf()).session(invitee.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"%s\"}".formatted(code)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
    }

    @Test
    void memberListLeaveAndLastOwnerProtection() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        String code = createInvitation(owner, orgId, null);
        acceptInvitation(member, code);

        mockMvc.perform(get("/api/organizations/" + orgId + "/members").session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2));

        // 最后一名负责人不能退出
        mockMvc.perform(delete("/api/organizations/" + orgId + "/members/me").with(csrf()).session(owner.session()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LAST_OWNER_PROTECTED"));

        // 普通成员可以退出；重复退出幂等 204
        mockMvc.perform(delete("/api/organizations/" + orgId + "/members/me").with(csrf()).session(member.session()))
            .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/organizations/" + orgId + "/members/me").with(csrf()).session(member.session()))
            .andExpect(status().isNoContent());

        // 退出后目录不再包含该组织
        mockMvc.perform(get("/api/me/organizations").session(member.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void removeRequiresOwnerAndProtectsRules() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        TestSession outsider = registerAndLogin();
        Long orgId = createOrg(owner);
        acceptInvitation(member, createInvitation(owner, orgId, null));
        Long memberMemberId = memberIdOf(orgId, member.username());

        // 非负责人移除 → 403
        mockMvc.perform(delete("/api/organizations/" + orgId + "/members/" + memberMemberId)
                .with(csrf()).session(member.session()))
            .andExpect(status().isForbidden());

        // 负责人不能移除自己
        Long ownerMemberId = memberIdOf(orgId, owner.username());
        mockMvc.perform(delete("/api/organizations/" + orgId + "/members/" + ownerMemberId)
                .with(csrf()).session(owner.session()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MEMBER_SELF_REMOVAL"));

        // 跨组织的 memberId → 404（先建另一组织拿一个 memberId）
        TestSession otherOwner = registerAndLogin();
        Long otherOrgId = createOrg(otherOwner);
        Long foreignMemberId = memberIdOf(otherOrgId, otherOwner.username());
        mockMvc.perform(delete("/api/organizations/" + orgId + "/members/" + foreignMemberId)
                .with(csrf()).session(owner.session()))
            .andExpect(status().isNotFound());

        // 正常移除；重复移除对已授权操作者幂等返回 204（R07）；从未加入与不存在 → 404
        mockMvc.perform(delete("/api/organizations/" + orgId + "/members/" + memberMemberId)
                .with(csrf()).session(owner.session()))
            .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/organizations/" + orgId + "/members/" + memberMemberId)
                .with(csrf()).session(owner.session()))
            .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/organizations/" + orgId + "/members/999999999")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isNotFound());
        assertThat(outsider).isNotNull();
    }

    @Test
    void promoteRevokeTransferWithLastOwnerProtection() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        Long orgId = createOrg(owner);
        acceptInvitation(member, createInvitation(owner, orgId, null));
        Long memberMemberId = memberIdOf(orgId, member.username());
        Long ownerMemberId = memberIdOf(orgId, owner.username());

        // 授予负责人 → 组织内两名 OWNER
        mockMvc.perform(post("/api/organizations/" + orgId + "/members/" + memberMemberId + "/promote-owner")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("OWNER"));

        // 重复授予 → 409
        mockMvc.perform(post("/api/organizations/" + orgId + "/members/" + memberMemberId + "/promote-owner")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("MEMBER_ALREADY_OWNER"));

        // 撤销一名仍有另一名 → 成功
        mockMvc.perform(post("/api/organizations/" + orgId + "/members/" + memberMemberId + "/revoke-owner")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("MEMBER"));

        // 仅剩一名负责人时撤销 → 409
        mockMvc.perform(post("/api/organizations/" + orgId + "/members/" + ownerMemberId + "/revoke-owner")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LAST_OWNER_PROTECTED"));

        // 移交给普通成员：成员成为 OWNER，原负责人降为 MEMBER（成员当前已是普通成员，无需再授予）
        mockMvc.perform(post("/api/organizations/" + orgId + "/ownership/transfer")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toMemberId\":%d}".formatted(memberMemberId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("MEMBER"));

        Integer owners = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM organization_member WHERE org_id = ? AND status = 'ACTIVE' AND role = 'OWNER'",
            Integer.class, orgId);
        assertThat(owners).isEqualTo(1);

        // 新负责人不能再移交给自己
        mockMvc.perform(post("/api/organizations/" + orgId + "/ownership/transfer")
                .with(csrf()).session(member.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toMemberId\":%d}".formatted(memberMemberId)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentAcceptSameAccountIsIdempotent() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession invitee = registerAndLogin();
        Long orgId = createOrg(owner);
        String code = createInvitation(owner, orgId, 2);

        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                barrier.await();
                return mockMvc.perform(post("/api/invitations/accept").with(csrf()).session(invitee.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\"}".formatted(code)))
                    .andReturn().getResponse().getStatus();
            }));
        }
        for (java.util.concurrent.Future<Integer> future : results) {
            assertThat(future.get()).isEqualTo(200);
        }
        pool.shutdownNow();

        // 组织内 ACTIVE 成员 = 负责人 + 被邀请人；被邀请人恰有一条有效关系
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM organization_member WHERE org_id = ? AND status = 'ACTIVE'",
            Integer.class, orgId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT used_count FROM organization_invitation WHERE org_id = ? AND status = 'ACTIVE'",
            Integer.class, orgId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM organization_member m JOIN account a ON a.id = m.account_id "
                + "WHERE m.org_id = ? AND m.status = 'ACTIVE' AND a.username = ?",
            Integer.class, orgId, invitee.username())).isEqualTo(1);

        // 主关系复用（R05）：退出后凭有效邀请再加入，memberId 不变
        Long firstMemberId = jdbcTemplate.queryForObject(
            "SELECT id FROM organization_member WHERE org_id = ? AND status = 'ACTIVE' "
                + "AND account_id = (SELECT id FROM account WHERE username = ?)",
            Long.class, orgId, invitee.username());
        mockMvc.perform(delete("/api/organizations/" + orgId + "/members/me").with(csrf()).session(invitee.session()))
            .andExpect(status().isNoContent());
        acceptInvitation(invitee, code);
        Long reJoinedId = jdbcTemplate.queryForObject(
            "SELECT id FROM organization_member WHERE org_id = ? AND status = 'ACTIVE' "
                + "AND account_id = (SELECT id FROM account WHERE username = ?)",
            Long.class, orgId, invitee.username());
        assertThat(reJoinedId).isEqualTo(firstMemberId);
    }

    @Test
    void concurrentAcceptRacesLastUse() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession a = registerAndLogin();
        TestSession b = registerAndLogin();
        Long orgId = createOrg(owner);
        String code = createInvitation(owner, orgId, 1);

        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
        for (TestSession invitee : java.util.List.of(a, b)) {
            results.add(pool.submit(() -> {
                barrier.await();
                return mockMvc.perform(post("/api/invitations/accept").with(csrf()).session(invitee.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\"}".formatted(code)))
                    .andReturn().getResponse().getStatus();
            }));
        }
        int ok = 0;
        for (java.util.concurrent.Future<Integer> future : results) {
            int status = future.get();
            assertThat(status).isIn(200, 400);
            if (status == 200) {
                ok++;
            }
        }
        pool.shutdownNow();
        assertThat(ok).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT used_count FROM organization_invitation WHERE org_id = ? AND status = 'ACTIVE'",
            Integer.class, orgId)).isEqualTo(1);
        // 组织内 ACTIVE 成员 = 负责人 + 恰好一名被邀请人
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM organization_member WHERE org_id = ? AND status = 'ACTIVE'",
            Integer.class, orgId)).isEqualTo(2);
    }

    @Test
    void concurrentRevokeKeepsAtLeastOneOwner() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession coOwner = registerAndLogin();
        Long orgId = createOrg(owner);
        acceptInvitation(coOwner, createInvitation(owner, orgId, null));
        Long coOwnerMemberId = memberIdOf(orgId, coOwner.username());
        mockMvc.perform(post("/api/organizations/" + orgId + "/members/" + coOwnerMemberId + "/promote-owner")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isOk());

        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
        results.add(pool.submit(() -> {
            barrier.await();
            return mockMvc.perform(
                    post("/api/organizations/" + orgId + "/members/" + coOwnerMemberId + "/revoke-owner")
                        .with(csrf()).session(owner.session()))
                .andReturn().getResponse().getStatus();
        }));
        results.add(pool.submit(() -> {
            barrier.await();
            return mockMvc.perform(
                    post("/api/organizations/" + orgId + "/members/" + memberIdOf(orgId, owner.username()) + "/revoke-owner")
                        .with(csrf()).session(coOwner.session()))
                .andReturn().getResponse().getStatus();
        }));
        int success = 0;
        for (java.util.concurrent.Future<Integer> future : results) {
            if (future.get() == 200) {
                success++;
            }
        }
        pool.shutdownNow();
        assertThat(success).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM organization_member WHERE org_id = ? AND status = 'ACTIVE' AND role = 'OWNER'",
            Integer.class, orgId)).isEqualTo(1);
    }

    @Test
    void demotionRacesInvitationCreation() throws Exception {
        // 撤权与创建邀请竞争（R04）：先持组织行锁，持锁期间把负责人降级；
        // 锁释放后被阻塞的创建请求到达，必须在锁内重验身份并以 403 失败。
        TestSession owner = registerAndLogin();
        TestSession coOwner = registerAndLogin();
        Long orgId = createOrg(owner);
        acceptInvitation(coOwner, createInvitation(owner, orgId, null));
        Long coOwnerMemberId = memberIdOf(orgId, coOwner.username());
        mockMvc.perform(post("/api/organizations/" + orgId + "/members/" + coOwnerMemberId + "/promote-owner")
                .with(csrf()).session(owner.session()))
            .andExpect(status().isOk());

        javax.sql.DataSource dataSource = applicationContext.getBean(javax.sql.DataSource.class);
        java.util.concurrent.CountDownLatch lockHeld = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (java.sql.Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                connection.createStatement()
                    .execute("SELECT id FROM organization WHERE id = " + orgId + " FOR UPDATE");
                lockHeld.countDown();
                release.await();
                // 持锁事务内降级并提交：锁与撤权对后续请求原子生效
                connection.createStatement().executeUpdate(
                    "UPDATE organization_member SET role = 'MEMBER' WHERE id = " + coOwnerMemberId);
                connection.commit();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        holder.start();
        assertThat(lockHeld.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        java.util.concurrent.atomic.AtomicReference<Integer> createStatus =
            new java.util.concurrent.atomic.AtomicReference<>();
        Thread creator = new Thread(() -> {
            try {
                MvcResult result = mockMvc.perform(post("/api/organizations/" + orgId + "/invitations")
                        .with(csrf()).session(coOwner.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                    .andReturn();
                createStatus.set(result.getResponse().getStatus());
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        creator.start();
        Thread.sleep(300);
        release.countDown();
        creator.join();
        holder.join();

        assertThat(createStatus.get()).isEqualTo(403);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM organization_invitation WHERE org_id = ? AND created_by = ("
                + "SELECT account_id FROM organization_member WHERE id = " + coOwnerMemberId + ")",
            Integer.class, orgId)).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM organization_member WHERE org_id = ? AND status = 'ACTIVE' AND role = 'OWNER'",
            Integer.class, orgId)).isEqualTo(1);
    }
}
