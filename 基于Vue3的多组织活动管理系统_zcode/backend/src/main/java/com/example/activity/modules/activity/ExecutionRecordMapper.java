package com.example.activity.modules.activity;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ExecutionRecordMapper {

    int insert(ExecutionRecord record);

    List<ExecutionRecord> listByActivity(@Param("activityId") Long activityId);

    int countByActivity(@Param("activityId") Long activityId);
}
