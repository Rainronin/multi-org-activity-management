package com.example.activity.modules.ai;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiConfigMapper {

    AiConfig findByOrgId(@Param("orgId") Long orgId);

    int insert(AiConfig config);

    /** 保存配置：仅当版本未变化；connection_revision 由服务层判定是否递增。 */
    int update(AiConfig config);

    /** 记录当前配置版本的成功测试；配置版本已变化时不写入（旧测试响应不得标记新配置）。 */
    int markTested(
        @Param("orgId") Long orgId,
        @Param("revision") Integer revision,
        @Param("testedBy") Long testedBy);

    /** 启用／停用：带期望版本；启用额外要求当前版本已通过测试（服务层判定）。 */
    int setEnabled(
        @Param("orgId") Long orgId,
        @Param("enabled") boolean enabled,
        @Param("operatorAccountId") Long operatorAccountId,
        @Param("expectedVersion") Long expectedVersion);

    /** 清除密钥：同时停用并递增 revision（服务层传入新值）。 */
    int clearKey(
        @Param("orgId") Long orgId,
        @Param("revision") Integer revision,
        @Param("operatorAccountId") Long operatorAccountId,
        @Param("expectedVersion") Long expectedVersion);
}
