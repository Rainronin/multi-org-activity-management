package com.example.activity.modules.material;

import com.example.activity.common.ApiException;
import com.example.activity.common.audit.AuditService;
import com.example.activity.modules.activity.Activity;
import com.example.activity.modules.activity.ActivityMapper;
import com.example.activity.modules.organization.OrganizationMember;
import com.example.activity.modules.organization.OrganizationMemberMapper;
import java.util.List;
import org.springframework.http.HttpStatus;
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

    private record Context(OrganizationMember actor, Activity activity) {
    }

    /**
     * S08 锁协议：物资写入前按「组织 → 活动 → 子记录」取锁，
     * 权限、调整期与筹备期判断都在锁内基于当前活动完成（F04）。
     */
    private Context requireManageContext(Long accountId, Long orgId, Long activityId) {
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
        // S07／F02／计划书 6.1：调整期待确认（撤回草稿或重新提交待确认）期间物资只读
        if (activity.isPendingReconfirmation()) {
            throw new ApiException(HttpStatus.CONFLICT, "ACTIVITY_STATE_CONFLICT", "活动安排调整中",
                "活动安排调整中，期间物资只读；重新确认后再维护。");
        }
        return new Context(actor, activity);
    }

    /** F02：新增物资只在已确认筹备期允许；初始草稿与待确认同样拒绝。锁内以当前活动判断。 */
    private void requirePreparationWritable(Activity activity) {
        if (!activity.isPreparationWritable()) {
            throw new ApiException(HttpStatus.CONFLICT, "ACTIVITY_STATE_CONFLICT", "活动尚未进入筹备",
                "物资只能在活动确认后的筹备阶段新增。");
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
    public MaterialItem create(Long accountId, Long orgId, Long activityId, Material draft) {
        Context ctx = requireManageContext(accountId, orgId, activityId);
        // F02：新增筹备数据仅在已确认筹备期允许（与权限检查同处锁内）
        requirePreparationWritable(ctx.activity());
        validateAssignee(orgId, draft.getAssigneeMemberId());
        draft.setOrgId(orgId);
        draft.setActivityId(activityId);
        draft.setStatus(Material.STATUS_PENDING);
        draft.setCreatedBy(accountId);
        materialMapper.insert(draft);
        auditService.record(accountId, "ACTIVITY", "MATERIAL_CREATED", "material:" + draft.getId(),
            null, java.util.Map.of("name", draft.getName(), "activityId", activityId));
        return toItem(materialMapper.findById(draft.getId()));
    }

    @Transactional
    public MaterialItem update(Long accountId, Long orgId, Long activityId, Long materialId, Material patch) {
        requireManageContext(accountId, orgId, activityId);
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
        auditService.record(accountId, "ACTIVITY", "MATERIAL_UPDATED", "material:" + materialId,
            null, java.util.Map.of("activityId", activityId));
        return toItem(materialMapper.findById(materialId));
    }

    @Transactional
    public MaterialItem changeStatus(Long accountId, Long orgId, Long activityId, Long materialId, String toStatus) {
        requireManageContext(accountId, orgId, activityId);
        Material material = requireMaterial(orgId, activityId, materialId);
        List<String> valid = List.of(Material.STATUS_PENDING, Material.STATUS_IN_PROGRESS,
            Material.STATUS_READY, Material.STATUS_CANCELLED);
        if (!valid.contains(toStatus) || material.getStatus().equals(toStatus)) {
            throw new ApiException(HttpStatus.CONFLICT, "MATERIAL_STATE_CONFLICT", "物资状态不允许此操作",
                "目标状态无效或与当前状态相同。");
        }
        materialMapper.updateStatus(materialId, toStatus);
        auditService.record(accountId, "ACTIVITY", "MATERIAL_STATUS_" + toStatus, "material:" + materialId,
            null, java.util.Map.of("from", material.getStatus(), "to", toStatus));
        return toItem(materialMapper.findById(materialId));
    }

    /** S08／F05：版本前置条件下更新影响行数为 0 即版本冲突，要求刷新后重试。 */
    private ApiException editConflict(Long currentVersion) {
        return new ApiException(HttpStatus.CONFLICT, "EDIT_CONFLICT", "物资已被他人修改",
            "该物资已由其他操作保存（当前版本 " + currentVersion + "），请刷新后重新编辑。");
    }

    private Material requireMaterial(Long orgId, Long activityId, Long materialId) {
        Material material = materialMapper.findById(materialId);
        if (material == null || !material.getOrgId().equals(orgId)
            || !material.getActivityId().equals(activityId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "物资不存在或无权访问。");
        }
        return material;
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
