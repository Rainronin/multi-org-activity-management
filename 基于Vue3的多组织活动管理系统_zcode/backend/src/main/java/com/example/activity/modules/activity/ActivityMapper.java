package com.example.activity.modules.activity;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ActivityMapper {

    int insert(Activity activity);

    Activity findById(@Param("id") Long id);

    List<Activity> listPage(
        @Param("orgId") Long orgId,
        @Param("query") String query,
        @Param("status") String status,
        @Param("offset") long offset,
        @Param("limit") int limit);

    long countPage(
        @Param("orgId") Long orgId,
        @Param("query") String query,
        @Param("status") String status);

    /** 仅更新显式传入的字段（PATCH 语义），由 Service 组装非空字段。 */
    int updateDraft(Activity activity);
}
