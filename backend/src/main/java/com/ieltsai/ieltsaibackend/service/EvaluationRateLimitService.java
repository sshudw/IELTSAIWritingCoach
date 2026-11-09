package com.ieltsai.ieltsaibackend.service;

import com.ieltsai.ieltsaibackend.config.EvaluationRedisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;

@Service
public class EvaluationRateLimitService {
    private static final Logger log = LoggerFactory.getLogger(EvaluationRateLimitService.class);
    private static final String KEY_PREFIX = "ielts:ai:evaluation:rate:user:";
    private static final Duration COUNTER_TTL = Duration.ofMinutes(2);

    private final StringRedisTemplate redisTemplate;
    private final EvaluationRedisProperties properties;
    private final Clock clock;

    public EvaluationRateLimitService(StringRedisTemplate redisTemplate,
                                      EvaluationRedisProperties properties,
                                      Clock evaluationRateLimitClock) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clock = evaluationRateLimitClock;
    }

    public boolean tryAcquire(Long userId) {
        String key = rateKey(userId);
        try {
            Boolean created = redisTemplate.opsForValue().setIfAbsent(key, "1", COUNTER_TTL);
            if (created == null) {
                log.warn("event=redis_rate_limit_unavailable userId={} reason=null_result", userId);
                return true;
            }

            long count;
            if (created) {
                count = 1L;
            } else {
                Long incremented = redisTemplate.opsForValue().increment(key);
                if (incremented == null) {
                    log.warn("event=redis_rate_limit_unavailable userId={} reason=null_counter", userId);
                    return true;
                }
                count = incremented;
            }

            if (count > properties.maxRequestsPerMinute()) {
                log.warn("event=ai_evaluation_rate_limited userId={} count={} limit={}",
                        userId, count, properties.maxRequestsPerMinute());
                return false;
            }
            return true;
        } catch (DataAccessException exception) {
            log.warn("event=redis_rate_limit_unavailable userId={} errorType={}",
                    userId, exception.getClass().getSimpleName());
            return true;
        }
    }

    private String rateKey(Long userId) {
        long epochMinute = Math.floorDiv(clock.instant().getEpochSecond(), 60L);
        return KEY_PREFIX + userId + ":minute:" + epochMinute;
    }
}
