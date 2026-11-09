package com.ieltsai.ieltsaibackend.mapper;

import com.ieltsai.ieltsaibackend.entity.Essay;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.time.LocalDateTime;

@Mapper
public interface EssayMapper {

    @Insert("""
        INSERT INTO essay(user_id, question, content, essay_type, word_count, evaluation_status)
        VALUES(#{userId}, #{question}, #{content}, #{essayType}, #{wordCount}, #{evaluationStatus})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insert(Essay essay);

    @Update("""
        UPDATE essay
        SET overall_score = #{overallScore},
            task_score = #{taskScore},
            coherence_score = #{coherenceScore},
            lexical_score = #{lexicalScore},
            grammar_score = #{grammarScore},
            ai_feedback = #{aiFeedback},
            ai_model = #{aiModel},
            evaluation_status = #{evaluationStatus}
        WHERE id = #{id} AND user_id = #{userId}
        """)
    int updateEvaluation(Essay essay);

    @Update("""
        UPDATE essay
        SET overall_score = NULL,
            task_score = NULL,
            coherence_score = NULL,
            lexical_score = NULL,
            grammar_score = NULL,
            ai_feedback = NULL,
            ai_model = #{aiModel},
            evaluation_status = 'FAILED'
        WHERE id = #{id} AND user_id = #{userId}
        """)
    int markEvaluationFailed(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("aiModel") String aiModel
    );

    @Select("""
        SELECT id, user_id AS userId, question, content, essay_type AS essayType,
               word_count AS wordCount, overall_score AS overallScore,
               task_score AS taskScore, coherence_score AS coherenceScore,
               lexical_score AS lexicalScore, grammar_score AS grammarScore,
               ai_feedback AS aiFeedback, ai_model AS aiModel,
               evaluation_status AS evaluationStatus, created_at AS createdAt,
               last_activity_at AS lastActivityAt
        FROM essay
        WHERE id = #{id} AND user_id = #{userId}
        """)
    Essay findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Select("""
        SELECT id, user_id AS userId, question, content, essay_type AS essayType,
               word_count AS wordCount, overall_score AS overallScore,
               task_score AS taskScore, coherence_score AS coherenceScore,
               lexical_score AS lexicalScore, grammar_score AS grammarScore,
               ai_feedback AS aiFeedback, ai_model AS aiModel,
               evaluation_status AS evaluationStatus, created_at AS createdAt,
               last_activity_at AS lastActivityAt
        FROM essay
        WHERE user_id = #{userId}
        ORDER BY id
        """)
    List<Essay> findByUserId(@Param("userId") Long userId);

    @Select("""
        SELECT COUNT(*)
        FROM essay
        WHERE user_id = #{userId}
        """)
    long countByUserId(@Param("userId") Long userId);

    @Select("""
        SELECT id, user_id AS userId, question, essay_type AS essayType,
               word_count AS wordCount, overall_score AS overallScore,
               evaluation_status AS evaluationStatus,
               created_at AS createdAt, last_activity_at AS lastActivityAt
        FROM essay
        WHERE user_id = #{userId}
        ORDER BY last_activity_at DESC, id DESC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<Essay> findPageByUserId(
            @Param("userId") Long userId,
            @Param("limit") int limit,
            @Param("offset") long offset
    );

    @Update("""
        UPDATE essay
        SET last_activity_at = GREATEST(last_activity_at, #{lastActivityAt})
        WHERE id = #{id} AND user_id = #{userId}
        """)
    int updateLastActivity(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("lastActivityAt") LocalDateTime lastActivityAt
    );

    @Delete("""
        DELETE FROM essay
        WHERE id = #{id} AND user_id = #{userId}
        """)
    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}
