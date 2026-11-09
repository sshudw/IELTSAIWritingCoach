package com.ieltsai.ieltsaibackend.controller;

import com.ieltsai.ieltsaibackend.dto.ApiErrorResponse;
import com.ieltsai.ieltsaibackend.entity.User;
import com.ieltsai.ieltsaibackend.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping(value = "/captcha", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getCaptcha(HttpSession session) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(authService.createCaptcha(session));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest loginRequest,
            HttpSession session,
            HttpServletRequest request
    ) {
        AuthService.LoginResult result = authService.login(
                loginRequest.username(),
                loginRequest.password(),
                loginRequest.captcha(),
                session
        );

        if (result.status() == AuthService.LoginStatus.CAPTCHA_EXPIRED) {
            return ResponseEntity.badRequest()
                    .body(ApiErrorResponse.of(400, "Captcha is missing or expired"));
        }
        if (result.status() == AuthService.LoginStatus.INVALID_CAPTCHA) {
            return ResponseEntity.badRequest()
                    .body(ApiErrorResponse.of(400, "Invalid captcha"));
        }
        if (result.status() == AuthService.LoginStatus.INVALID_CREDENTIALS) {
            return ResponseEntity.status(401)
                    .body(ApiErrorResponse.of(401, "Invalid username or password"));
        }

        request.changeSessionId();
        return ResponseEntity.ok(new MessageResponse("Login successful"));
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(HttpSession session) {
        User user = authService.getCurrentUser(session);
        if (user == null) {
            return ResponseEntity.status(401)
                    .body(ApiErrorResponse.of(401, "Not logged in"));
        }

        return ResponseEntity.ok(new CurrentUserResponse(
                user.getUsername(),
                user.getEmail()
        ));
    }

    public record LoginRequest(
            @NotBlank(message = "username is required") String username,
            @NotBlank(message = "password is required") String password,
            @NotBlank(message = "captcha is required") String captcha
    ) {
    }

    public record CurrentUserResponse(
            String username,
            String email
    ) {
    }

    public record MessageResponse(String message) {
    }
}
