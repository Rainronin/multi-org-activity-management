package com.example.activity.modules.task;

import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaskMapper {

    int insert(Task task);

    Task findById(@Param("id") Long id);

    /**
     * S08／F07 锁协议：任务行共享锁（附件登记等子记录写入前按「组织 → 活动 → 任务」取锁）。
     * 与任务改派的 UPDATE 互斥，避免改派与资料登记各自按旧承担者判断。
     */
    Long lockTaskShared(@Param("id") Long id);

    List<Task> listByActivity(@Param("activityId") Long activityId);

    /** 本人任务：跨活动按组织过滤，未完成任务在前。 */
    List<Task> listMine(@Param("orgId") Long orgId, @Param("assigneeMemberId") Long assigneeMemberId);

    int updateAssignment(
        @Param("id") Long id,
        @Param("title") String title,
        @Param("description") String description,
        @Param("assigneeMemberId") Long assigneeMemberId,
        @Param("priority") String priority,
        @Param("dueAt") OffsetDateTime dueAt,
        @Param("version") Long version);

    /** 状态流转带前置状态守卫；完成时写入完成时间，重开时清空。 */
    int transition(
        @Param("id") Long id,
        @Param("fromStatus") String fromStatus,
        @Param("toStatus") String toStatus,
        @Param("setCompletedAt") boolean setCompletedAt);

    /** 只读职责查询：供 organization 模块退出的未结责任检查（单向依赖，后端架构 2.1）。 */
    int countOpenByAssignee(@Param("orgId") Long orgId, @Param("assigneeMemberId") Long assigneeMemberId);

    /** S07：草稿删除前的依赖检查。 */
    int countByActivity(@Param("activityId") Long activityId);
}
