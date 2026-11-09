package com.ieltsai.ieltsaibackend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EvaluationRedisProperties.class)
public class EvaluationRedisConfiguration {
    @Bean
    Clock evaluationRateLimitClock() {
        return Clock.systemUTC();
    }
}
