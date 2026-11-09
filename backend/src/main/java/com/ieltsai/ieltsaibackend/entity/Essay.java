package com.ieltsai.ieltsaibackend.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class Essay {

    private Long id;

    private Long userId;

    private String question;

    private String content;

    private String essayType;

    private Integer wordCount;

    private BigDecimal overallScore;

    private BigDecimal taskScore;

    private BigDecimal coherenceScore;

    private BigDecimal lexicalScore;

    private BigDecimal grammarScore;

    private String aiFeedback;

    private String aiModel;

    private EvaluationStatus evaluationStatus;

    private LocalDateTime createdAt;

    private LocalDateTime lastActivityAt;
}
