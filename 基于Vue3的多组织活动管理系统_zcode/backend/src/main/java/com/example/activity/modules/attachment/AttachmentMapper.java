package com.example.activity.modules.attachment;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AttachmentMapper {

    int insert(Attachment attachment);

    Attachment findActiveById(@Param("id") Long id);

    List<Attachment> listActiveByActivity(@Param("activityId") Long activityId);

    int markDeleted(@Param("id") Long id);

    /** S07：草稿删除前的依赖检查（只统计未删除附件）。 */
    int countByActivity(@Param("activityId") Long activityId);
}
