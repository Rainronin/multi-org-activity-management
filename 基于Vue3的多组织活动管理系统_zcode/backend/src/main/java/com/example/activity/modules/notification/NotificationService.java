package com.example.activity.modules.notification;

import com.example.activity.modules.activity.Activity;
import com.example.activity.modules.activity.ActivityMapper;
import com.example.activity.modules.organization.OrganizationMember;
import com.example.activity.modules.organization.OrganizationMemberMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 站内提示读取（P7.3）：列表与详情跳转都按当前权限重新校验——
 * 成员撤权或活动不再可见时，旧提示不再下发（不通过旧消息泄露内部信息）。
 * 公开活动的外部报名者按其报名身份保留变更提示（后端架构 6.2）。
 */
@Service
public class NotificationService {

    private final NotificationMapper notificationMapper;
    private final ActivityMapper activityMapper;
    private final OrganizationMemberMapper memberMapper;
    private final com.example.activity.modules.registration.RegistrationMapper registrationMapper;

    public NotificationService(
        NotificationMapper notificationMapper,
        ActivityMapper activityMapper,
        OrganizationMemberMapper memberMapper,
        com.example.activity.modules.registration.RegistrationMapper registrationMapper) {
        this.notificationMapper = notificationMapper;
        this.activityMapper = activityMapper;
        this.memberMapper = memberMapper;
        this.registrationMapper = registrationMapper;
    }

    public record NotificationItem(
        Long notificationId, String type, String payload, OffsetDateTime createdAt,
        OffsetDateTime readAt, String activityId, String activityName, boolean accessible) {
    }

    public record NotificationPage(List<NotificationItem> items, int page, int pageSize, long total, long unread) {
    }

    /** 本人提示列表：逐条按当前权限复核，不可访问的条目不返回也不计入总数。 */
    public NotificationPage listMine(Long accountId, int page, int pageSize) {
        List<Notification> all = new ArrayList<>();
        long offset = 0;
        // 小规模场景：按页扫描原始行，过滤掉已失去访问权的条目后再分页
        while (true) {
            List<Notification> batch = notificationMapper.listByAccount(accountId, offset, 100);
            if (batch.isEmpty()) {
                break;
            }
            all.addAll(batch);
            offset += batch.size();
            if (batch.size() < 100) {
                break;
            }
        }
        List<NotificationItem> accessible = new ArrayList<>();
        for (Notification notification : all) {
            NotificationItem item = toItem(accountId, notification);
            if (item.accessible()) {
                accessible.add(item);
            }
        }
        int from = Math.min((page - 1) * pageSize, accessible.size());
        int to = Math.min(from + pageSize, accessible.size());
        return new NotificationPage(accessible.subList(from, to), page, pageSize, accessible.size(),
            notificationMapper.countUnread(accountId));
    }

    /** 未读数量：同样只统计仍可访问的条目。 */
    public long unreadCount(Long accountId) {
        return listMine(accountId, 1, 1).unread();
    }

    @Transactional
    public void markRead(Long accountId, Long notificationId) {
        Notification notification = notificationMapper.findById(notificationId);
        if (notification == null || !notification.getRecipientAccountId().equals(accountId)) {
            // 他人提示与不存在同响应，不泄露存在性
            throw new com.example.activity.common.ApiException(
                org.springframework.http.HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "提示不存在或无权访问。");
        }
        notificationMapper.markRead(notificationId, accountId);
    }

    @Transactional
    public int markAllRead(Long accountId) {
        return notificationMapper.markAllRead(accountId);
    }

    private NotificationItem toItem(Long accountId, Notification notification) {
        Activity activity = notification.getActivityId() == null
            ? null : activityMapper.findById(notification.getActivityId());
        boolean accessible = activity != null && canAccess(accountId, activity);
        return new NotificationItem(
            notification.getId(), notification.getType(), notification.getPayload(),
            notification.getCreatedAt(), notification.getReadAt(),
            activity == null ? null : String.valueOf(activity.getId()),
            // 名称随访问权限一起判定：不可访问时不下发任何活动信息
            accessible ? activity.getName() : null,
            accessible);
    }

    /** 当前是否仍可访问该活动：有效成员且活动可见，或公开活动的报名者按外部身份保留。 */
    private boolean canAccess(Long accountId, Activity activity) {
        OrganizationMember member = memberMapper.findActive(activity.getOrgId(), accountId);
        if (member != null && activity.isVisibleTo(member, accountId)) {
            return true;
        }
        if (!Activity.SCOPE_PUBLIC.equals(activity.getRegistrationScope())
            || Activity.STATUS_DRAFT.equals(activity.getStatus())
            || Activity.STATUS_PENDING_APPROVAL.equals(activity.getStatus())) {
            return false;
        }
        return registrationMapper.findByActivityAndAccount(activity.getId(), accountId) != null;
    }
}
