package com.example.activity.modules.workbench;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 工作台聚合查询（P7.1／P7.2）：只读统计与待办定位，按组织（可选限本人负责的活动）汇总。
 * 所有数字都来自真实业务记录，供界面标注时间范围与来源并跳转到授权明细复算。
 */
@Mapper
public interface WorkbenchMapper {

    /** 任务统计：mode 取 OPEN（待处理＋进行中）、OVERDUE、NOT_CANCELLED、COMPLETED。 */
    long countTasks(
        @Param("orgId") Long orgId,
        @Param("ownerMemberId") Long ownerMemberId,
        @Param("assigneeMemberId") Long assigneeMemberId,
        @Param("mode") String mode,
        @Param("now") java.time.OffsetDateTime now);

    /** 逾期任务清单（本人最需要先处理的事项，可直接跳转）。 */
    List<WorkbenchTodoRow> listOverdueTasks(
        @Param("orgId") Long orgId,
        @Param("ownerMemberId") Long ownerMemberId,
        @Param("assigneeMemberId") Long assigneeMemberId,
        @Param("now") java.time.OffsetDateTime now,
        @Param("limit") int limit);

    /** 待审报名清单（负责人待办）。 */
    List<WorkbenchTodoRow> listPendingRegistrations(
        @Param("orgId") Long orgId,
        @Param("ownerMemberId") Long ownerMemberId,
        @Param("limit") int limit);

    long countPendingRegistrations(
        @Param("orgId") Long orgId, @Param("ownerMemberId") Long ownerMemberId);

    /** 待确认费用清单（组织负责人待办）。 */
    List<WorkbenchTodoRow> listPendingExpenses(
        @Param("orgId") Long orgId, @Param("limit") int limit);

    long countPendingExpenses(@Param("orgId") Long orgId);

    /** 复盘待办：复盘中且总结未确认的活动。 */
    List<WorkbenchTodoRow> listReviewingActivities(@Param("orgId") Long orgId, @Param("limit") int limit);

    /** 有效报名人数（待审与已通过，排除取消与驳回）。 */
    long countEffectiveRegistrations(
        @Param("orgId") Long orgId, @Param("ownerMemberId") Long ownerMemberId);

    /** 本人报名（账号维度）：该组织下本人待审与已通过报名合计。 */
    long countMyEffectiveRegistrations(
        @Param("orgId") Long orgId, @Param("accountId") Long accountId);

    /** 名额使用口径：参与汇总的活动名额合计（仅统计设定了名额的活动）。 */
    long sumCapacity(
        @Param("orgId") Long orgId, @Param("ownerMemberId") Long ownerMemberId);

    long countApprovedRegistrations(
        @Param("orgId") Long orgId, @Param("ownerMemberId") Long ownerMemberId);

    /** 经费指标：按状态汇总（PENDING／APPROVED）与预算合计，口径同 P5.3。 */
    BigDecimal sumExpense(
        @Param("orgId") Long orgId, @Param("ownerMemberId") Long ownerMemberId,
        @Param("status") String status);

    BigDecimal sumBudget(@Param("orgId") Long orgId, @Param("ownerMemberId") Long ownerMemberId);

    /** 反馈口径同 P6.3：份数与平均分。 */
    long countFeedback(@Param("orgId") Long orgId, @Param("ownerMemberId") Long ownerMemberId);

    BigDecimal averageFeedback(@Param("orgId") Long orgId, @Param("ownerMemberId") Long ownerMemberId);

    /** 待办行：标题、补充说明与目标活动，供界面拼跳转路径。 */
    record WorkbenchTodoRow(String title, String detail, Long activityId) {
    }
}
