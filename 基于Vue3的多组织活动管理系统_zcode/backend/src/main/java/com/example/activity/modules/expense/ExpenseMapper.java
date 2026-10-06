package com.example.activity.modules.expense;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ExpenseMapper {

    int insert(Expense expense);

    Expense findById(@Param("id") Long id);

    Expense findByIdForUpdate(@Param("id") Long id);

    Expense findByRequestKey(@Param("orgId") Long orgId, @Param("requestKey") String requestKey);

    List<Expense> listByActivity(
        @Param("activityId") Long activityId,
        @Param("status") String status,
        @Param("offset") long offset,
        @Param("limit") int limit);

    long countByActivity(@Param("activityId") Long activityId, @Param("status") String status);

    /** 统计：只累加指定状态的有效明细（P5.3）。 */
    BigDecimal sumAmountByStatus(@Param("activityId") Long activityId, @Param("status") String status);

    /** 编辑：仅草稿／已驳回可改，带期望版本。 */
    int update(
        @Param("id") Long id,
        @Param("purpose") String purpose,
        @Param("amount") BigDecimal amount,
        @Param("incurredOn") java.time.LocalDate incurredOn,
        @Param("category") String category,
        @Param("handledNote") String handledNote,
        @Param("noReceiptNote") String noReceiptNote,
        @Param("version") Long version);

    /** 状态流转带前置状态守卫；返回 0 表示状态已变化。 */
    int transition(
        @Param("id") Long id,
        @Param("fromStatus") String fromStatus,
        @Param("toStatus") String toStatus,
        @Param("submittedBy") Long submittedBy,
        @Param("decidedBy") Long decidedBy,
        @Param("reason") String reason,
        @Param("clearRejectReason") boolean clearRejectReason,
        @Param("overBudgetNote") String overBudgetNote);

    /** 作废：仅已确认记录，写原因与时间。 */
    int voidApproved(
        @Param("id") Long id,
        @Param("actorAccountId") Long actorAccountId,
        @Param("reason") String reason);

    /** 草稿删除依赖检查：活动下费用数量。 */
    int countByActivityId(@Param("activityId") Long activityId);
}
