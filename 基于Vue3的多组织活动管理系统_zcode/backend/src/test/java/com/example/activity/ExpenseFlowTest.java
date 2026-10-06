package com.example.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.activity.support.FlowTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

/**
 * P5 经费回归：登记与权限、提交与确认链路、驳回重提、超支说明、作废与更正、统计口径、
 * 版本前置条件与创建防重。金额一律用两位小数字符串核对，避免浮点误差掩盖问题。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ExpenseFlowTest extends FlowTestSupport {

    @ParameterizedTest
    @ValueSource(strings = {"actor", "activity"})
    void creationKeyCannotReplayExpenseFromAnotherScope(String scope) throws Exception {
        TestSession owner = registerAndLogin();
        TestSession otherOwner = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, otherOwner, orgId);
        mockMvc.perform(post("/api/organizations/" + orgId + "/members/"
                + memberIdOf(orgId, otherOwner.username()) + "/promote-owner")
                .with(csrf()).session(owner.session())).andExpect(status().isOk());
        Long activityId = createApprovedActivityWithBudget(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-07-10T14:00:00+08:00", "100.00");
        Long otherActivityId = createApprovedActivityWithBudget(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-07-11T14:00:00+08:00", "100.00");
        String key = newIdempotencyKey();
        String body = "{\"purpose\":\"原费用意图\",\"amount\":\"10.00\",\"incurredOn\":\"2027-07-01\",\"category\":\"OTHER\"}";
        MvcResult first = mockMvc.perform(post(expensesPath(orgId, activityId)).with(csrf()).session(owner.session())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn();
        mockMvc.perform(post(expensesPath(orgId, activityId)).with(csrf()).session(owner.session())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.expenseId").value(readField(first, "$.expenseId")));
        mockMvc.perform(post(expensesPath(orgId, "activity".equals(scope) ? otherActivityId : activityId))
                .with(csrf()).session("actor".equals(scope) ? otherOwner.session() : owner.session())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM expense WHERE org_id = ?", Integer.class, orgId))
            .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"create:ARCHIVED", "create:PENDING_APPROVAL", "correction:ARCHIVED", "correction:PENDING_APPROVAL"})
    void existingCreationKeyCanBeCheckedAfterActivityBecomesReadOnly(String scenario) throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivityWithBudget(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-07-10T14:00:00+08:00", "100.00");
        String activityPath = "/api/organizations/" + orgId + "/activities/" + activityId;
        String target = expensesPath(orgId, activityId);
        boolean correction = scenario.startsWith("correction:");
        if (correction) {
            String originalId = createExpense(owner, orgId, activityId, "原费用", "10.00", "无票说明");
            submitExpense(owner, orgId, activityId, originalId);
            confirmExpense(owner, orgId, activityId, originalId, null, 200);
            mockMvc.perform(post(target + "/" + originalId + "/void").with(csrf()).session(owner.session())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":" + versionOf(Long.parseLong(originalId)) + ",\"reason\":\"更正原费用\"}"))
                .andExpect(status().isOk());
            target += "/" + originalId + "/correction";
        }
        String key = newIdempotencyKey();
        String body = "{\"purpose\":\"待核对费用\",\"amount\":\"10.00\",\"incurredOn\":\"2027-07-01\",\"category\":\"OTHER\"}";
        MvcResult first = mockMvc.perform(post(target).with(csrf()).session(owner.session())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn();
        if (scenario.endsWith("ARCHIVED")) {
            for (String action : new String[] {"start-execution", "finish-execution"}) {
                mockMvc.perform(post(activityPath + "/" + action).with(csrf()).session(owner.session())
                        .contentType(MediaType.APPLICATION_JSON).content(versioned(activityId, "{}")))
                    .andExpect(status().isOk());
            }
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(activityPath + "/summary")
                    .with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                    .content(versioned(activityId, "{\"summary\":\"活动结束，费用以实际确认记录为准。\"}")))
                .andExpect(status().isOk());
            for (String action : new String[] {"summary/confirm", "archive"}) {
                mockMvc.perform(post(activityPath + "/" + action).with(csrf()).session(owner.session())
                        .contentType(MediaType.APPLICATION_JSON).content(versioned(activityId, "{}")))
                    .andExpect(status().isOk());
            }
        } else {
            mockMvc.perform(post(activityPath + "/withdraw-approval").with(csrf()).session(owner.session())
                    .contentType(MediaType.APPLICATION_JSON).content(versioned(activityId, "{\"reason\":\"调整安排\"}")))
                .andExpect(status().isOk());
            mockMvc.perform(post(activityPath + "/submit").with(csrf()).session(owner.session()))
                .andExpect(status().isOk());
        }
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM activity WHERE id = ?", String.class, activityId))
            .isEqualTo(scenario.substring(scenario.indexOf(':') + 1));
        mockMvc.perform(post(target).with(csrf()).session(owner.session())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.expenseId").value(readField(first, "$.expenseId")));
        mockMvc.perform(post(target).with(csrf()).session(owner.session())
                .header("Idempotency-Key", newIdempotencyKey()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACTIVITY_STATE_CONFLICT"));
        mockMvc.perform(post(target).with(csrf()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM expense WHERE activity_id = ?", Integer.class, activityId))
            .isEqualTo(correction ? 2 : 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "correction"})
    void concurrentCreationKeyCanBeCheckedWithoutInternalErrors(String kind) throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivityWithBudget(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-07-10T14:00:00+08:00", "100.00");
        String target = expensesPath(orgId, activityId);
        boolean correction = "correction".equals(kind);
        if (correction) {
            String originalId = createExpense(owner, orgId, activityId, "原费用", "10.00", "无票说明");
            submitExpense(owner, orgId, activityId, originalId);
            confirmExpense(owner, orgId, activityId, originalId, null, 200);
            mockMvc.perform(post(target + "/" + originalId + "/void").with(csrf()).session(owner.session())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":" + versionOf(Long.parseLong(originalId)) + ",\"reason\":\"更正原费用\"}"))
                .andExpect(status().isOk());
            target += "/" + originalId + "/correction";
        }
        String endpoint = target;
        String key = newIdempotencyKey();
        String body = "{\"purpose\":\"并发费用\",\"amount\":\"10.00\",\"incurredOn\":\"2027-07-01\",\"category\":\"OTHER\"}";
        var start = new java.util.concurrent.CountDownLatch(1);
        var results = new java.util.ArrayList<java.util.concurrent.Future<MvcResult>>();
        var responses = new java.util.ArrayList<MvcResult>();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(post(endpoint).with(csrf()).session(owner.session())
                            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn();
                }));
            }
            start.countDown();
            for (var result : results) responses.add(result.get(20, java.util.concurrent.TimeUnit.SECONDS));
        }
        for (var response : responses) {
            assertThat(response.getResponse().getStatus()).isIn(201, 409);
            if (response.getResponse().getStatus() == 409) {
                assertThat(readField(response, "$.code")).isEqualTo("REQUEST_IN_PROGRESS");
            }
        }
        MvcResult replay = mockMvc.perform(post(endpoint).with(csrf()).session(owner.session())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn();
        for (var response : responses) {
            if (response.getResponse().getStatus() == 201) {
                assertThat(readField(response, "$.expenseId")).isEqualTo(readField(replay, "$.expenseId"));
            }
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM expense WHERE activity_id = ?", Integer.class, activityId))
            .isEqualTo(correction ? 2 : 1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target = ? AND action = ?",
            Integer.class, "expense:" + readField(replay, "$.expenseId"),
            correction ? "EXPENSE_CORRECTION_CREATED" : "EXPENSE_DRAFT_CREATED")).isEqualTo(1);
    }

    @Test
    void creationKeyDistinguishesNotesContainingTheLegacyDelimiter() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivityWithBudget(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-07-10T14:00:00+08:00", "100.00");
        String target = expensesPath(orgId, activityId);
        String key = newIdempotencyKey();
        String body = "{\"purpose\":\"原费用\",\"amount\":\"10.00\",\"incurredOn\":\"2027-07-01\",\"category\":\"OTHER\","
            + "\"handledNote\":\"A\\u001fB\",\"noReceiptNote\":\"C\"}";
        mockMvc.perform(post(target).with(csrf()).session(owner.session())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
        mockMvc.perform(post(target).with(csrf()).session(owner.session())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("A\\u001fB", "A").replace("\"C\"", "\"B\\u001fC\"")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM expense WHERE activity_id = ?",
            Integer.class, activityId)).isEqualTo(1);
    }

    @Test
    void ambiguousLegacyCreationKeyRequiresManualVerification() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivityWithBudget(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-07-10T14:00:00+08:00", "100.00");
        String path = expensesPath(orgId, activityId);
        String key = newIdempotencyKey();
        String body = "{\"purpose\":\"旧歧义费用\",\"amount\":\"10.00\",\"incurredOn\":\"2027-07-01\",\"category\":\"OTHER\","
            + "\"handledNote\":\"A\\u001fB\",\"noReceiptNote\":\"C\"}";
        MvcResult created = mockMvc.perform(post(path).with(csrf()).session(owner.session())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn();
        String id = readField(created, "$.expenseId");
        String legacy = com.example.activity.common.SecureCodes.hash(
            "旧歧义费用\u001f10.00\u001f2027-07-01\u001fOTHER\u001fA\u001fB\u001fC\u001fnull");
        jdbcTemplate.update("UPDATE expense SET request_fingerprint = ? WHERE id = ?", legacy, id);
        for (String request : new String[] {body, body.replace("A\\u001fB", "A").replace("\"C\"", "\"B\\u001fC\"")}) {
            mockMvc.perform(post(path).with(csrf()).session(owner.session())
                    .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_RESULT_UNVERIFIABLE"));
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM expense WHERE activity_id = ?",
            Integer.class, activityId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT request_fingerprint FROM expense WHERE id = ?",
            String.class, id)).isEqualTo(legacy);
    }

    @Test
    void legacyCreationKeyStillReplaysOriginalInputAfterEditing() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivityWithBudget(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-07-10T14:00:00+08:00", "100.00");
        String target = expensesPath(orgId, activityId);
        String key = newIdempotencyKey();
        String body = "{\"purpose\":\"旧费用\",\"amount\":\"10.00\",\"incurredOn\":\"2027-07-01\",\"category\":\"OTHER\","
            + "\"handledNote\":\"经手\",\"noReceiptNote\":\"无票\"}";
        MvcResult created = mockMvc.perform(post(target).with(csrf()).session(owner.session())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn();
        String id = readField(created, "$.expenseId");
        String legacy = com.example.activity.common.SecureCodes.hash(
            "旧费用\u001f10.00\u001f2027-07-01\u001fOTHER\u001f经手\u001f无票\u001fnull");
        jdbcTemplate.update("UPDATE expense SET request_fingerprint = ? WHERE id = ?", legacy, id);
        mockMvc.perform(post(target + "/" + id + "/update").with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("旧费用", "已编辑费用").replace("}", ",\"version\":1}")))
            .andExpect(status().isOk());
        mockMvc.perform(post(target).with(csrf()).session(owner.session())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.expenseId").value(id))
            .andExpect(jsonPath("$.purpose").value("已编辑费用"));
        assertThat(jdbcTemplate.queryForObject("SELECT request_fingerprint FROM expense WHERE id = ?",
            String.class, id)).isEqualTo(legacy);
    }

    private static final byte[] PNG = new byte[] {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    /** 带预算的已确认活动：预算为活动字段，经费场景需要非零预算才能验证超支。 */
    private Long createApprovedActivityWithBudget(
        TestSession owner, Long orgId, Long ownerMemberId, String startTime, String budget) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/organizations/" + orgId + "/activities")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"name\":\"经费核对活动\",\"ownerMemberId\":%d,\"description\":\"简介\","
                    + "\"startTime\":\"%s\",\"endTime\":\"%s\",\"location\":\"礼堂\","
                    + "\"registrationScope\":\"INTERNAL\",\"capacity\":10,\"budget\":\"%s\"}")
                    .formatted(ownerMemberId, startTime, startTime.replace("T14:00", "T16:00"), budget)))
            .andExpect(status().isCreated())
            .andReturn();
        Long activityId = Long.parseLong(readField(created, "$.id"));
        submitForApproval(owner, orgId, activityId);
        return activityId;
    }

    private String expensesPath(Long orgId, Long activityId) {
        return "/api/organizations/" + orgId + "/activities/" + activityId + "/expenses";
    }

    private String createExpense(TestSession actor, Long orgId, Long activityId, String purpose,
        String amount, String noReceiptNote) throws Exception {
        MvcResult created = mockMvc.perform(post(expensesPath(orgId, activityId))
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"purpose\":\"%s\",\"amount\":%s,\"incurredOn\":\"2027-07-01\","
                    + "\"category\":\"MATERIAL\",\"handledNote\":\"经手说明\",\"noReceiptNote\":%s}")
                    .formatted(purpose, amount,
                        noReceiptNote == null ? "null" : "\"" + noReceiptNote + "\"")))
            .andExpect(status().isCreated())
            .andReturn();
        return readField(created, "$.expenseId");
    }

    private long versionOf(Long expenseId) {
        return jdbcTemplate.queryForObject("SELECT version FROM expense WHERE id = ?", Long.class, expenseId);
    }

    private void submitExpense(TestSession actor, Long orgId, Long activityId, String expenseId) throws Exception {
        mockMvc.perform(post(expensesPath(orgId, activityId) + "/" + expenseId + "/submit")
                .with(csrf()).session(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + versionOf(Long.parseLong(expenseId)) + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"));
    }

    private void confirmExpense(TestSession actor, Long orgId, Long activityId, String expenseId,
        String overBudgetNote, int expectedStatus) throws Exception {
        String note = overBudgetNote == null ? "null" : "\"" + overBudgetNote + "\"";
        mockMvc.perform(post(expensesPath(orgId, activityId) + "/" + expenseId + "/confirm")
                .with(csrf()).session(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + versionOf(Long.parseLong(expenseId))
                    + ",\"overBudgetNote\":" + note + "}"))
            .andExpect(status().is(expectedStatus));
    }

    private void assertSummary(Long orgId, Long activityId, TestSession actor, String budget,
        String pending, String approved, String balance) throws Exception {
        mockMvc.perform(get(expensesPath(orgId, activityId) + "/summary").session(actor.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.budget").value(budget))
            .andExpect(jsonPath("$.pendingAmount").value(pending))
            .andExpect(jsonPath("$.approvedAmount").value(approved))
            .andExpect(jsonPath("$.balance").value(balance));
    }

    @Test
    void draftSubmitConfirmChainUpdatesSummary() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivityWithBudget(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-07-10T14:00:00+08:00", "100.00");

        assertSummary(orgId, activityId, owner, "100.00", "0.00", "0.00", "100.00");
        String expenseId = createExpense(owner, orgId, activityId, "横幅制作", "40.00", "供应商未开票");
        assertSummary(orgId, activityId, owner, "100.00", "0.00", "0.00", "100.00");

        submitExpense(owner, orgId, activityId, expenseId);
        assertSummary(orgId, activityId, owner, "100.00", "40.00", "0.00", "100.00");

        confirmExpense(owner, orgId, activityId, expenseId, null, 200);
        assertSummary(orgId, activityId, owner, "100.00", "0.00", "40.00", "60.00");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM expense WHERE id = ?", String.class, Long.parseLong(expenseId)))
            .isEqualTo("APPROVED");

        // 重复确认不产生第二次有效结果：状态守卫拒绝
        confirmExpense(owner, orgId, activityId, expenseId, null, 409);
        assertSummary(orgId, activityId, owner, "100.00", "0.00", "40.00", "60.00");

        // 费用登记进入草稿删除依赖清点
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId + "/expenses")
                .session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].amount").value("40.00"))
            .andExpect(jsonPath("$.items[0].voucherCount").value("0"));
    }

    @Test
    void overBudgetNeedsOwnerNoteAndBalanceMayBeNegative() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivityWithBudget(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-07-11T14:00:00+08:00", "100.00");

        String first = createExpense(owner, orgId, activityId, "场地费", "60.00", "无票说明");
        String second = createExpense(owner, orgId, activityId, "餐饮费", "60.00", "无票说明");
        submitExpense(owner, orgId, activityId, first);
        submitExpense(owner, orgId, activityId, second);

        confirmExpense(owner, orgId, activityId, first, null, 200);

        // 合计 120 > 预算 100：未填超支说明时拒绝，且不写入结果
        mockMvc.perform(post(expensesPath(orgId, activityId) + "/" + second + "/confirm")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + versionOf(Long.parseLong(second)) + "}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("EXPENSE_OVER_BUDGET"))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("超出预算")));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM expense WHERE id = ?", String.class, Long.parseLong(second)))
            .isEqualTo("PENDING");

        // 组织负责人填写说明后仍允许确认；余额允许为负
        confirmExpense(owner, orgId, activityId, second, "实际人数超出预期，超支部分由组织自筹", 200);
        assertSummary(orgId, activityId, owner, "100.00", "0.00", "120.00", "-20.00");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT over_budget_note FROM expense WHERE id = ?", String.class, Long.parseLong(second)))
            .contains("自筹");
    }

    @Test
    void registrationAndConfirmationFollowPermissionMatrix() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession member = registerAndLogin();
        TestSession outsider = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, member, orgId);
        Long ownerMemberId = memberIdOf(orgId, owner.username());
        Long memberMemberId = memberIdOf(orgId, member.username());
        // 活动负责人由普通成员担任：可登记，不可确认（计划书 7.4）
        Long activityId = createApprovedActivityWithBudget(owner, orgId, memberMemberId,
            "2027-07-12T14:00:00+08:00", "200.00");

        String expenseId = createExpense(member, orgId, activityId, "物资采购", "30.00", "无票说明");
        submitExpense(member, orgId, activityId, expenseId);

        // 活动负责人不能确认，但可以看到“看得到但不能确认”的 403
        confirmExpense(member, orgId, activityId, expenseId, null, 403);
        // 普通成员与外部账号既读不到也登记不了：统一 404
        mockMvc.perform(get(expensesPath(orgId, activityId)).session(owner.session()))
            .andExpect(status().isOk());
        TestSession plainMember = registerAndLogin();
        inviteAndAccept(owner, plainMember, orgId);
        mockMvc.perform(get(expensesPath(orgId, activityId)).session(plainMember.session()))
            .andExpect(status().isNotFound());
        mockMvc.perform(post(expensesPath(orgId, activityId))
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(plainMember.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"越权\",\"amount\":1.00,\"incurredOn\":\"2027-07-01\",\"category\":\"OTHER\"}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(get(expensesPath(orgId, activityId) + "/summary").session(outsider.session()))
            .andExpect(status().isNotFound());
        assertThat(ownerMemberId).isNotNull();
    }

    @Test
    void rejectedExpenseCanBeEditedAndResubmitted() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivityWithBudget(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-07-13T14:00:00+08:00", "500.00");
        String expenseId = createExpense(owner, orgId, activityId, "打印材料", "80.00", "无票说明");
        submitExpense(owner, orgId, activityId, expenseId);

        mockMvc.perform(post(expensesPath(orgId, activityId) + "/" + expenseId + "/reject")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + versionOf(Long.parseLong(expenseId)) + ",\"reason\":\"金额与报价不符\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.rejectReason").value("金额与报价不符"));
        assertSummary(orgId, activityId, owner, "500.00", "0.00", "0.00", "500.00");

        // 驳回后可修改重提：版本前置条件与状态守卫同时生效
        mockMvc.perform(post(expensesPath(orgId, activityId) + "/" + expenseId + "/update")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"purpose\":\"打印材料（修正）\",\"amount\":75.50,\"incurredOn\":\"2027-07-13\","
                    + "\"category\":\"PRINTING\",\"noReceiptNote\":\"无票说明\",\"version\":%d}")
                    .formatted(versionOf(Long.parseLong(expenseId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.amount").value("75.50"));
        submitExpense(owner, orgId, activityId, expenseId);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT reject_reason FROM expense WHERE id = ?", String.class, Long.parseLong(expenseId)))
            .isNull();
        confirmExpense(owner, orgId, activityId, expenseId, null, 200);
        assertSummary(orgId, activityId, owner, "500.00", "0.00", "75.50", "424.50");
    }

    @Test
    void voidedExpenseLeavesStatisticsAndCorrectionCountsOnce() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivityWithBudget(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-07-14T14:00:00+08:00", "300.00");
        String expenseId = createExpense(owner, orgId, activityId, "交通费", "120.00", "无票说明");
        submitExpense(owner, orgId, activityId, expenseId);
        confirmExpense(owner, orgId, activityId, expenseId, null, 200);
        assertSummary(orgId, activityId, owner, "300.00", "0.00", "120.00", "180.00");

        // 未作废的已确认记录不能另建更正，否则两笔确认后会重复计入有效支出。
        mockMvc.perform(post(expensesPath(orgId, activityId) + "/" + expenseId + "/correction")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"交通费（更正）\",\"amount\":100.00,\"incurredOn\":\"2027-07-14\","
                    + "\"category\":\"TRANSPORT\",\"noReceiptNote\":\"无票说明\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("EXPENSE_STATE_CONFLICT"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM expense WHERE activity_id = ?",
            Integer.class, activityId)).isEqualTo(1);

        mockMvc.perform(post(expensesPath(orgId, activityId) + "/" + expenseId + "/void")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + versionOf(Long.parseLong(expenseId)) + ",\"reason\":\"重复登记\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("VOIDED"))
            .andExpect(jsonPath("$.voidReason").value("重复登记"));
        assertSummary(orgId, activityId, owner, "300.00", "0.00", "0.00", "300.00");

        // 更正登记：关联原记录，重新确认后只计一次
        MvcResult correction = mockMvc.perform(post(
                expensesPath(orgId, activityId) + "/" + expenseId + "/correction")
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"交通费（更正）\",\"amount\":100.00,\"incurredOn\":\"2027-07-14\","
                    + "\"category\":\"TRANSPORT\",\"noReceiptNote\":\"无票说明\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.correctsExpenseId").value(expenseId))
            .andReturn();
        String correctionId = readField(correction, "$.expenseId");
        submitExpense(owner, orgId, activityId, correctionId);
        confirmExpense(owner, orgId, activityId, correctionId, null, 200);
        assertSummary(orgId, activityId, owner, "300.00", "0.00", "100.00", "200.00");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM expense WHERE activity_id = ? AND status = 'APPROVED'",
            Integer.class, activityId)).isEqualTo(1);
    }

    @Test
    void versionAndIdempotencyContractsHold() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivityWithBudget(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-07-15T14:00:00+08:00", "100.00");

        // 缺防重键：400
        mockMvc.perform(post(expensesPath(orgId, activityId))
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"无键\",\"amount\":10.00,\"incurredOn\":\"2027-07-01\",\"category\":\"OTHER\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        // 金额必须大于零、最多两位小数
        mockMvc.perform(post(expensesPath(orgId, activityId))
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"零金额\",\"amount\":0,\"incurredOn\":\"2027-07-01\",\"category\":\"OTHER\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 同键同内容重放返回原记录；同键异内容 409
        String key = newIdempotencyKey();
        String body = "{\"purpose\":\"防重支出\",\"amount\":25.00,\"incurredOn\":\"2027-07-02\","
            + "\"category\":\"OTHER\",\"noReceiptNote\":\"无票说明\"}";
        MvcResult first = mockMvc.perform(post(expensesPath(orgId, activityId))
                .header("Idempotency-Key", key)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andReturn();
        String firstId = readField(first, "$.expenseId");
        MvcResult replay = mockMvc.perform(post(expensesPath(orgId, activityId))
                .header("Idempotency-Key", key)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andReturn();
        assertThat(readField(replay, "$.expenseId")).isEqualTo(firstId);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM expense WHERE activity_id = ?", Integer.class, activityId)).isEqualTo(1);
        mockMvc.perform(post(expensesPath(orgId, activityId))
                .header("Idempotency-Key", key)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"另一个支出\",\"amount\":26.00,\"incurredOn\":\"2027-07-02\","
                    + "\"category\":\"OTHER\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        // 缺版本：提交与更新都被拒
        mockMvc.perform(post(expensesPath(orgId, activityId) + "/" + firstId + "/submit")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors[0].field").value("version"));
        mockMvc.perform(post(expensesPath(orgId, activityId) + "/" + firstId + "/update")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"缺版本\",\"amount\":26.00,\"incurredOn\":\"2027-07-02\",\"category\":\"OTHER\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors[0].field").value("version"));

        // 过期版本：409
        submitExpense(owner, orgId, activityId, firstId);
        mockMvc.perform(post(expensesPath(orgId, activityId) + "/" + firstId + "/confirm")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("EDIT_CONFLICT"));
    }

    @Test
    void voucherReplayCannotChangeExpenseTarget() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivityWithBudget(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-07-15T14:00:00+08:00", "100.00");
        String firstExpense = createExpense(owner, orgId, activityId, "第一笔支出", "10.00", null);
        String otherExpense = createExpense(owner, orgId, activityId, "第二笔支出", "10.00", null);
        String key = newIdempotencyKey();
        String firstPath = expensesPath(orgId, activityId) + "/" + firstExpense + "/attachments";
        MvcResult first = mockMvc.perform(multipart(firstPath)
                .file(new MockMultipartFile("file", "凭证.png", "image/png", PNG))
                .header("Idempotency-Key", key).with(csrf()).session(owner.session()))
            .andExpect(status().isCreated()).andReturn();
        String attachmentId = readField(first, "$.attachmentId");
        String originalFingerprint = jdbcTemplate.queryForObject(
            "SELECT request_fingerprint FROM attachment WHERE id = ?", String.class, attachmentId);

        // 同文件、同键不能从第一笔费用重放到第二笔费用。
        mockMvc.perform(multipart(expensesPath(orgId, activityId) + "/" + otherExpense + "/attachments")
                .file(new MockMultipartFile("file", "凭证.png", "image/png", PNG))
                .header("Idempotency-Key", key).with(csrf()).session(owner.session()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        MvcResult replay = mockMvc.perform(multipart(firstPath)
                .file(new MockMultipartFile("file", "凭证.png", "image/png", PNG))
                .header("Idempotency-Key", key).with(csrf()).session(owner.session()))
            .andExpect(status().isCreated()).andReturn();
        assertThat(readField(replay, "$.attachmentId")).isEqualTo(attachmentId);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attachment WHERE expense_id = ?",
            Integer.class, otherExpense)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attachment WHERE request_key = ?",
            Integer.class, key)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT request_fingerprint FROM attachment WHERE id = ?",
            String.class, attachmentId)).isEqualTo(originalFingerprint);
    }

    @Test
    void voucherReplayRequiresOriginalUploader() throws Exception {
        TestSession owner = registerAndLogin();
        TestSession activityOwner = registerAndLogin();
        Long orgId = createOrg(owner);
        inviteAndAccept(owner, activityOwner, orgId);
        Long activityId = createApprovedActivityWithBudget(owner, orgId,
            memberIdOf(orgId, activityOwner.username()), "2027-07-15T14:00:00+08:00", "100.00");
        String expenseId = createExpense(owner, orgId, activityId, "操作者核对", "10.00", null);
        String uploadPath = expensesPath(orgId, activityId) + "/" + expenseId + "/attachments";
        String key = newIdempotencyKey();
        mockMvc.perform(multipart(uploadPath)
                .file(new MockMultipartFile("file", "凭证.png", "image/png", PNG))
                .header("Idempotency-Key", key).with(csrf()).session(owner.session()))
            .andExpect(status().isCreated());

        // 两个账号均有票据权限，防重意图仍只属于原上传账号。
        mockMvc.perform(multipart(uploadPath)
                .file(new MockMultipartFile("file", "凭证.png", "image/png", PNG))
                .header("Idempotency-Key", key).with(csrf()).session(activityOwner.session()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attachment WHERE request_key = ?",
            Integer.class, key)).isEqualTo(1);
    }

    @Test
    void voucherUploadSatisfiesNoReceiptRuleAndStaysOutOfActivityDocs() throws Exception {        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivityWithBudget(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-07-16T14:00:00+08:00", "100.00");
        String expenseId = createExpense(owner, orgId, activityId, "耗材", "50.00", null);

        // 未上传凭证又没有无票说明：提交被拒并指向 noReceiptNote
        mockMvc.perform(post(expensesPath(orgId, activityId) + "/" + expenseId + "/submit")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + versionOf(Long.parseLong(expenseId)) + "}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors[0].field").value("noReceiptNote"));

        // 上传凭证后可以提交；凭证不出现在活动资料列表
        mockMvc.perform(multipart(expensesPath(orgId, activityId) + "/" + expenseId + "/attachments")
                .file(new MockMultipartFile("file", "收据.png", "image/png", PNG))
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.expenseId").value(expenseId));
        mockMvc.perform(get(expensesPath(orgId, activityId) + "/" + expenseId + "/attachments")
                .session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].originalName").value("收据.png"));
        mockMvc.perform(get("/api/organizations/" + orgId + "/activities/" + activityId + "/attachments")
                .session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));

        submitExpense(owner, orgId, activityId, expenseId);
        confirmExpense(owner, orgId, activityId, expenseId, null, 200);
        mockMvc.perform(get(expensesPath(orgId, activityId)).session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].voucherCount").value("1"));
    }

    /**
     * P5.2 并发确认：预算 100、两笔各 60 的待确认支出同时确认。
     * 活动行排他锁把两次确认串行化，后到的一次必须看到先到者的已确认合计并按超支处理——
     * 只允许一笔成功，另一笔 409 EXPENSE_OVER_BUDGET，且已确认合计不会变成 120 而无人说明。
     */
    @Test
    void concurrentConfirmsDetectCombinedOverBudget() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivityWithBudget(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-07-17T14:00:00+08:00", "100.00");
        String first = createExpense(owner, orgId, activityId, "并发甲", "60.00", "无票说明");
        String second = createExpense(owner, orgId, activityId, "并发乙", "60.00", "无票说明");
        submitExpense(owner, orgId, activityId, first);
        submitExpense(owner, orgId, activityId, second);

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.List<Integer> statuses = new java.util.ArrayList<>();
        try {
            java.util.List<java.util.concurrent.Callable<Integer>> calls = java.util.List.of(
                () -> confirmStatus(owner, orgId, activityId, first),
                () -> confirmStatus(owner, orgId, activityId, second));
            for (java.util.concurrent.Future<Integer> result : pool.invokeAll(calls)) {
                statuses.add(result.get());
            }
        } finally {
            pool.shutdownNow();
        }

        java.util.Collections.sort(statuses);
        assertThat(statuses).containsExactly(200, 409);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM expense WHERE activity_id = ? AND status = 'APPROVED'",
            Integer.class, activityId)).isEqualTo(1);
        assertSummary(orgId, activityId, owner, "100.00", "60.00", "60.00", "40.00");
    }

    /**
     * P5.3 边界：零预算活动同样可以登记与确认支出——任何金额都算超支，必须填写说明；
     * 余额为负；没有支出时不产生统计记录。
     */
    @Test
    void zeroBudgetActivityRequiresNoteForAnyExpense() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivityWithBudget(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-07-18T14:00:00+08:00", "0.00");

        // 无支出：统计为零且余额为零
        assertSummary(orgId, activityId, owner, "0.00", "0.00", "0.00", "0.00");
        mockMvc.perform(get(expensesPath(orgId, activityId)).session(owner.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0));

        String expenseId = createExpense(owner, orgId, activityId, "临时支出", "10.00", "无票说明");
        submitExpense(owner, orgId, activityId, expenseId);
        confirmExpense(owner, orgId, activityId, expenseId, null, 409);
        confirmExpense(owner, orgId, activityId, expenseId, "零预算活动的必要支出", 200);
        assertSummary(orgId, activityId, owner, "0.00", "0.00", "10.00", "-10.00");
    }

    /**
     * P2.3／P5.3：预算下限——新预算不得小于已确认支出合计；同时确认调整期待确认期间费用只读。
     */
    @Test
    void budgetFloorAndAdjustmentFreezeProtectConfirmedExpenses() throws Exception {
        TestSession owner = registerAndLogin();
        Long orgId = createOrg(owner);
        Long activityId = createApprovedActivityWithBudget(owner, orgId, memberIdOf(orgId, owner.username()),
            "2027-07-19T14:00:00+08:00", "200.00");
        String expenseId = createExpense(owner, orgId, activityId, "已确认支出", "80.00", "无票说明");
        submitExpense(owner, orgId, activityId, expenseId);
        confirmExpense(owner, orgId, activityId, expenseId, null, 200);

        // 撤回确认后才能改预算；低于已确认支出被拒
        mockMvc.perform(post("/api/organizations/" + orgId + "/activities/" + activityId + "/withdraw-approval")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"reason\":\"预算调整\"}")))
            .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"budget\":\"50.00\"}")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ACTIVITY_BUDGET_BELOW_APPROVED"));

        // 调整期待确认期间费用只读：登记、确认、作废都被拒
        mockMvc.perform(post(expensesPath(orgId, activityId))
                .header("Idempotency-Key", newIdempotencyKey())
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"调整期支出\",\"amount\":10.00,\"incurredOn\":\"2027-07-19\","
                    + "\"category\":\"OTHER\",\"noReceiptNote\":\"无票说明\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ACTIVITY_STATE_CONFLICT"));
        mockMvc.perform(post(expensesPath(orgId, activityId) + "/" + expenseId + "/void")
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + versionOf(Long.parseLong(expenseId)) + ",\"reason\":\"调整期作废\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ACTIVITY_STATE_CONFLICT"));
        assertSummary(orgId, activityId, owner, "200.00", "0.00", "80.00", "120.00");

        // 不低于已确认支出的预算可以保存
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/api/organizations/" + orgId + "/activities/" + activityId)
                .with(csrf()).session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(versioned(activityId, "{\"budget\":\"80.00\"}")))
            .andExpect(status().isOk());
        assertSummary(orgId, activityId, owner, "80.00", "0.00", "80.00", "0.00");
    }

    private Integer confirmStatus(TestSession actor, Long orgId, Long activityId, String expenseId) {
        try {
            return mockMvc.perform(post(expensesPath(orgId, activityId) + "/" + expenseId + "/confirm")
                    .with(csrf()).session(actor.session())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":" + versionOf(Long.parseLong(expenseId)) + "}"))
                .andReturn().getResponse().getStatus();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
