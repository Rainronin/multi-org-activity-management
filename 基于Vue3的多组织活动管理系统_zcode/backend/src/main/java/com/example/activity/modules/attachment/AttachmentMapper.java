package com.example.activity.modules.attachment;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AttachmentMapper {

    int insert(Attachment attachment);

    Attachment findActiveById(@Param("id") Long id);

    /** F06：按组织的防重键查已提交附件（含已软删除记录，避免同键重放复活已删附件）。 */
    Attachment findByRequestKey(@Param("orgId") Long orgId, @Param("requestKey") String requestKey);

    List<Attachment> listActiveByActivity(@Param("activityId") Long activityId);

    /** S10：活动资料列表排除任务资料，任务资料按任务查询。 */
    List<Attachment> listActiveByTask(
        @Param("activityId") Long activityId, @Param("taskId") Long taskId);

    /** P5：费用凭证按支出查询。 */
    List<Attachment> listActiveByExpense(
        @Param("activityId") Long activityId, @Param("expenseId") Long expenseId);

    int markDeleted(@Param("id") Long id);

    /** S07：草稿删除前的依赖检查（只统计未删除附件）。 */
    int countByActivity(@Param("activityId") Long activityId);

    /** S09：登记失败补偿前确认存储名是否已被引用（含已软删除记录，避免误删在用文件）。 */
    int countByStoredName(@Param("storedName") String storedName);

    /** P5：费用凭证数量（只读依赖，供 expense 模块判断是否缺票据说明）。 */
    int countActiveByExpense(@Param("expenseId") Long expenseId);
}
