package com.ieltsai.ieltsaibackend.mapper;

import com.ieltsai.ieltsaibackend.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    @Insert("""
        INSERT INTO `user`(username, email, password)
        VALUES(#{username}, #{email}, #{password})
        """)
    void insert(User user);

    @Select("""
        SELECT id, username, email, created_at AS createdAt
        FROM `user`
        WHERE id = #{id}
        """)
    User findById(@Param("id") Long id);

    @Select("""
        SELECT id, username, email, password, created_at AS createdAt
        FROM `user`
        WHERE username = #{username}
        """)
    User findByUsername(@Param("username") String username);
}
