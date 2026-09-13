package com.example.activity.modules.activity;

import com.example.activity.common.ApiException;
import com.example.activity.modules.organization.Organization;
import com.example.activity.modules.organization.OrganizationMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 公开活动浏览（P4.1，计划书 4 节第 6 条）：未登录可浏览已发布的公开活动基础信息。
 * 仅返回发布者明确填写的内容与报名条件；任务明细、成员联系方式、票据、反馈原文与内部附件不因公开而公开。
 * 当前主状态只有草稿／待确认／已确认，因此“已发布”= 已确认；内部报名范围的活动面向本组织成员，
 * 通过组织内端点访问，不进入公开信息流（计划书第 8 节“内部活动仅对有效成员开放”）。
 */
@Service
public class PublicActivityService {

    private final ActivityMapper activityMapper;
    private final OrganizationMapper organizationMapper;

    public PublicActivityService(ActivityMapper activityMapper, OrganizationMapper organizationMapper) {
        this.activityMapper = activityMapper;
        this.organizationMapper = organizationMapper;
    }

    public record PublicActivityItem(
        String id, String organizationName, String name, String description, String location,
        OffsetDateTime startTime, OffsetDateTime endTime, String registrationScope,
        Integer capacity, OffsetDateTime registrationStart, OffsetDateTime registrationEnd,
        boolean registrationOpen, OffsetDateTime updatedAt) {
    }

    public record PublicActivityPage(List<PublicActivityItem> items, int page, int pageSize, long total) {
    }

    /** 公开列表：仅已确认活动；不返回预算、方案、审批与负责人等内部字段。 */
    public PublicActivityPage list(int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(50, Math.max(1, pageSize));
        long total = activityMapper.countPublicApproved();
        List<PublicActivityItem> items = activityMapper
            .listPublicApproved((long) (safePage - 1) * safeSize, safeSize)
            .stream()
            .map(this::toItem)
            .toList();
        return new PublicActivityPage(items, safePage, safeSize, total);
    }

    /** 公开详情：仅已确认且公开报名的活动；其余一律 404，不泄露草稿存在性。 */
    public PublicActivityItem get(Long activityId) {
        Activity activity = activityMapper.findById(activityId);
        if (activity == null || !Activity.STATUS_APPROVED.equals(activity.getStatus())
            || !Activity.SCOPE_PUBLIC.equals(activity.getRegistrationScope())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "活动不存在或未公开。");
        }
        return toItem(activity);
    }

    private PublicActivityItem toItem(Activity activity) {
        Organization organization = organizationMapper.findById(activity.getOrgId());
        return new PublicActivityItem(
            String.valueOf(activity.getId()),
            organization == null ? "" : organization.getName(),
            activity.getName(), activity.getDescription(), activity.getLocation(),
            activity.getStartTime(), activity.getEndTime(), activity.getRegistrationScope(),
            activity.getCapacity(), activity.getRegistrationStart(), activity.getRegistrationEnd(),
            activity.isRegistrationOpen(), activity.getUpdatedAt());
    }
}
