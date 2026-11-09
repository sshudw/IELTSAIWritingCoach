package com.ieltsai.ieltsaibackend.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterUserRequest(
        @NotBlank(message = "username is required") String username,
        @NotBlank(message = "email is required") String email,
        @NotBlank(message = "password is required") String password
) {
}
