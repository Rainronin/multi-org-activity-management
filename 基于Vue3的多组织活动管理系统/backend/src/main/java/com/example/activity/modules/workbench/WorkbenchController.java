package com.example.activity.modules.workbench;

import com.example.activity.security.AccountUserDetails;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作台端点（P7.1／P7.2）：按当前身份返回待办与基础统计。
 * 统计只来自真实业务记录，卡片带来源说明与明细路径；跳转后由目标端点重新校验权限。
 */
@RestController
public class WorkbenchController {

    private final WorkbenchService workbenchService;

    public WorkbenchController(WorkbenchService workbenchService) {
        this.workbenchService = workbenchService;
    }

    public record CardResponse(
        String key, String label, String value, String hint, String detailPath, String detailLabel) {
    }

    public record TodoResponse(String kind, String title, String detail, String path) {
    }

    public record WorkbenchResponse(
        String role, List<CardResponse> cards, List<TodoResponse> todos, List<CardResponse> myCards) {
    }

    @GetMapping("/api/organizations/{orgId}/workbench")
    public ResponseEntity<WorkbenchResponse> workbench(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @RequestParam(name = "todoLimit", defaultValue = "5")
        @Min(value = 1, message = "至少 1 条") @Max(value = 20, message = "最多 20 条") int todoLimit) {
        WorkbenchService.Workbench workbench =
            workbenchService.of(principal.getAccountId(), orgId, todoLimit);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new WorkbenchResponse(workbench.role(),
                workbench.cards().stream().map(WorkbenchController::toCard).toList(),
                workbench.todos().stream()
                    .map(todo -> new TodoResponse(todo.kind(), todo.title(), todo.detail(), todo.path()))
                    .toList(),
                workbench.myCards().stream().map(WorkbenchController::toCard).toList()));
    }

    private static CardResponse toCard(WorkbenchService.WorkbenchCard card) {
        return new CardResponse(card.key(), card.label(), card.value(), card.hint(),
            card.detailPath(), card.detailLabel());
    }
}
