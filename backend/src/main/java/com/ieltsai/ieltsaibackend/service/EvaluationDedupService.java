package com.ieltsai.ieltsaibackend.service;

import com.ieltsai.ieltsaibackend.config.EvaluationRedisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class EvaluationDedupService {
    private static final Logger log = LoggerFactory.getLogger(EvaluationDedupService.class);
    private static final String KEY_PREFIX = "ielts:ai:evaluation:dedup:user:";

    private final StringRedisTemplate redisTemplate;
    private final EvaluationRedisProperties properties;

    public EvaluationDedupService(StringRedisTemplate redisTemplate, EvaluationRedisProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public boolean isDuplicate(Long userId, String idempotencyKey) {
        try {
            Boolean created = redisTemplate.opsForValue().setIfAbsent(
                    dedupKey(userId, idempotencyKey), "1", properties.dedupTtl());
            if (created == null) {
                log.warn("event=redis_evaluation_dedup_unavailable operation=mark userId={} reason=null_result",
                        userId);
                return false;
            }
            return !created;
        } catch (DataAccessException exception) {
            log.warn("event=redis_evaluation_dedup_unavailable operation=mark userId={} errorType={}",
                    userId, exception.getClass().getSimpleName());
            return false;
        }
    }

    private String dedupKey(Long userId, String idempotencyKey) {
        return KEY_PREFIX + userId + ":key:" + idempotencyKey;
    }
}
