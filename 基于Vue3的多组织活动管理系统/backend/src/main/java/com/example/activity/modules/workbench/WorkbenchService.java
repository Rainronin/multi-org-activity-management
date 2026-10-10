package com.example.activity.modules.workbench;

import com.example.activity.common.ApiException;
import com.example.activity.modules.activity.Activity;
import com.example.activity.modules.activity.ActivityMapper;
import com.example.activity.modules.organization.OrganizationMember;
import com.example.activity.modules.organization.OrganizationMemberMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 工作台（P7.1／P7.2）：按身份给出待办与基础统计。
 *
 * <p>数字口径与各业务阶段一致：进行中活动只算筹备与执行（复盘待办单列、取消归档不混入）、
 * 任务完成率以未取消任务为分母（分母为零返回 null，界面显示“—”）、有效报名同 P4、
 * 经费同 P5.3、反馈同 P6.3。每张卡片都带来源说明与明细路径，供用户进入授权明细复算。
 */
@Service
public class WorkbenchService {

    private final WorkbenchMapper workbenchMapper;
    private final ActivityMapper activityMapper;
    private final OrganizationMemberMapper memberMapper;

    public WorkbenchService(
        WorkbenchMapper workbenchMapper,
        ActivityMapper activityMapper,
        OrganizationMemberMapper memberMapper) {
        this.workbenchMapper = workbenchMapper;
        this.activityMapper = activityMapper;
        this.memberMapper = memberMapper;
    }

    /** 卡片：标签、数值、口径说明与明细入口。 */
    public record WorkbenchCard(
        String key, String label, String value, String hint, String detailPath, String detailLabel) {
    }

    /** 待办：标题、补充说明与跳转路径（跳转后仍由目标端点重新校验权限）。 */
    public record WorkbenchTodo(String kind, String title, String detail, String path) {
    }

    public record Workbench(
        String role, List<WorkbenchCard> cards, List<WorkbenchTodo> todos, List<WorkbenchCard> myCards) {
    }

    /** 组织负责人／活动负责人／普通成员三种身份的工作台。 */
    public Workbench of(Long accountId, Long orgId, int todoLimit) {
        OrganizationMember actor = memberMapper.findActive(orgId, accountId);
        if (actor == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "组织不存在或无权访问。");
        }
        boolean orgOwner = OrganizationMember.ROLE_OWNER.equals(actor.getRole());
        boolean activityOwner = hasOwnedActivity(orgId, actor.getId());
        String role = orgOwner ? "OWNER" : activityOwner ? "ACTIVITY_OWNER" : "MEMBER";
        Long scopeOwnerMemberId = orgOwner ? null : (activityOwner ? actor.getId() : null);

