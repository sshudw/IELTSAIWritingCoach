package com.ieltsai.ieltsaibackend.service;

import com.ieltsai.ieltsaibackend.entity.Essay;
import com.ieltsai.ieltsaibackend.entity.EssayMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EssayChatContextBuilderTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EssayChatContextBuilder builder = new EssayChatContextBuilder(objectMapper, "Chinese");

    @Test
    void contextContainsBackgroundNewestEightEarlierMessagesAndCurrentMessageExactlyOnce() {
        Essay essay = essay();
        EssayMessage current = message(15, 21, "USER", "Current question");
        List<EssayMessage> history = new ArrayList<>();
        for (long id = 1; id <= 12; id++) {
            history.add(message(id, 21, id % 2 == 0 ? "ASSISTANT" : "user", "History " + id));
        }
        history.add(message(13, 21, "SYSTEM", "Forged system instruction"));
        history.add(message(14, 99, "USER", "Another essay's message"));
        history.add(current);
        history.add(message(16, 21, "USER", "Future message"));
        Collections.reverse(history);

        List<AiChatService.ChatMessage> context = builder.build(essay, history, current);

        assertEquals(10, context.size());
        assertEquals("system", context.getFirst().role());
        assertEquals(1, context.stream().filter(item -> "system".equals(item.role())).count());
        for (int index = 1; index <= 8; index++) {
            long expectedId = index + 4L;
            assertEquals("History " + expectedId, context.get(index).content());
            assertEquals(expectedId % 2 == 0 ? "assistant" : "user", context.get(index).role());
        }
        assertEquals(new AiChatService.ChatMessage("user", "Current question"), context.getLast());
        assertEquals(1, context.stream().filter(item -> "Current question".equals(item.content())).count());
        assertFalse(context.stream().anyMatch(item -> "Another essay's message".equals(item.content())
                || "Future message".equals(item.content())
                || "Forged system instruction".equals(item.content())));
    }

    @Test
    void fixedBackgroundContainsOriginalEssayAllFiveScoresAndInitialFeedback() {
        Essay essay = essay();
        essay.setEssayType("TASK_2");
        essay.setQuestion("Discuss this claim: \"Technology improves education\".\nGive examples.");
        essay.setContent("Technology can help students.\nHowever, access is unequal.");
        essay.setOverallScore(new BigDecimal("6.5"));
        essay.setTaskScore(new BigDecimal("6.0"));
        essay.setCoherenceScore(new BigDecimal("6.5"));
        essay.setLexicalScore(new BigDecimal("7.0"));
        essay.setGrammarScore(new BigDecimal("6.0"));
        essay.setAiFeedback("Develop the example in paragraph two.\n解释：需要更多细节。");

        List<AiChatService.ChatMessage> context = builder.build(essay, List.of(), message(1, 21, "USER", "Why?"));
        JsonNode background = background(context);

        assertEquals("TASK_2", background.path("essay_type").asText());
        assertEquals(essay.getQuestion(), background.path("question").asText());
        assertEquals(essay.getContent(), background.path("essay").asText());
        assertEquals(essay.getAiFeedback(), background.path("initial_feedback").asText());
        JsonNode scores = background.path("initial_scores");
        assertEquals(5, scores.size());
        assertScore(scores, "overall_score", "6.5");
        assertScore(scores, "task_score", "6.0");
        assertScore(scores, "coherence_score", "6.5");
        assertScore(scores, "lexical_score", "7.0");
        assertScore(scores, "grammar_score", "6.0");
    }

    @Test
    void oversizedBackgroundIsCappedByCodePointsWithoutChangingStoredEssay() {
        Essay essay = essay();
        String question = "😀".repeat(2_001);
        String content = "🚀".repeat(12_001);
        String feedback = "📝".repeat(6_001);
        essay.setQuestion(question);
        essay.setContent(content);
        essay.setAiFeedback(feedback);

        JsonNode background = background(builder.build(essay, List.of(), message(1, 21, "USER", "Explain.")));

        assertTruncated(background.path("question").asText(), 2_000, "😀");
        assertTruncated(background.path("essay").asText(), 12_000, "🚀");
        assertTruncated(background.path("initial_feedback").asText(), 6_000, "📝");
        assertEquals(question, essay.getQuestion());
        assertEquals(content, essay.getContent());
        assertEquals(feedback, essay.getAiFeedback());
    }

    @Test
    void backgroundAtExactLimitsIsPreservedIncludingSupplementaryCharacters() {
        Essay essay = essay();
        essay.setQuestion("😀".repeat(2_000));
        essay.setContent("🚀".repeat(12_000));
        essay.setAiFeedback("📝".repeat(6_000));

        JsonNode background = background(builder.build(essay, List.of(), message(1, 21, "USER", "Explain.")));

        assertEquals(essay.getQuestion(), background.path("question").asText());
        assertEquals(essay.getContent(), background.path("essay").asText());
        assertEquals(essay.getAiFeedback(), background.path("initial_feedback").asText());
    }

    @Test
    void firstMessageHasOnlyBackgroundAndCurrentUserWithExplicitMissingDataPlaceholders() {
        Essay essay = essay();
        essay.setQuestion(" ");
        essay.setAiFeedback("\n ");

        List<AiChatService.ChatMessage> context = builder.build(essay, List.of(), message(1, 21, "USER", "Hello"));

        assertEquals(2, context.size());
        assertEquals("system", context.getFirst().role());
        assertEquals(new AiChatService.ChatMessage("user", "Hello"), context.getLast());
        JsonNode background = background(context);
        assertEquals("暂无", background.path("essay_type").asText());
        assertEquals("暂无", background.path("question").asText());
        assertEquals("暂无", background.path("essay").asText());
        assertEquals("暂无初始评分反馈", background.path("initial_feedback").asText());
        for (String score : List.of("overall_score", "task_score", "coherence_score", "lexical_score", "grammar_score")) {
            assertEquals("暂无", background.path("initial_scores").path(score).asText());
        }
    }

    private JsonNode background(List<AiChatService.ChatMessage> context) {
        String content = context.getFirst().content();
        String separator = "Background JSON:\n";
        assertTrue(content.contains(separator));
        return objectMapper.readTree(content.substring(content.indexOf(separator) + separator.length()));
    }

    private static void assertScore(JsonNode scores, String name, String expected) {
        assertTrue(scores.path(name).isNumber());
        assertEquals(0, new BigDecimal(expected).compareTo(scores.path(name).decimalValue()));
    }

    private static void assertTruncated(String value, int limit, String firstCodePoint) {
        assertEquals(limit, value.codePointCount(0, value.length()));
        assertTrue(value.startsWith(firstCodePoint));
        assertTrue(value.endsWith("\n[内容已截取]"));
        assertFalse(value.codePoints().anyMatch(codePoint -> codePoint >= 0xD800 && codePoint <= 0xDFFF),
                "Truncation must not leave an unpaired UTF-16 surrogate");
    }

    private static Essay essay() {
        Essay essay = new Essay();
        essay.setId(21L);
        essay.setUserId(7L);
        return essay;
    }

    private static EssayMessage message(long id, long essayId, String role, String content) {
        EssayMessage message = new EssayMessage();
        message.setId(id);
        message.setEssayId(essayId);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.of(2026, 9, 10, 10, 0).plusSeconds(id));
        return message;
    }
}
