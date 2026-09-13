package com.example.activity.modules.feedback;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FeedbackMapper {

    int insert(Feedback feedback);

    /** 已存在则更新评分与建议（归档前允许本人修改）。 */
    int update(
        @Param("id") Long id, @Param("rating") Integer rating, @Param("comment") String comment);

    Feedback findByActivityAndAccount(
        @Param("activityId") Long activityId, @Param("accountId") Long accountId);

    List<Feedback> listByActivity(@Param("activityId") Long activityId);

    long countByActivity(@Param("activityId") Long activityId);

    /** 平均评分；无反馈时返回 null（界面显示“暂无反馈”与“—”）。 */
    BigDecimal averageRating(@Param("activityId") Long activityId);

    // ---------- 反馈资格依据（P6.3） ----------

    int insertEligibility(
        @Param("activityId") Long activityId, @Param("accountId") Long accountId,
        @Param("source") String source);

    int countEligibility(@Param("activityId") Long activityId, @Param("accountId") Long accountId);

    List<Long> listEligibleAccountIds(@Param("activityId") Long activityId);
}
