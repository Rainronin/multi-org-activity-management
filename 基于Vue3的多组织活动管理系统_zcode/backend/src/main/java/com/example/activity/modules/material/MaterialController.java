package com.example.activity.modules.material;

import com.example.activity.security.AccountUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 物资端点（P3.3）。 */
@RestController
public class MaterialController {

    private final MaterialService materialService;

    public MaterialController(MaterialService materialService) {
        this.materialService = materialService;
    }

    public record MaterialResponse(
        String materialId, Long activityId, String name, Integer quantity, String unit,
        String assigneeMemberId, String assigneeUsername, String status, String note, Long version) {
    }

    public record MaterialsResponse(List<MaterialResponse> items) {
    }

    public record SaveMaterialRequest(
        @NotBlank(message = "物资名称不能为空") @Size(max = 128, message = "名称不超过 128 字符") String name,
        @NotNull(message = "数量不能为空") @Min(value = 1, message = "数量必须为正整数") @Max(value = 1000000, message = "数量过大") Integer quantity,
        @NotBlank(message = "单位不能为空") @Size(max = 16, message = "单位不超过 16 字符") String unit,
        Long assigneeMemberId,
        @Size(max = 500, message = "备注不超过 500 字符") String note,
        /** S08：期望版本；仅更新时携带，做乐观锁。 */
        @Min(value = 1, message = "版本号必须为正整数") Long version) {
    }

    public record MaterialStatusRequest(
        @Pattern(regexp = "^(PENDING|IN_PROGRESS|READY|CANCELLED)$", message = "未知的目标状态") String toStatus) {
    }

    private MaterialResponse toResponse(MaterialService.MaterialItem item) {
        return new MaterialResponse(String.valueOf(item.materialId()), item.activityId(), item.name(),
            item.quantity(), item.unit(), item.assigneeMemberId(), item.assigneeUsername(),
            item.status(), item.note(), item.version());
    }

    @GetMapping("/api/organizations/{orgId}/activities/{activityId}/materials")
    public ResponseEntity<MaterialsResponse> list(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId) {
        List<MaterialResponse> items = materialService.list(principal.getAccountId(), orgId, activityId)
            .stream().map(this::toResponse).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new MaterialsResponse(items));
    }

    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/materials")
    public ResponseEntity<MaterialResponse> create(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @Valid @RequestBody SaveMaterialRequest request) {
        Material draft = new Material();
        draft.setName(request.name().trim());
        draft.setQuantity(request.quantity());
        draft.setUnit(request.unit().trim());
        draft.setAssigneeMemberId(request.assigneeMemberId());
        draft.setNote(request.note() == null || request.note().isBlank() ? null : request.note().trim());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
            .body(toResponse(materialService.create(principal.getAccountId(), orgId, activityId, draft)));
    }

    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/materials/{materialId}/update")
    public ResponseEntity<MaterialResponse> update(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @PathVariable("materialId") Long materialId,
        @Valid @RequestBody SaveMaterialRequest request) {
        Material patch = new Material();
        patch.setName(request.name().trim());
        patch.setQuantity(request.quantity());
        patch.setUnit(request.unit().trim());
        patch.setAssigneeMemberId(request.assigneeMemberId());
        patch.setNote(request.note() == null || request.note().isBlank() ? null : request.note().trim());
        patch.setVersion(request.version());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(materialService.update(
                principal.getAccountId(), orgId, activityId, materialId, patch)));
    }

    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/materials/{materialId}/transition")
    public ResponseEntity<MaterialResponse> changeStatus(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @PathVariable("materialId") Long materialId,
        @Valid @RequestBody MaterialStatusRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(materialService.changeStatus(
                principal.getAccountId(), orgId, activityId, materialId, request.toStatus())));
    }
}
