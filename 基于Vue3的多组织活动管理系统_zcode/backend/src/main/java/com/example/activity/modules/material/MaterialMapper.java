package com.example.activity.modules.material;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MaterialMapper {

    int insert(Material material);

    Material findById(@Param("id") Long id);

    List<Material> listByActivity(@Param("activityId") Long activityId);

    int update(@Param("id") Long id, @Param("name") String name, @Param("quantity") Integer quantity,
        @Param("unit") String unit, @Param("assigneeMemberId") Long assigneeMemberId,
        @Param("note") String note);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /** 只读职责查询：未就绪且未取消的物资责任数。 */
    int countOpenByAssignee(@Param("orgId") Long orgId, @Param("assigneeMemberId") Long assigneeMemberId);
}
