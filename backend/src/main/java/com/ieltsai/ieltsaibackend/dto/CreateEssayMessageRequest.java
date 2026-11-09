package com.ieltsai.ieltsaibackend.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateEssayMessageRequest(
        @NotBlank(message = "content is required") String content
) {
}
