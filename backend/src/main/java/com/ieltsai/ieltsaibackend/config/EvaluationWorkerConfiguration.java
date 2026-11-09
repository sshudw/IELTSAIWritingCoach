package com.ieltsai.ieltsaibackend.config;

import com.ieltsai.ieltsaibackend.mapper.EvaluationTaskMapper;
import com.ieltsai.ieltsaibackend.service.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "evaluation.worker.enabled", havingValue = "true", matchIfMissing = true)
public class EvaluationWorkerConfiguration {
    @Bean
    EvaluationTaskWorker evaluationTaskWorker(EvaluationTaskMapper mapper,
            EvaluationTaskPersistenceService persistence, EssayService essays, AiEvaluationService ai) {
        return new EvaluationTaskWorker(mapper, persistence, essays, ai);
    }
}
