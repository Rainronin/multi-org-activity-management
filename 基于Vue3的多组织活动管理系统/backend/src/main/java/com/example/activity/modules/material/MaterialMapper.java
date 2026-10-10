package com.example.activity.modules.material;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MaterialMapper {

    int insert(Material material);

    Material findById(@Param("id") Long id);

    /** 写入前锁住物资并读取当前状态、承担者与版本。 */
    Material findByIdForUpdate(@Param("id") Long id);

    Material findByRequestKey(@Param("orgId") Long orgId, @Param("requestKey") String requestKey);

    List<Material> listByActivity(@Param("activityId") Long activityId);

    int update(@Param("id") Long id, @Param("name") String name, @Param("quantity") Integer quantity,
        @Param("unit") String unit, @Param("assigneeMemberId") Long assigneeMemberId,
        @Param("note") String note, @Param("version") Long version);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /** 只读职责查询：未就绪且未取消的物资责任数。 */
    int countOpenByAssignee(@Param("orgId") Long orgId, @Param("assigneeMemberId") Long assigneeMemberId);

    /** S07：草稿删除前的依赖检查。 */
    int countByActivity(@Param("activityId") Long activityId);
}
