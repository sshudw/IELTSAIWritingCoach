package com.ieltsai.ieltsaibackend.controller;

import com.ieltsai.ieltsaibackend.dto.ApiErrorResponse;
import com.ieltsai.ieltsaibackend.dto.CreateEssayMessageRequest;
import com.ieltsai.ieltsaibackend.entity.EssayMessage;
import com.ieltsai.ieltsaibackend.service.EssayMessageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/essays")
public class EssayMessageController {

    private static final int MAX_CONTENT_BYTES = 60_000;

    private final EssayMessageService essayMessageService;

    public EssayMessageController(EssayMessageService essayMessageService) {
        this.essayMessageService = essayMessageService;
    }

    @GetMapping("/{essayId}/messages")
    public ResponseEntity<?> getMessages(
            @PathVariable Long essayId,
            HttpServletRequest request
    ) {
        Long currentUserId = getCurrentUserId(request);

        List<EssayMessage> messages = essayMessageService.getMessages(currentUserId, essayId);
        if (messages == null) {
            return ResponseEntity.status(404).body(ApiErrorResponse.of(404, "Essay not found"));
        }

        return ResponseEntity.ok(messages);
    }

    @PostMapping("/{essayId}/messages")
    public ResponseEntity<?> createUserMessage(
            @PathVariable Long essayId,
            @Valid @RequestBody CreateEssayMessageRequest messageRequest,
            HttpServletRequest request
    ) {
        Long currentUserId = getCurrentUserId(request);
        if (messageRequest.content().getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of(400, "content is too long"));
        }

        EssayMessageService.ChatResult result = essayMessageService.createUserMessage(
                currentUserId,
                essayId,
                messageRequest.content()
        );
        if (result == null) {
            return ResponseEntity.status(404).body(ApiErrorResponse.of(404, "Essay not found"));
        }

        if (result.status() != 201) {
            return ResponseEntity.status(result.status()).body(
                    ApiErrorResponse.chat(result.status(), result.message(), result.userMessage().getId())
            );
        }
        return ResponseEntity.status(201).body(new ChatResponse(
                result.userMessage(), result.assistantMessage(), result.aiModel()
        ));
    }

    private Long getCurrentUserId(HttpServletRequest request) {
        return (Long) request.getSession(false).getAttribute("AUTH_USER_ID");
    }

    public record ChatResponse(EssayMessage userMessage, EssayMessage assistantMessage, String aiModel) {
    }

}
