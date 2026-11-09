package com.ieltsai.ieltsaibackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties(prefix = "evaluation.redis")
public record EvaluationRedisProperties(
        int maxRequestsPerMinute,
        Duration dedupTtl
) {
    public EvaluationRedisProperties {
        if (maxRequestsPerMinute < 1) {
            throw new IllegalArgumentException("evaluation.redis.max-requests-per-minute must be at least 1");
        }
        Objects.requireNonNull(dedupTtl, "evaluation.redis.dedup-ttl is required");
        if (dedupTtl.isZero() || dedupTtl.isNegative()) {
            throw new IllegalArgumentException("evaluation.redis.dedup-ttl must be positive");
        }
    }
}
