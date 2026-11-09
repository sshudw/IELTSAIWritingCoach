package com.ieltsai.ieltsaibackend.mapper;

import com.ieltsai.ieltsaibackend.entity.EvaluationTask;
import com.ieltsai.ieltsaibackend.entity.EvaluationTaskStatus;
import org.apache.ibatis.annotations.*;

@Mapper
public interface EvaluationTaskMapper {
    String COLUMNS = """
            id, user_id AS userId, essay_id AS essayId, idempotency_key AS idempotencyKey,
            status, retry_count AS retryCount, error_message AS errorMessage,
            created_at AS createdAt, updated_at AS updatedAt
            """;

    @Insert("""
            INSERT INTO evaluation_task(user_id, essay_id, idempotency_key, status)
            VALUES(#{userId}, #{essayId}, #{idempotencyKey}, 'PENDING')
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(EvaluationTask task);

    @Select("SELECT " + COLUMNS + " FROM evaluation_task WHERE id = #{taskId} AND user_id = #{userId}")
    EvaluationTask findOwned(@Param("taskId") Long taskId, @Param("userId") Long userId);

    @Select("SELECT " + COLUMNS + " FROM evaluation_task WHERE user_id = #{userId} AND idempotency_key = #{key}")
    EvaluationTask findByKey(@Param("userId") Long userId, @Param("key") String key);

    @Select("SELECT " + COLUMNS + " FROM evaluation_task WHERE status = 'PENDING' ORDER BY id LIMIT 1")
    EvaluationTask findNextPending();

    @Update("""
            UPDATE evaluation_task SET status = 'PROCESSING', updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{taskId} AND status = 'PENDING'
            """)
    int claim(@Param("taskId") Long taskId);

    @Update("""
            UPDATE evaluation_task
            SET status = #{status}, error_message = #{error}, updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{taskId} AND status = 'PROCESSING'
            """)
    int finish(@Param("taskId") Long taskId, @Param("status") EvaluationTaskStatus status,
               @Param("error") String error);

    // Phase one runs exactly ONE application instance. Never run this recovery in a cluster.
    @Update("""
            UPDATE evaluation_task
            SET status = 'PENDING', retry_count = retry_count + 1,
                error_message = NULL, updated_at = CURRENT_TIMESTAMP(6)
            WHERE status = 'PROCESSING'
            """)
    int recoverInterrupted();
}
