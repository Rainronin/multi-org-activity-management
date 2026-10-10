package com.example.activity.modules.ai;

import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiGenerationMapper {

    int insert(AiGeneration generation);

    AiGeneration findById(@Param("id") Long id);

    AiGeneration findByIdForUpdate(@Param("id") Long id);

    List<AiGeneration> listStaleRunning(@Param("orgId") Long orgId, @Param("before") OffsetDateTime before);

    /** 防重键重放：同组织同操作者同键返回既有记录，不重复调用模型。 */
    AiGeneration findByRequestKey(
        @Param("orgId") Long orgId,
        @Param("operatorAccountId") Long operatorAccountId,
        @Param("requestKey") String requestKey);

    /** 结束一次调用（成功或失败）；仅 RUNNING 可结束，重复结束返回 0。 */
    int finish(
        @Param("id") Long id,
        @Param("status") String status,
        @Param("outputText") String outputText,
        @Param("errorCode") String errorCode,
        @Param("requestId") String requestId);

    /** 人工采用草稿：记录采用人与时间，只写一次。 */
    int markAdopted(@Param("id") Long id, @Param("accountId") Long accountId);

    /** 组织额度：按持久生成记录统计当日调用次数（含连接测试）。 */
    long countSince(@Param("orgId") Long orgId, @Param("since") OffsetDateTime since);

    /** 活动最近一次指定类型的生成记录（复盘页展示采用状态）。 */
    List<AiGeneration> listRecent(
        @Param("activityId") Long activityId,
        @Param("type") String type,
        @Param("limit") int limit);
}
