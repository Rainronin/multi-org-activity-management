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
}
