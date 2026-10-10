package com.example.activity.modules.notification;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 通知意图写入（P3 起随业务同事务落库）与站内提示读取（P7）。
 * 写入使用 INSERT IGNORE + (接收账号, 类型, 来源操作) 唯一键：重放与并发不产生重复提示。
 */
@Mapper
public interface NotificationMapper {

    int insert(
        @Param("recipientAccountId") Long recipientAccountId,
        @Param("type") String type,
        @Param("activityId") Long activityId,
        @Param("payload") String payload,
        @Param("sourceKey") String sourceKey);

    Notification findById(@Param("id") Long id);

    List<Notification> listByAccount(
        @Param("accountId") Long accountId, @Param("offset") long offset, @Param("limit") int limit);

    long countByAccount(@Param("accountId") Long accountId);

    long countUnread(@Param("accountId") Long accountId);

    /** 标记已读：只允许本人记录，重复标记幂等。 */
    int markRead(@Param("id") Long id, @Param("accountId") Long accountId);

    int markAllRead(@Param("accountId") Long accountId);
}
