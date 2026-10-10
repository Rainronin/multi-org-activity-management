package com.example.activity.modules.account;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AccountMapper {

    int insert(Account account);

    Account findByUsername(@Param("username") String username);

    String findUsernameById(@Param("id") Long id);

    Account findById(@Param("id") Long id);

    Account findByIdForUpdate(@Param("id") Long id);

    Account findByUsernameForUpdate(@Param("username") String username);

    Long selectAuthVersionById(@Param("id") Long id);

    int updatePasswordAndBumpVersion(@Param("id") Long id, @Param("passwordHash") String passwordHash);
}
