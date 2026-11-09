package com.ieltsai.ieltsaibackend.service;

import com.ieltsai.ieltsaibackend.dto.AiEvaluationResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiEvaluationServiceHttpTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsOpenAiCompatiblePostAndParsesStructuredEvaluationAndActualModel()
            throws Exception {
        AtomicReference<String> requestMethod = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        startServer(openAiResponse(
                "actual-evaluator-model-2026-09",
                """
                        {
                          "task_score": 6.5,
                          "coherence_score": 7.0,
                          "lexical_score": 6.0,
                          "grammar_score": 5.5,
                          "ai_feedback": "The position is clear. Develop the second example and correct article usage."
                        }
                        """
        ), exchange -> {
            requestMethod.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
        });

        AiEvaluationResult result = service().evaluate(
                "Discuss whether public transport should be free.",
                "Public transport benefits citizens and reduces congestion.",
                "Task 2"
        );

        assertEquals("POST", requestMethod.get());
        assertEquals("Bearer test-api-key", authorization.get());
        assertEquals("application/json", contentType.get());

        JsonNode sentJson = objectMapper.readTree(requestBody.get());
        assertEquals("configured-model", sentJson.path("model").asText());
        assertEquals("json_object", sentJson.path("response_format").path("type").asText());
        assertEquals("system", sentJson.path("messages").path(0).path("role").asText());
        assertEquals("user", sentJson.path("messages").path(1).path("role").asText());
        String userPrompt = sentJson.path("messages").path(1).path("content").asText();
        assertTrue(userPrompt.contains("Task 2"));
        assertTrue(userPrompt.contains("Discuss whether public transport should be free."));
        assertTrue(userPrompt.contains(
                "Public transport benefits citizens and reduces congestion."
        ));

        assertEquals(decimal("6.5"), result.taskScore());
        assertEquals(decimal("7.0"), result.coherenceScore());
        assertEquals(decimal("6.0"), result.lexicalScore());
        assertEquals(decimal("5.5"), result.grammarScore());
        assertEquals(
                "The position is clear. Develop the second example and correct article usage.",
                result.aiFeedback()
        );
        assertEquals("actual-evaluator-model-2026-09", result.aiModel());
    }

    @Test
    void rejectsScoreThatIsNotInHalfBandIncrements() throws Exception {
        startServer(openAiResponse(
                "actual-model",
                """
                        {
                          "task_score": 6.5,
                          "coherence_score": 6.3,
                          "lexical_score": 6.0,
                          "grammar_score": 6.0,
                          "ai_feedback": "Detailed feedback"
                        }
                        """
        ), exchange -> {
                });

        AiEvaluationService.AiEvaluationException exception = assertThrows(
                AiEvaluationService.AiEvaluationException.class,
                () -> service().evaluate("Question", "Essay", "Task 2")
        );

        assertEquals("Invalid AI score: coherence_score", exception.getMessage());
        assertFalse(exception.isTimeout());
    }

    @Test
    void rejectsResponseWithoutEvaluationContent() throws Exception {
        startServer("""
                {
                  "model": "actual-model",
                  "choices": []
                }
                """, exchange -> {
                });

        AiEvaluationService.AiEvaluationException exception = assertThrows(
                AiEvaluationService.AiEvaluationException.class,
                () -> service().evaluate("Question", "Essay", "Task 2")
        );

        assertEquals(
                "AI response does not contain evaluation content",
                exception.getMessage()
        );
        assertFalse(exception.isTimeout());
    }

    private AiEvaluationService service() {
        return new AiEvaluationService(
                objectMapper,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                "test-api-key",
                "configured-model",
                "Chinese",
                5
        );
    }

    private String openAiResponse(String actualModel, String evaluationJson) {
        var root = objectMapper.createObjectNode();
        root.put("model", actualModel);
        root.putArray("choices")
                .addObject()
                .putObject("message")
                .put("content", evaluationJson);
        return objectMapper.writeValueAsString(root);
    }

    private void startServer(String responseBody, RequestInspector requestInspector)
            throws IOException {
        byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                requestInspector.inspect(exchange);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, responseBytes.length);
                exchange.getResponseBody().write(responseBytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    @FunctionalInterface
    private interface RequestInspector {
        void inspect(HttpExchange exchange) throws IOException;
    }
}
