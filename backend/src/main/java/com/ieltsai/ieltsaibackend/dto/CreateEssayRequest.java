package com.ieltsai.ieltsaibackend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateEssayRequest(
        @NotBlank(message = "question is required") String question,
        @NotBlank(message = "content is required") String content,
        @NotBlank(message = "essayType is required") String essayType
) {
}
