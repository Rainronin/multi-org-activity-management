package com.example.activity.modules.account;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RecoveryCredentialMapper {

    int insert(RecoveryCredential credential);

    RecoveryCredential findActiveByAccountId(@Param("accountId") Long accountId);

    /** 仅当仍为 ACTIVE 时消费；返回 0 表示已被并发消费，调用方按失败处理。 */
    int markConsumed(@Param("id") Long id);

    int revokeActiveByAccountId(@Param("accountId") Long accountId);
}
