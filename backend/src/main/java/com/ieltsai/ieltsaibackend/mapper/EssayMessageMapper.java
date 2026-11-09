package com.ieltsai.ieltsaibackend.mapper;

import com.ieltsai.ieltsaibackend.entity.EssayMessage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EssayMessageMapper {

    @Insert("""
        INSERT INTO essay_message(essay_id, `role`, content, created_at)
        VALUES(#{essayId}, #{role}, #{content}, #{createdAt})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insert(EssayMessage message);

    @Select("""
        SELECT id, essay_id AS essayId, `role`, content, created_at AS createdAt
        FROM essay_message
        WHERE essay_id = #{essayId}
        ORDER BY created_at ASC, id ASC
        """)
    List<EssayMessage> findByEssayId(@Param("essayId") Long essayId);

    @Select("""
        SELECT id, essay_id AS essayId, `role`, content, created_at AS createdAt
        FROM essay_message
        WHERE essay_id = #{essayId} AND id < #{beforeId}
        ORDER BY id DESC
        LIMIT 8
        """)
    List<EssayMessage> findRecentBefore(
            @Param("essayId") Long essayId, @Param("beforeId") Long beforeId
    );
}
