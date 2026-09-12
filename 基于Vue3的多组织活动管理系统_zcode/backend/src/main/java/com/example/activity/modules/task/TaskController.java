package com.example.activity.modules.task;

import com.example.activity.security.AccountUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 任务端点（P3.2）：创建/编辑/状态流转/本人任务。 */
@RestController
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    public record TaskResponse(
        String taskId, Long activityId, String title, String description, String assigneeMemberId,
        String assigneeUsername, String priority, OffsetDateTime dueAt, String status,
        OffsetDateTime completedAt, boolean overdue) {
    }

    public record TasksResponse(List<TaskResponse> items) {
    }

    public record CreateTaskRequest(
        @NotBlank(message = "任务标题不能为空") @Size(max = 128, message = "标题不超过 128 字符") String title,
        @Size(max = 2000, message = "说明不超过 2000 字符") String description,
        @NotNull(message = "任务负责人不能为空") Long assigneeMemberId,
        @Pattern(regexp = "^(HIGH|MEDIUM|LOW)$", message = "优先级仅支持 HIGH/MEDIUM/LOW") String priority,
        OffsetDateTime dueAt) {
    }

    public record TransitionRequest(
        @Pattern(regexp = "^(PENDING|IN_PROGRESS|COMPLETED|CANCELLED)$", message = "未知的目标状态") String toStatus) {
    }

    private TaskResponse toResponse(TaskService.TaskItem item) {
        return new TaskResponse(String.valueOf(item.taskId()), item.activityId(), item.title(),
            item.description(), item.assigneeMemberId(), item.assigneeUsername(), item.priority(),
            item.dueAt(), item.status(), item.completedAt(), item.overdue());
    }

    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/tasks")
    public ResponseEntity<TaskResponse> create(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @Valid @RequestBody CreateTaskRequest request) {
        Task draft = new Task();
        draft.setTitle(request.title().trim());
        draft.setDescription(request.description() == null || request.description().isBlank()
            ? null : request.description().trim());
        draft.setAssigneeMemberId(request.assigneeMemberId());
        draft.setPriority(request.priority());
        draft.setDueAt(request.dueAt());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
            .body(toResponse(taskService.create(principal.getAccountId(), orgId, activityId, draft)));
    }

    @GetMapping("/api/organizations/{orgId}/activities/{activityId}/tasks")
    public ResponseEntity<TasksResponse> listByActivity(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId) {
        List<TaskResponse> items = taskService.listByActivity(principal.getAccountId(), orgId, activityId)
            .stream().map(this::toResponse).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new TasksResponse(items));
    }

    @GetMapping("/api/organizations/{orgId}/my-tasks")
    public ResponseEntity<TasksResponse> listMine(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId) {
        List<TaskResponse> items = taskService.listMine(principal.getAccountId(), orgId)
            .stream().map(this::toResponse).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new TasksResponse(items));
    }

    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/tasks/{taskId}/update")
    public ResponseEntity<TaskResponse> update(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @PathVariable("taskId") Long taskId,
        @Valid @RequestBody CreateTaskRequest request) {
        Task patch = new Task();
        patch.setTitle(request.title().trim());
        patch.setDescription(request.description() == null || request.description().isBlank()
            ? null : request.description().trim());
        patch.setAssigneeMemberId(request.assigneeMemberId());
        patch.setPriority(request.priority());
        patch.setDueAt(request.dueAt());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(taskService.update(
                principal.getAccountId(), orgId, activityId, taskId, patch)));
    }

    /** 管理者流转（取消、重新打开等任意合法转换）。 */
    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/tasks/{taskId}/transition")
    public ResponseEntity<TaskResponse> manageTransition(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @PathVariable("taskId") Long taskId,
        @Valid @RequestBody TransitionRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(taskService.manageTransition(
                principal.getAccountId(), orgId, activityId, taskId, request.toStatus())));
    }

    /** 普通成员推进本人任务（待处理→进行中→已完成）。 */
    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/tasks/{taskId}/advance")
    public ResponseEntity<TaskResponse> advance(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @PathVariable("taskId") Long taskId,
        @Valid @RequestBody TransitionRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(taskService.advanceOwn(
                principal.getAccountId(), orgId, activityId, taskId, request.toStatus())));
    }
}
