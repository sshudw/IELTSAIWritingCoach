package com.ieltsai.ieltsaibackend.service;

import com.ieltsai.ieltsaibackend.config.EvaluationRedisProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvaluationRedisServicesTests {
    private static final Duration DEDUP_TTL = Duration.ofSeconds(30);
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private EvaluationRedisProperties properties;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        properties = new EvaluationRedisProperties(2, DEDUP_TTL);
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void fixedWindowAllowsConfiguredLimitAndRejectsNextRequest() {
        when(values.setIfAbsent(anyString(), eq("1"), eq(Duration.ofMinutes(2))))
                .thenReturn(true, false, false);
        when(values.increment(anyString())).thenReturn(2L, 3L);
        EvaluationRateLimitService service = rateLimit();

        assertTrue(service.tryAcquire(7L));
        assertTrue(service.tryAcquire(7L));
        assertFalse(service.tryAcquire(7L));

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(values, times(3)).setIfAbsent(keys.capture(), eq("1"), eq(Duration.ofMinutes(2)));
        assertEquals(1, keys.getAllValues().stream().distinct().count());
    }

    @Test
    void rateLimitUsesSeparateKeysForUsers() {
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        EvaluationRateLimitService service = rateLimit();

        assertTrue(service.tryAcquire(7L));
        assertTrue(service.tryAcquire(8L));

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(values, times(2)).setIfAbsent(keys.capture(), eq("1"), eq(Duration.ofMinutes(2)));
        assertNotEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
    }

    @Test
    void rateLimitFailsOpenWhenRedisIsUnavailable() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertTrue(rateLimit().tryAcquire(7L));
    }

    @Test
    void dedupAtomicallyMarksFirstRequestAndDetectsTheNext() {
        EvaluationDedupService service = new EvaluationDedupService(redis, properties);
        String key = "ielts:ai:evaluation:dedup:user:7:key:request-1";
        when(values.setIfAbsent(key, "1", DEDUP_TTL)).thenReturn(true, false);

        assertFalse(service.isDuplicate(7L, "request-1"));
        assertTrue(service.isDuplicate(7L, "request-1"));
        verify(values, times(2)).setIfAbsent(key, "1", DEDUP_TTL);
    }

    @Test
    void dedupUsesSeparateKeysForUsers() {
        EvaluationDedupService service = new EvaluationDedupService(redis, properties);
        when(values.setIfAbsent(anyString(), eq("1"), eq(DEDUP_TTL))).thenReturn(true);
        service.isDuplicate(7L, "same");
        service.isDuplicate(8L, "same");

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(values, times(2)).setIfAbsent(keys.capture(), eq("1"), eq(DEDUP_TTL));
        assertNotEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
    }

    @Test
    void dedupFailsOpenWhenRedisIsUnavailable() {
        EvaluationDedupService service = new EvaluationDedupService(redis, properties);
        RedisConnectionFailureException outage = new RedisConnectionFailureException("down");
        when(values.setIfAbsent(anyString(), anyString(), eq(DEDUP_TTL))).thenThrow(outage);

        assertFalse(service.isDuplicate(7L, "request-1"));
    }

    private EvaluationRateLimitService rateLimit() {
        Clock fixed = Clock.fixed(Instant.parse("2026-10-05T04:12:30Z"), ZoneOffset.UTC);
        return new EvaluationRateLimitService(redis, properties, fixed);
    }
}
