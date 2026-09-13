package com.example.activity.modules.task;

import com.example.activity.common.ApiException;
import com.example.activity.common.audit.AuditService;
import com.example.activity.modules.activity.Activity;
import com.example.activity.modules.activity.ActivityMapper;
import com.example.activity.modules.organization.OrganizationMember;
import com.example.activity.modules.organization.OrganizationMemberMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 活动任务（P3.2）：创建与分派归组织负责人／活动负责人；普通成员仅推进本人任务
 * （待处理→进行中／已完成，进行中→已完成）；取消与重新打开由管理者执行；分派写入站内通知意图（同事务）。
 * 子资源读取沿用活动自身可见性（S06）。
 */
@Service
public class TaskService {

    private final TaskMapper taskMapper;
    private final ActivityMapper activityMapper;
    private final OrganizationMemberMapper memberMapper;
    private final com.example.activity.modules.notification.NotificationMapper notificationMapper;
    private final AuditService auditService;

    public TaskService(
        TaskMapper taskMapper,
        ActivityMapper activityMapper,
        OrganizationMemberMapper memberMapper,
        com.example.activity.modules.notification.NotificationMapper notificationMapper,
        AuditService auditService) {
        this.taskMapper = taskMapper;
        this.activityMapper = activityMapper;
        this.memberMapper = memberMapper;
        this.notificationMapper = notificationMapper;
        this.auditService = auditService;
    }

    public record TaskItem(
        Long taskId, Long activityId, String activityName, String title, String description,
        String assigneeMemberId, String assigneeUsername, String priority, OffsetDateTime dueAt,
        String status, OffsetDateTime completedAt, boolean overdue) {
    }

    private record Context(OrganizationMember actor, Activity activity) {
    }

    private Context requireVisibleContext(Long accountId, Long orgId, Long activityId) {
        OrganizationMember actor = memberMapper.findActive(orgId, accountId);
        Activity activity = actor == null ? null : activityMapper.findById(activityId);
        // S06：子资源沿用活动自身可见性，未发布活动不因任务列表／流转接口泄露
        if (actor == null || activity == null || !activity.getOrgId().equals(orgId)
            || !activity.isVisibleTo(actor, accountId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "活动不存在或无权访问。");
        }
        return new Context(actor, activity);
    }

    private boolean canManage(Context ctx) {
        return OrganizationMember.ROLE_OWNER.equals(ctx.actor().getRole())
            || ctx.actor().getId().equals(ctx.activity().getOwnerMemberId());
    }

    /** S07／计划书 6.1：调整期待确认期间任务只读，避免继续修改筹备数据。 */
    private void requireNotPendingReconfirmation(Context ctx) {
        if (ctx.activity().isPendingReconfirmation()) {
            throw new ApiException(HttpStatus.CONFLICT, "ACTIVITY_STATE_CONFLICT", "活动安排调整中",
                "活动已撤回到草稿等待重新确认，期间任务只读；重新确认后再修改。");
        }
    }

    @Transactional
    public TaskItem create(Long accountId, Long orgId, Long activityId, Task draft) {
        Context ctx = requireVisibleContext(accountId, orgId, activityId);
        if (!canManage(ctx)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问", "仅组织负责人或活动负责人可以创建任务。");
        }
        requireNotPendingReconfirmation(ctx);
        validateAssignee(orgId, draft.getAssigneeMemberId());
        draft.setOrgId(orgId);
        draft.setActivityId(activityId);
        draft.setStatus(Task.STATUS_PENDING);
        draft.setCreatedBy(accountId);
        if (draft.getPriority() == null) {
            draft.setPriority("MEDIUM");
        }
        taskMapper.insert(draft);
        notifyAssignment(orgId, activityId, draft);
        auditService.record(accountId, "ACTIVITY", "TASK_CREATED", "task:" + draft.getId(),
            null, java.util.Map.of("title", draft.getTitle(), "activityId", activityId));
        return toItem(taskMapper.findById(draft.getId()));
    }

