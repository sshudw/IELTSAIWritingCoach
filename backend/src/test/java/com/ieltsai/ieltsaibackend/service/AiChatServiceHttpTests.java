package com.ieltsai.ieltsaibackend.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChatServiceHttpTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsOrderedPlainTextChatWithBearerAuthenticationAndReadsActualModel() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(200, response("actual-deepseek-model", "第二段需要一个具体例子。", "stop"), exchange -> {
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        });
        List<AiChatService.ChatMessage> messages = List.of(
                new AiChatService.ChatMessage("system", "Essay background"),
                new AiChatService.ChatMessage("user", "Explain paragraph two."),
                new AiChatService.ChatMessage("assistant", "It needs supporting details."),
                new AiChatService.ChatMessage("user", "请举一个例子。")
        );

        AiChatService.ChatReply reply = service(5).chat(messages);

        assertEquals("POST", method.get());
        assertEquals("Bearer test-api-key", authorization.get());
        assertEquals("application/json", contentType.get());
        JsonNode body = objectMapper.readTree(requestBody.get());
        assertEquals("deepseek-v4-flash", body.path("model").asText());
        assertFalse(body.path("stream").asBoolean());
        assertEquals("disabled", body.path("thinking").path("type").asText());
        assertFalse(body.has("response_format"));
        assertEquals(messages.size(), body.path("messages").size());
        for (int i = 0; i < messages.size(); i++) {
            assertEquals(messages.get(i).role(), body.path("messages").path(i).path("role").asText());
            assertEquals(messages.get(i).content(), body.path("messages").path(i).path("content").asText());
        }
        assertEquals("第二段需要一个具体例子。", reply.content());
        assertEquals("actual-deepseek-model", reply.aiModel());
    }

    @Test
    void usesConfiguredModelWhenProviderOmitsModel() throws Exception {
        startServer(200, """
                {"choices":[{"message":{"content":"A plain text answer."}}]}
                """, exchange -> {});

        AiChatService.ChatReply reply = service(5).chat(messages());

        assertEquals("A plain text answer.", reply.content());
        assertEquals("deepseek-v4-flash", reply.aiModel());
    }

    @Test
    void rejectsNonSuccessfulHttpWithoutExposingProviderBody() throws Exception {
        startServer(402, "provider-secret test-api-key", exchange -> {});

        var exception = assertThrows(AiChatService.AiChatException.class, () -> service(5).chat(messages()));

        assertEquals("AI API returned HTTP 402", exception.getMessage());
        assertFalse(exception.isTimeout());
        assertNull(exception.getCause());
    }

    @Test
    void rejectsMalformedJsonWithoutExposingProviderBody() throws Exception {
        startServer(200, "provider-secret: not JSON", exchange -> {});

        var exception = assertThrows(AiChatService.AiChatException.class, () -> service(5).chat(messages()));

        assertEquals("AI response is not valid JSON", exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void rejectsEmptyResponseContent() throws Exception {
        startServer(200, response("actual-model", " \n ", "stop"), exchange -> {});

        var exception = assertThrows(AiChatService.AiChatException.class, () -> service(5).chat(messages()));

        assertEquals("AI response does not contain chat content", exception.getMessage());
        assertFalse(exception.isTimeout());
    }

    @Test
    void rejectsNonTextResponseContent() throws Exception {
        startServer(200, """
                {"choices":[{"message":{"content":123}}]}
                """, exchange -> {});

        var exception = assertThrows(AiChatService.AiChatException.class, () -> service(5).chat(messages()));

        assertEquals("AI response does not contain chat content", exception.getMessage());
    }

    @Test
    void rejectsTruncatedReplyInsteadOfSavingPartialAssistantMessage() throws Exception {
        startServer(200, response("actual-model", "A partial answer", "length"), exchange -> {});

        var exception = assertThrows(AiChatService.AiChatException.class, () -> service(5).chat(messages()));

        assertEquals("AI response did not finish normally", exception.getMessage());
    }

    @Test
    void rejectsReplyThatExceedsDatabaseTextLimitInUtf8Bytes() throws Exception {
        startServer(200, response("actual-model", "文".repeat(20_001), "stop"), exchange -> {});

        var exception = assertThrows(AiChatService.AiChatException.class, () -> service(5).chat(messages()));

        assertEquals("AI chat response is too long", exception.getMessage());
    }

    @Test
    void reportsTimeoutSeparately() throws Exception {
        CountDownLatch releaseResponse = new CountDownLatch(1);
        startServer(200, response("actual-model", "Late answer", "stop"), exchange -> {
            try {
                releaseResponse.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });

        try {
            var exception = assertThrows(AiChatService.AiChatException.class, () -> service(1).chat(messages()));
            assertTrue(exception.isTimeout());
            assertEquals("AI chat timed out", exception.getMessage());
        } finally {
            releaseResponse.countDown();
        }
    }

    @Test
    void rejectsMissingKeyBeforeSendingRequest() {
        var service = new AiChatService(objectMapper, "http://127.0.0.1:1/chat/completions", "", "model", 1);

        var exception = assertThrows(AiChatService.AiChatException.class, () -> service.chat(messages()));

        assertEquals("AI chat is not configured", exception.getMessage());
        assertFalse(exception.isTimeout());
    }

    private AiChatService service(long timeoutSeconds) {
        return new AiChatService(objectMapper,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions",
                "test-api-key", "deepseek-v4-flash", timeoutSeconds);
    }

    private List<AiChatService.ChatMessage> messages() {
        return List.of(new AiChatService.ChatMessage("user", "Explain this essay."));
    }

    private String response(String model, String content, String finishReason) {
        var body = objectMapper.createObjectNode();
        body.put("model", model);
        body.putArray("choices").addObject()
                .put("finish_reason", finishReason)
                .putObject("message")
                .put("role", "assistant")
                .put("content", content);
        return objectMapper.writeValueAsString(body);
    }

    private void startServer(int status, String responseBody, RequestInspector inspector) throws IOException {
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try {
                inspector.inspect(exchange);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @FunctionalInterface
    private interface RequestInspector {
        void inspect(HttpExchange exchange) throws IOException;
    }
}
