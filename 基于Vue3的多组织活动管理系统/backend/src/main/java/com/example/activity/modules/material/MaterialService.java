package com.example.activity.modules.material;

import com.example.activity.common.ApiException;
import com.example.activity.common.RequestFingerprint;
import com.example.activity.common.audit.AuditService;
import com.example.activity.modules.activity.Activity;
import com.example.activity.modules.activity.ActivityMapper;
import com.example.activity.modules.organization.OrganizationMember;
import com.example.activity.modules.organization.OrganizationMemberMapper;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 物资准备（P3.3）：新增／编辑／状态由组织负责人或活动负责人维护；
 * 普通成员仅查看；任务完成不改变物资状态；数量为正整数。
 */
@Service
public class MaterialService {

    private final MaterialMapper materialMapper;
    private final ActivityMapper activityMapper;
    private final OrganizationMemberMapper memberMapper;
    private final com.example.activity.modules.organization.OrganizationMapper organizationMapper;
    private final AuditService auditService;

    public MaterialService(
        MaterialMapper materialMapper,
        ActivityMapper activityMapper,
        OrganizationMemberMapper memberMapper,
        com.example.activity.modules.organization.OrganizationMapper organizationMapper,
        AuditService auditService) {
        this.materialMapper = materialMapper;
        this.activityMapper = activityMapper;
        this.memberMapper = memberMapper;
        this.organizationMapper = organizationMapper;
        this.auditService = auditService;
    }

    public record MaterialItem(
        Long materialId, Long activityId, String name, Integer quantity, String unit,
        String assigneeMemberId, String assigneeUsername, String status, String note, Long version) {
    }

