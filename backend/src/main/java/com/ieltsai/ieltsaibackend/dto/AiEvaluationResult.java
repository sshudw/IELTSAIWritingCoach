package com.ieltsai.ieltsaibackend.dto;

import java.math.BigDecimal;

public record AiEvaluationResult(
        BigDecimal taskScore,
        BigDecimal coherenceScore,
        BigDecimal lexicalScore,
        BigDecimal grammarScore,
        String aiFeedback,
        String aiModel
) {
}
