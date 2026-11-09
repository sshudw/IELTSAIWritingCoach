package com.ieltsai.ieltsaibackend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

@Service
public class AiChatService {

    private static final int MAX_CONTENT_BYTES = 60_000;
    private static final int MAX_MODEL_LENGTH = 100;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final Duration requestTimeout;

    public AiChatService(
            ObjectMapper objectMapper,
            @Value("${ai.api-url:}") String apiUrl,
            @Value("${ai.api-key:}") String apiKey,
            @Value("${ai.model:}") String model,
            @Value("${ai.request-timeout-seconds:60}") long requestTimeoutSeconds
    ) {
        this.objectMapper = objectMapper;
        this.apiUrl = apiUrl == null ? "" : apiUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.requestTimeout = Duration.ofSeconds(Math.max(1, requestTimeoutSeconds));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(20, Math.max(1, requestTimeoutSeconds))))
                .build();
    }

    public ChatReply chat(List<ChatMessage> messages) {
        if (apiUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
            throw new AiChatException("AI chat is not configured", false);
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(apiUrl))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(createRequestBody(messages)))
                    .build();
        } catch (IllegalArgumentException exception) {
            throw new AiChatException("AI request configuration is invalid", false);
        }

        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // Provider response bodies can contain sensitive data; never expose them.
                throw new AiChatException("AI API returned HTTP " + response.statusCode(), false);
            }
            return parseResponse(response.body());
        } catch (HttpTimeoutException exception) {
            throw new AiChatException("AI chat timed out", true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiChatException("AI chat was interrupted", false);
        } catch (IOException exception) {
            throw new AiChatException("Unable to call AI API", false);
        }
    }

    private String createRequestBody(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new AiChatException("AI chat messages are empty", false);
        }
        var body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        body.putObject("thinking").put("type", "disabled");
        var requestMessages = body.putArray("messages");
        for (ChatMessage message : messages) {
            if (message == null || message.content() == null || message.content().isBlank()
                    || !("system".equals(message.role()) || "user".equals(message.role())
                    || "assistant".equals(message.role()))) {
                throw new AiChatException("AI chat message is invalid", false);
            }
            requestMessages.addObject()
                    .put("role", message.role())
                    .put("content", message.content());
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JacksonException exception) {
            throw new AiChatException("Unable to create AI request", false);
        }
    }

    private ChatReply parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root == null || !root.isObject()) {
                throw new AiChatException("AI response is empty or invalid", false);
            }
            JsonNode choice = root.path("choices").path(0);
            JsonNode finishReason = choice.path("finish_reason");
            if (!finishReason.isMissingNode() && !finishReason.isNull()
                    && !"stop".equals(finishReason.asText())) {
                throw new AiChatException("AI response did not finish normally", false);
            }
            JsonNode contentNode = choice.path("message").path("content");
            if (!contentNode.isString() || contentNode.asText().isBlank()) {
                throw new AiChatException("AI response does not contain chat content", false);
            }
            String content = contentNode.asText().trim();
            if (content.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
                throw new AiChatException("AI chat response is too long", false);
            }
            JsonNode modelNode = root.path("model");
            String actualModel = modelNode.isString() ? modelNode.asText().trim() : "";
            if (actualModel.isBlank()) {
                actualModel = model;
            }
            if (actualModel.length() > MAX_MODEL_LENGTH) {
                throw new AiChatException("AI model name is too long", false);
            }
            return new ChatReply(content, actualModel);
        } catch (JacksonException exception) {
            // Do not attach parser exceptions containing excerpts of the provider body.
            throw new AiChatException("AI response is not valid JSON", false);
        }
    }

    public record ChatMessage(String role, String content) {
    }

    public record ChatReply(String content, String aiModel) {
    }

    public static class AiChatException extends RuntimeException {

        private final boolean timeout;

        public AiChatException(String message, boolean timeout) {
            super(message);
            this.timeout = timeout;
        }

        public boolean isTimeout() {
            return timeout;
        }
    }
}
