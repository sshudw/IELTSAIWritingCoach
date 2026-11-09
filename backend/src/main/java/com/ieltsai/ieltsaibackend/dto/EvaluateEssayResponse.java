package com.ieltsai.ieltsaibackend.dto;

import com.ieltsai.ieltsaibackend.entity.EvaluationStatus;

import java.math.BigDecimal;

public record EvaluateEssayResponse(
        Long essayId,
        Integer wordCount,
        EvaluationStatus evaluationStatus,
        BigDecimal overallScore,
        BigDecimal taskScore,
        BigDecimal coherenceScore,
        BigDecimal lexicalScore,
        BigDecimal grammarScore,
        String aiFeedback,
        String aiModel
) {
}