        List<WorkbenchCard> cards = orgOwner
            ? organizationCards(orgId)
            : activityOwner ? activityOwnerCards(orgId, actor.getId()) : memberCards(orgId, actor.getId(), accountId);
        List<WorkbenchTodo> todos = todos(orgId, actor, orgOwner, activityOwner, todoLimit);
        List<WorkbenchCard> myCards = myCards(orgId, accountId);
        return new Workbench(role, cards, todos, myCards);
    }

    private boolean hasOwnedActivity(Long orgId, Long memberId) {
        return activityMapper.countPage(orgId, null, null, memberId, null) > 0;
    }

    /** 组织负责人：进行中活动、复盘待办、待确认立项、任务与报名、经费与反馈。 */
    private List<WorkbenchCard> organizationCards(Long orgId) {
        OffsetDateTime now = OffsetDateTime.now();
        List<WorkbenchCard> cards = new ArrayList<>();
        long preparing = activityMapper.countPage(orgId, null, Activity.STATUS_APPROVED, null, null);
        long executing = activityMapper.countPage(orgId, null, Activity.STATUS_EXECUTING, null, null);
        long reviewing = activityMapper.countPage(orgId, null, Activity.STATUS_REVIEWING, null, null);
        long pendingApproval = activityMapper.countPage(orgId, null, Activity.STATUS_PENDING_APPROVAL, null, null);
        cards.add(new WorkbenchCard("activities-in-progress", "进行中活动", String.valueOf(preparing + executing),
            "筹备中 " + preparing + " 个、执行中 " + executing + " 个；不含复盘、取消与归档",
            "/org/" + orgId + "/activities", "活动管理"));
        cards.add(new WorkbenchCard("activities-reviewing", "复盘待办", String.valueOf(reviewing),
            "复盘中待处理总结与归档；取消归档不计入正常完成",
            "/org/" + orgId + "/activities", "活动管理"));
        cards.add(new WorkbenchCard("activities-pending", "待确认立项", String.valueOf(pendingApproval),
            "待确认活动数量", "/org/" + orgId + "/activities", "活动管理"));
        addTaskCards(cards, orgId, null, null, now);
        addRegistrationCards(cards, orgId, null);
        addMoneyCards(cards, orgId, null);
        addFeedbackCard(cards, orgId, null);
        return cards;
    }

    /** 活动负责人：聚焦本人负责的活动。 */
    private List<WorkbenchCard> activityOwnerCards(Long orgId, Long memberId) {
        OffsetDateTime now = OffsetDateTime.now();
        List<WorkbenchCard> cards = new ArrayList<>();
        long preparing = activityMapper.countPage(orgId, null, Activity.STATUS_APPROVED, memberId, null);
        long executing = activityMapper.countPage(orgId, null, Activity.STATUS_EXECUTING, memberId, null);
        long reviewing = activityMapper.countPage(orgId, null, Activity.STATUS_REVIEWING, memberId, null);
        cards.add(new WorkbenchCard("activities-mine", "我负责的活动", String.valueOf(preparing + executing + reviewing),
            "筹备中 " + preparing + " 个、执行中 " + executing + " 个、复盘中 " + reviewing + " 个",
            "/org/" + orgId + "/activities", "活动管理"));
        addTaskCards(cards, orgId, memberId, null, now);
        addRegistrationCards(cards, orgId, memberId);
        addMoneyCards(cards, orgId, memberId);
        addFeedbackCard(cards, orgId, memberId);
        return cards;
    }

    /**
     * 普通成员：没有管理视图卡片（他人任务只在管理视图出现），
     * 本人任务与报名由 {@code myCards} 呈现。
     */
    private List<WorkbenchCard> memberCards(Long orgId, Long memberId, Long accountId) {
        return List.of();
    }

    private void addTaskCards(List<WorkbenchCard> cards, Long orgId, Long ownerMemberId,
        Long assigneeMemberId, OffsetDateTime now) {
        long open = workbenchMapper.countTasks(orgId, ownerMemberId, assigneeMemberId, "OPEN", now);
        long overdue = workbenchMapper.countTasks(orgId, ownerMemberId, assigneeMemberId, "OVERDUE", now);
        long notCancelled = workbenchMapper.countTasks(orgId, ownerMemberId, assigneeMemberId, "NOT_CANCELLED", now);
        long completed = workbenchMapper.countTasks(orgId, ownerMemberId, assigneeMemberId, "COMPLETED", now);
        cards.add(new WorkbenchCard("tasks-open", assigneeMemberId != null ? "我的待处理任务" : "待处理任务",
            String.valueOf(open), "待处理与进行中任务合计", "/org/" + orgId + "/my-tasks", "我的任务"));
        cards.add(new WorkbenchCard("tasks-overdue", assigneeMemberId != null ? "我的逾期任务" : "逾期任务",
            String.valueOf(overdue), "按当前时间与截止时间计算，不依赖定时群发",
            "/org/" + orgId + "/my-tasks", "我的任务"));
        cards.add(new WorkbenchCard("tasks-completion", "任务完成率",
            notCancelled == 0 ? "—" : percent(completed, notCancelled),
            notCancelled == 0
                ? "暂无未取消任务，完成率不适用"
                : "已完成 " + completed + " / 未取消 " + notCancelled + "（取消任务不计入分母）",
            "/org/" + orgId + "/activities", "活动管理"));
    }

    private void addRegistrationCards(List<WorkbenchCard> cards, Long orgId, Long ownerMemberId) {
        long effective = workbenchMapper.countEffectiveRegistrations(orgId, ownerMemberId);
        long approved = workbenchMapper.countApprovedRegistrations(orgId, ownerMemberId);
        long capacity = workbenchMapper.sumCapacity(orgId, ownerMemberId);
        cards.add(new WorkbenchCard("registrations-effective", "有效报名人数", String.valueOf(effective),
            "待审与已通过合计；不含取消与驳回", "/org/" + orgId + "/activities", "活动管理"));
        cards.add(new WorkbenchCard("registrations-capacity", "名额使用率",
            capacity == 0 ? "—" : percent(approved, capacity),
            capacity == 0
                ? "参与统计的活动未设置名额，使用率不适用"
                : "已通过 " + approved + " / 名额合计 " + capacity,
            "/org/" + orgId + "/activities", "活动管理"));
    }

    private void addMoneyCards(List<WorkbenchCard> cards, Long orgId, Long ownerMemberId) {
        BigDecimal budget = money(workbenchMapper.sumBudget(orgId, ownerMemberId));
        BigDecimal approved = money(workbenchMapper.sumExpense(orgId, ownerMemberId, "APPROVED"));
        BigDecimal pending = money(workbenchMapper.sumExpense(orgId, ownerMemberId, "PENDING"));
        cards.add(new WorkbenchCard("expense-approved", "已确认支出", approved.toPlainString(),
            "仅统计已确认明细，口径同经费区块；不代表已完成付款", "/org/" + orgId + "/activities", "活动管理"));
        cards.add(new WorkbenchCard("expense-pending", "待确认支出", pending.toPlainString(),
            "待确认明细合计", "/org/" + orgId + "/activities", "活动管理"));
        cards.add(new WorkbenchCard("expense-balance", "预算余额", budget.subtract(approved).toPlainString(),
            "预算合计 " + budget.toPlainString() + " 元减已确认支出；允许为负",
            "/org/" + orgId + "/activities", "活动管理"));
    }

    private void addFeedbackCard(List<WorkbenchCard> cards, Long orgId, Long ownerMemberId) {
        long count = workbenchMapper.countFeedback(orgId, ownerMemberId);
        BigDecimal average = workbenchMapper.averageFeedback(orgId, ownerMemberId);
        cards.add(new WorkbenchCard("feedback", "反馈份数与平均分",
            count == 0 ? "暂无反馈" : count + " 份 / " + average.setScale(1, RoundingMode.HALF_UP).toPlainString(),
            count == 0 ? "尚无有效反馈，平均分显示 —" : "口径同反馈区：报名者与执行角色的主动表达",
            "/org/" + orgId + "/activities", "活动管理"));
    }

    /** 本人视角卡片：我的任务与报名（各身份共用）。 */
    private List<WorkbenchCard> myCards(Long orgId, Long accountId) {
        OffsetDateTime now = OffsetDateTime.now();
        long open = workbenchMapper.countTasks(orgId, null, myMemberId(orgId, accountId), "OPEN", now);
        long overdue = workbenchMapper.countTasks(orgId, null, myMemberId(orgId, accountId), "OVERDUE", now);
        List<WorkbenchCard> cards = new ArrayList<>();
        cards.add(new WorkbenchCard("my-tasks-open", "我的待处理任务", String.valueOf(open),
            "只含本人被分派且未完成的任务", "/org/" + orgId + "/my-tasks", "我的任务"));
        cards.add(new WorkbenchCard("my-tasks-overdue", "我的逾期任务", String.valueOf(overdue),
            "按截止时间与当前时间计算", "/org/" + orgId + "/my-tasks", "我的任务"));
        cards.add(new WorkbenchCard("my-registrations", "我的有效报名",
            String.valueOf(workbenchMapper.countMyEffectiveRegistrations(orgId, accountId)),
            "本人待审与已通过报名", "/", "我的报名"));
        return cards;
    }

    private Long myMemberId(Long orgId, Long accountId) {
        OrganizationMember member = memberMapper.findActive(orgId, accountId);
        return member == null ? null : member.getId();
    }

    /** 待办清单：按身份给出可处理的确认事项，已完成与已取消不列入。 */
    private List<WorkbenchTodo> todos(Long orgId, OrganizationMember actor, boolean orgOwner,
        boolean activityOwner, int limit) {
        List<WorkbenchTodo> todos = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();
        if (orgOwner || activityOwner) {
            Long scope = orgOwner ? null : actor.getId();
            for (WorkbenchMapper.WorkbenchTodoRow row
                : workbenchMapper.listPendingRegistrations(orgId, scope, limit)) {
                todos.add(new WorkbenchTodo("REGISTRATION_PENDING", row.title(), row.detail(),
                    activityPath(orgId, row.activityId())));
            }
            for (WorkbenchMapper.WorkbenchTodoRow row : workbenchMapper.listOverdueTasks(
                orgId, scope, null, now, limit)) {
                todos.add(new WorkbenchTodo("TASK_OVERDUE", row.title(), row.detail(),
                    activityPath(orgId, row.activityId())));
            }
        }
        if (orgOwner) {
            for (WorkbenchMapper.WorkbenchTodoRow row : workbenchMapper.listPendingExpenses(orgId, limit)) {
                todos.add(new WorkbenchTodo("EXPENSE_PENDING", row.title(), row.detail(),
                    activityPath(orgId, row.activityId())));
            }
            for (WorkbenchMapper.WorkbenchTodoRow row : workbenchMapper.listReviewingActivities(orgId, limit)) {
                todos.add(new WorkbenchTodo("ACTIVITY_REVIEW", row.title(), row.detail(),
                    activityPath(orgId, row.activityId())));
            }
        }
        for (WorkbenchMapper.WorkbenchTodoRow row
            : workbenchMapper.listOverdueTasks(orgId, null, actor.getId(), now, limit)) {
            todos.add(new WorkbenchTodo("MY_TASK_OVERDUE", row.title(), row.detail(),
                activityPath(orgId, row.activityId())));
        }
        return todos;
    }

    private String activityPath(Long orgId, Long activityId) {
        return "/org/" + orgId + "/activities/" + activityId;
    }

    private static String percent(long part, long whole) {
        return BigDecimal.valueOf(part * 100.0 / whole).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
