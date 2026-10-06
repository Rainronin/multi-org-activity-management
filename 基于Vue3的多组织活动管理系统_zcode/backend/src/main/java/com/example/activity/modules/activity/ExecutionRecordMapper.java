package com.example.activity.modules.activity;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ExecutionRecordMapper {

    int insert(ExecutionRecord record);

    ExecutionRecord findById(@Param("id") Long id);

    ExecutionRecord findByRequestKey(@Param("orgId") Long orgId, @Param("requestKey") String requestKey);

    List<ExecutionRecord> listByActivity(@Param("activityId") Long activityId);

    int countByActivity(@Param("activityId") Long activityId);
}
