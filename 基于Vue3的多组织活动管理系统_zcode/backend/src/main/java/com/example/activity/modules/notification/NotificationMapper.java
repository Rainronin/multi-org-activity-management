package com.example.activity.modules.notification;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 通知意图写入（P3 起随业务同事务落库）；列表展示与失败补建随 P7。 */
@Mapper
public interface NotificationMapper {

    int insert(
        @Param("recipientAccountId") Long recipientAccountId,
        @Param("type") String type,
        @Param("activityId") Long activityId,
        @Param("payload") String payload);
}