    public List<TaskItem> listByActivity(Long accountId, Long orgId, Long activityId) {
        requireVisibleContext(accountId, orgId, activityId);
        return taskMapper.listByActivity(activityId).stream().map(this::toItem).toList();
    }

    public List<TaskItem> listMine(Long accountId, Long orgId) {
        OrganizationMember actor = memberMapper.findActive(orgId, accountId);
        if (actor == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "组织不存在或无权访问。");
        }
        return taskMapper.listMine(orgId, actor.getId()).stream().map(this::toItem).toList();
    }

    @Transactional
    public TaskItem update(Long accountId, Long orgId, Long activityId, Long taskId, Task patch) {
        Context ctx = requireVisibleContext(accountId, orgId, activityId);
        if (!canManage(ctx)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问", "仅组织负责人或活动负责人可以编辑任务。");
        }
        requireNotPendingReconfirmation(ctx);
        Task task = requireTask(orgId, activityId, taskId);
        if (Task.STATUS_COMPLETED.equals(task.getStatus()) || Task.STATUS_CANCELLED.equals(task.getStatus())) {
            throw conflict("TASK_STATE_CONFLICT", "任务已完成或已取消", "如需调整请先重新打开任务。");
        }
        validateAssignee(orgId, patch.getAssigneeMemberId());
        boolean reassigned = !patch.getAssigneeMemberId().equals(task.getAssigneeMemberId());
        // PATCH 语义：未传优先级时沿用当前值，避免把非空列写成 NULL
        String priority = patch.getPriority() == null ? task.getPriority() : patch.getPriority();
        taskMapper.updateAssignment(taskId, patch.getTitle(), patch.getDescription(),
            patch.getAssigneeMemberId(), priority, patch.getDueAt());
        if (reassigned) {
            notifyAssignment(orgId, activityId, taskMapper.findById(taskId));
        }
        auditService.record(accountId, "ACTIVITY", "TASK_UPDATED", "task:" + taskId,
            null, java.util.Map.of("activityId", activityId));
        return toItem(taskMapper.findById(taskId));
    }

    /** 管理者状态操作：取消、重新打开及任意合法流转。 */
    @Transactional
    public TaskItem manageTransition(Long accountId, Long orgId, Long activityId, Long taskId, String toStatus) {
        Context ctx = requireVisibleContext(accountId, orgId, activityId);
        if (!canManage(ctx)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问", "仅组织负责人或活动负责人可以取消或重新打开任务。");
        }
        requireNotPendingReconfirmation(ctx);
        Task task = requireTask(orgId, activityId, taskId);
        transition(task, toStatus, accountId);
        return toItem(taskMapper.findById(taskId));
    }

    /** 普通成员推进本人任务：待处理→进行中／已完成，进行中→已完成（S05），不可取消或重开。 */
    @Transactional
    public TaskItem advanceOwn(Long accountId, Long orgId, Long activityId, Long taskId, String toStatus) {
        Context ctx = requireVisibleContext(accountId, orgId, activityId);
        requireNotPendingReconfirmation(ctx);
        Task task = requireTask(orgId, activityId, taskId);
        if (!task.getAssigneeMemberId().equals(ctx.actor().getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问", "只能推进本人任务。");
        }
        // S05：普通成员仅可按流程推进（待处理可直接完成，也可先转进行中），不可取消或重新打开
        boolean allowed = (Task.STATUS_PENDING.equals(task.getStatus())
                && (Task.STATUS_IN_PROGRESS.equals(toStatus) || Task.STATUS_COMPLETED.equals(toStatus)))
            || (Task.STATUS_IN_PROGRESS.equals(task.getStatus()) && Task.STATUS_COMPLETED.equals(toStatus));
        if (!allowed) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_STATE_CONFLICT", "任务状态不允许此操作",
                "普通成员只能把本人任务从待处理推进为进行中或已完成，再从进行中标记为已完成。");
        }
        transition(task, toStatus, accountId);
        return toItem(taskMapper.findById(taskId));
    }

    private void transition(Task task, String toStatus, Long accountId) {
        String from = task.getStatus();
        boolean allowed = switch (toStatus) {
            case Task.STATUS_IN_PROGRESS -> Task.STATUS_PENDING.equals(from);
            case Task.STATUS_COMPLETED -> Task.STATUS_PENDING.equals(from) || Task.STATUS_IN_PROGRESS.equals(from);
            case Task.STATUS_PENDING -> Task.STATUS_IN_PROGRESS.equals(from)
                || Task.STATUS_CANCELLED.equals(from) || Task.STATUS_COMPLETED.equals(from);
            case Task.STATUS_CANCELLED -> Task.STATUS_PENDING.equals(from) || Task.STATUS_IN_PROGRESS.equals(from);
            default -> false;
        };
        if (!allowed) {
            throw conflict("TASK_STATE_CONFLICT", "任务状态不允许此操作", "任务状态已变化，请刷新后查看。");
        }
        if (taskMapper.transition(task.getId(), from, toStatus, Task.STATUS_COMPLETED.equals(toStatus)) != 1) {
            throw conflict("TASK_STATE_CONFLICT", "任务状态不允许此操作", "任务状态已变化，请刷新后查看。");
        }
        auditService.record(accountId, "ACTIVITY", "TASK_STATUS_" + toStatus, "task:" + task.getId(),
            null, java.util.Map.of("from", from, "activityId", task.getActivityId()));
    }

    private void validateAssignee(Long orgId, Long assigneeMemberId) {
        OrganizationMember assignee = memberMapper.findById(assigneeMemberId);
        if (assignee == null || !assignee.getOrgId().equals(orgId)
            || !OrganizationMember.STATUS_ACTIVE.equals(assignee.getStatus())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TASK_ASSIGNEE_INVALID", "任务负责人无效",
                "任务负责人必须是本组织的有效成员。",
                List.of(new com.example.activity.common.FieldErrorItem("assigneeMemberId", "必须是本组织的有效成员")));
        }
    }

    private void notifyAssignment(Long orgId, Long activityId, Task task) {
        // 通知意图同事务落库（P3.2）；展示与补建随 P7。当前来源：任务创建与改派。
        OrganizationMember assignee = memberMapper.findById(task.getAssigneeMemberId());
        if (assignee == null) {
            return;
        }
        Activity activity = activityMapper.findById(activityId);
        String payload = "新任务「" + task.getTitle() + "」：" + (activity == null ? "" : activity.getName());
        if (payload.length() > 500) {
            payload = payload.substring(0, 500);
        }
        notificationMapper.insert(assignee.getAccountId(), "TASK_ASSIGNED",
            activityId, payload);
    }

    private Task requireTask(Long orgId, Long activityId, Long taskId) {
        Task task = taskMapper.findById(taskId);
        if (task == null || !task.getOrgId().equals(orgId) || !task.getActivityId().equals(activityId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "任务不存在或无权访问。");
        }
        return task;
    }

    private ApiException conflict(String code, String title, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, title, detail);
    }

    private TaskItem toItem(Task task) {
        String username = memberMapper.findUsernameByMemberId(task.getAssigneeMemberId());
        boolean overdue = task.getDueAt() != null
            && task.getDueAt().isBefore(OffsetDateTime.now())
            && (Task.STATUS_PENDING.equals(task.getStatus()) || Task.STATUS_IN_PROGRESS.equals(task.getStatus()));
        return new TaskItem(
            task.getId(), task.getActivityId(), null, task.getTitle(), task.getDescription(),
            String.valueOf(task.getAssigneeMemberId()), username == null ? "" : username,
            task.getPriority(), task.getDueAt(), task.getStatus(), task.getCompletedAt(), overdue);
    }
}