    /**
     * S08 锁协议：物资写入前按「组织 → 活动 → 子记录」取锁，
     * 权限、调整期与筹备期判断都在锁内基于当前活动完成（F04）。
     */
    private Activity requireManageContext(Long accountId, Long orgId, Long activityId) {
        organizationMapper.lockOrganizationShared(orgId);
        activityMapper.lockActivityShared(activityId);
        OrganizationMember actor = memberMapper.findActive(orgId, accountId);
        Activity activity = actor == null ? null : activityMapper.findById(activityId);
        if (actor == null || activity == null || !activity.getOrgId().equals(orgId)
            || !activity.isVisibleTo(actor, accountId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "活动不存在或无权访问。");
        }
        boolean canManage = OrganizationMember.ROLE_OWNER.equals(actor.getRole())
            || actor.getId().equals(activity.getOwnerMemberId());
        if (!canManage) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问", "仅组织负责人或活动负责人可以维护物资。");
        }
        return activity;
    }

    private void requireWritable(Activity activity) {
        // S07／F02／计划书 6.1：调整期待确认（撤回草稿或重新提交待确认）期间物资只读
        if (activity.isPendingReconfirmation()) {
            throw new ApiException(HttpStatus.CONFLICT, "ACTIVITY_STATE_CONFLICT", "活动安排调整中",
                "活动安排调整中，期间物资只读；重新确认后再维护。");
        }
        requirePreparationWritable(activity);
    }

    /** 物资的新增、编辑与状态流转共用锁内状态检查，归档与取消后只读。 */
    private void requirePreparationWritable(Activity activity) {
        if (!activity.isPreparationWritable()) {
            throw new ApiException(HttpStatus.CONFLICT, "ACTIVITY_STATE_CONFLICT", "当前活动物资只读",
                "仅筹备、执行和复盘阶段可维护物资。");
        }
    }

    private void validateAssignee(Long orgId, Long assigneeMemberId) {
        if (assigneeMemberId == null) {
            return;
        }
        OrganizationMember assignee = memberMapper.findById(assigneeMemberId);
        if (assignee == null || !assignee.getOrgId().equals(orgId)
            || !OrganizationMember.STATUS_ACTIVE.equals(assignee.getStatus())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MATERIAL_ASSIGNEE_INVALID", "物资负责人无效",
                "物资负责人必须是本组织的有效成员。",
                List.of(new com.example.activity.common.FieldErrorItem("assigneeMemberId", "必须是本组织的有效成员")));
        }
    }

    /** 物资读取与活动详情同可见性（S06）：未发布活动不向非负责人成员泄露清单。 */
    public List<MaterialItem> list(Long accountId, Long orgId, Long activityId) {
        OrganizationMember actor = memberMapper.findActive(orgId, accountId);
        Activity activity = actor == null ? null : activityMapper.findById(activityId);
        if (actor == null || activity == null || !activity.getOrgId().equals(orgId)
            || !activity.isVisibleTo(actor, accountId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "活动不存在或无权访问。");
        }
        return materialMapper.listByActivity(activityId).stream().map(this::toItem).toList();
    }

    @Transactional
    public MaterialItem create(Long accountId, Long orgId, Long activityId, Material draft, String requestKey) {
        Activity activity = requireManageContext(accountId, orgId, activityId);
        String key = normalizeRequestKey(requestKey);
        String fingerprint = key == null ? null : RequestFingerprint.of(activityId, draft.getName(),
            draft.getQuantity(), draft.getUnit(), draft.getAssigneeMemberId(), draft.getNote());
        if (key != null) {
            Material existing = materialMapper.findByRequestKey(orgId, key);
            if (existing != null) {
                if (!existing.getActivityId().equals(activityId) || !existing.getCreatedBy().equals(accountId)
                    || !fingerprint.equals(existing.getRequestFingerprint())) {
                    throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "重复的防重键",
                        "该请求键已用于不同的物资创建内容。");
                }
                return toItem(existing);
            }
        }
        requireWritable(activity);
        validateAssignee(orgId, draft.getAssigneeMemberId());
        draft.setOrgId(orgId);
        draft.setActivityId(activityId);
        draft.setStatus(Material.STATUS_PENDING);
        draft.setCreatedBy(accountId);
        draft.setRequestKey(key);
        draft.setRequestFingerprint(fingerprint);
        try {
            materialMapper.insert(draft);
        } catch (DuplicateKeyException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "相同请求已被受理",
                "请保留原请求键核对创建结果。");
        }
        Material created = materialMapper.findById(draft.getId());
        recordMaterialChange(accountId, "MATERIAL_CREATED", null, created, java.util.Map.of("name", draft.getName()));
        return toItem(created);
    }

    private String normalizeRequestKey(String requestKey) {
        if (requestKey == null) return null; // 兼容既有脚本；正式页面始终携带键。
        String key = requestKey.trim();
        if (key.isEmpty() || key.length() > 64) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "输入无效",
                "Idempotency-Key 必须为 1～64 字符。");
        }
        return key;
    }

    @Transactional
    public MaterialItem update(Long accountId, Long orgId, Long activityId, Long materialId, Material patch) {
        requireWritable(requireManageContext(accountId, orgId, activityId));
        Material material = requireMaterial(orgId, activityId, materialId);
        // F05：物资编辑必须携带期望版本，缺失即拒绝
        if (patch.getVersion() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "输入无效",
                "请求字段校验失败，请修改后重试。",
                List.of(new com.example.activity.common.FieldErrorItem("version", "请携带当前版本号（version）后再提交")));
        }
        validateAssignee(orgId, patch.getAssigneeMemberId());
        // S08／F05：版本是必备前置条件，影响行数为 0 即版本冲突
        if (materialMapper.update(materialId, patch.getName(), patch.getQuantity(), patch.getUnit(),
                patch.getAssigneeMemberId(), patch.getNote(), patch.getVersion()) != 1) {
            throw editConflict(material.getVersion());
        }
        Material updated = materialMapper.findById(materialId);
        recordMaterialChange(accountId, "MATERIAL_UPDATED", material, updated, null);
        return toItem(updated);
    }

    @Transactional
    public MaterialItem changeStatus(Long accountId, Long orgId, Long activityId, Long materialId, String toStatus) {
        requireWritable(requireManageContext(accountId, orgId, activityId));
        Material material = requireMaterial(orgId, activityId, materialId);
        List<String> valid = List.of(Material.STATUS_PENDING, Material.STATUS_IN_PROGRESS,
            Material.STATUS_READY, Material.STATUS_CANCELLED);
        if (!valid.contains(toStatus) || material.getStatus().equals(toStatus)) {
            throw new ApiException(HttpStatus.CONFLICT, "MATERIAL_STATE_CONFLICT", "物资状态不允许此操作",
                "目标状态无效或与当前状态相同。");
        }
        materialMapper.updateStatus(materialId, toStatus);
        Material updated = materialMapper.findById(materialId);
        recordMaterialChange(accountId, "MATERIAL_STATUS_" + toStatus, material, updated,
            java.util.Map.of("from", material.getStatus(), "to", toStatus));
        return toItem(updated);
    }

    /** S08／F05：版本前置条件下更新影响行数为 0 即版本冲突，要求刷新后重试。 */
    private ApiException editConflict(Long currentVersion) {
        return new ApiException(HttpStatus.CONFLICT, "EDIT_CONFLICT", "物资已被他人修改",
            "该物资已由其他操作保存（当前版本 " + currentVersion + "），请刷新后重新编辑。");
    }

    private Material requireMaterial(Long orgId, Long activityId, Long materialId) {
        Material material = materialMapper.findByIdForUpdate(materialId);
        if (material == null || !material.getOrgId().equals(orgId)
            || !material.getActivityId().equals(activityId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "物资不存在或无权访问。");
        }
        return material;
    }

    private void recordMaterialChange(Long accountId, String action, Material before, Material after, java.util.Map<String, ?> detail) {
        java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
        if (detail != null) entry.putAll(detail);
        entry.put("orgId", after.getOrgId());
        entry.put("activityId", after.getActivityId());
        entry.put("before", auditState(before));
        entry.put("after", auditState(after));
        auditService.record(accountId, "ACTIVITY", action, "material:" + after.getId(), null, entry);
    }

    private java.util.Map<String, Object> auditState(Material material) {
        if (material == null) return null;
        java.util.Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("status", material.getStatus());
        state.put("version", material.getVersion());
        state.put("assigneeMemberId", material.getAssigneeMemberId());
        state.put("quantity", material.getQuantity());
        state.put("unit", material.getUnit());
        return state;
    }

    private MaterialItem toItem(Material material) {
        String username = material.getAssigneeMemberId() == null
            ? null
            : memberMapper.findUsernameByMemberId(material.getAssigneeMemberId());
        return new MaterialItem(
            material.getId(), material.getActivityId(), material.getName(), material.getQuantity(),
            material.getUnit(),
            material.getAssigneeMemberId() == null ? null : String.valueOf(material.getAssigneeMemberId()),
            username == null ? "" : username, material.getStatus(), material.getNote(),
            material.getVersion());
    }
}
