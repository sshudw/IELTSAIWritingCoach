package com.ieltsai.ieltsaibackend.service;

import com.ieltsai.ieltsaibackend.entity.Essay;
import com.ieltsai.ieltsaibackend.entity.EssayMessage;
import com.ieltsai.ieltsaibackend.entity.EvaluationStatus;
import com.ieltsai.ieltsaibackend.entity.User;
import com.ieltsai.ieltsaibackend.mapper.EssayMapper;
import com.ieltsai.ieltsaibackend.mapper.EssayMessageMapper;
import com.ieltsai.ieltsaibackend.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Opt in with -Dessay.chat.mysql-test=true against the configured MySQL database.
 * Only uniquely named fixtures created by this class are inserted and removed.
 * AI is mocked; the Spring proxies, transactions and MyBatis SQL are real.
 */
@SpringBootTest(properties = "evaluation.worker.enabled=false")
@EnabledIfSystemProperty(named = "essay.chat.mysql-test", matches = "true")
class EssayChatMySqlTests {

    @Autowired
    private EssayMessageService messageService;

    @Autowired
    private EssayService essayService;

    @Autowired
    private EssayMessagePersistenceService persistenceService;

    @Autowired
    private EssayMapper essayMapper;

    @Autowired
    private EssayMessageMapper messageMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private AiChatService aiChatService;

    private final List<Long> fixtureEssayIds = new ArrayList<>();
    private final List<Long> fixtureUserIds = new ArrayList<>();
    private Long fixtureUserId;
    private Essay fixtureEssay;

    @BeforeEach
    void createFixtures() {
        fixtureUserId = createUser();
        fixtureEssay = createEssay(fixtureUserId, "Discuss the benefits of reading.");
    }

    @AfterEach
    void removeOnlyFixtures() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (Long essayId : fixtureEssayIds) {
            jdbc.update("DELETE FROM essay_message WHERE essay_id = ?", essayId);
            jdbc.update("DELETE FROM essay WHERE id = ?", essayId);
        }
        for (Long userId : fixtureUserIds) {
            jdbc.update("DELETE FROM `user` WHERE id = ?", userId);
        }
    }

    @Test
    void insertUsesDatabaseDefaultAndSelectMapsLastActivity() {
        assertNull(fixtureEssay.getLastActivityAt(),
                "The insert relies on the database default rather than a Java-generated timestamp");

        Essay stored = essayMapper.findByIdAndUserId(fixtureEssay.getId(), fixtureUserId);

        assertNotNull(stored);
        assertNotNull(stored.getLastActivityAt());
    }

    @Test
    void updateLastActivityUsesBothEssayIdAndOwnerId() {
        LocalDateTime expected = LocalDateTime.now()
                .plusHours(1)
                .truncatedTo(ChronoUnit.MICROS);

        assertEquals(0, essayMapper.updateLastActivity(
                fixtureEssay.getId(), fixtureUserId + 1, expected.plusHours(1)
        ));
        assertEquals(1, essayMapper.updateLastActivity(fixtureEssay.getId(), fixtureUserId, expected));

        Essay stored = essayMapper.findByIdAndUserId(fixtureEssay.getId(), fixtureUserId);
        assertEquals(expected, stored.getLastActivityAt());
    }

    @Test
    void savingUserMessageMovesAnOldEssayToTheFrontOfThePage() throws Exception {
        Essay previouslyNewerEssay = createEssay(fixtureUserId, "Should public transport be free?");
        LocalDateTime oldActivity = LocalDateTime.now().minusDays(2).truncatedTo(ChronoUnit.MICROS);
        LocalDateTime newerActivity = LocalDateTime.now().minusHours(1).truncatedTo(ChronoUnit.MICROS);
        setLastActivity(fixtureEssay.getId(), oldActivity);
        setLastActivity(previouslyNewerEssay.getId(), newerActivity);

        assertEquals(previouslyNewerEssay.getId(),
                essayService.getMyEssays(fixtureUserId, 1, 10).essays().getFirst().id());

        EssayMessage saved = persistenceService.saveUserMessage(
                fixtureUserId, fixtureEssay.getId(), "Bring this conversation back to the top."
        );

        LocalDateTime updatedActivity = readLastActivity(fixtureEssay.getId());
        assertTrue(updatedActivity.isAfter(newerActivity));
        assertEquals(fixtureEssay.getId(),
                essayService.getMyEssays(fixtureUserId, 1, 10).essays().getFirst().id());
        assertEquals(List.of(new StoredMessage(
                saved.getId(), "USER", "Bring this conversation back to the top."
        )), readThroughFreshConnection(fixtureEssay.getId()));
    }

    @Test
    void unauthorizedUserCannotSaveMessageOrChangeEssayActivity() throws Exception {
        Long otherUserId = createUser();
        Essay otherUsersEssay = createEssay(otherUserId, "A private essay owned by another user.");
        LocalDateTime originalActivity = LocalDateTime.now().minusDays(3).truncatedTo(ChronoUnit.MICROS);
        setLastActivity(otherUsersEssay.getId(), originalActivity);

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                persistenceService.saveUserMessage(
                        fixtureUserId, otherUsersEssay.getId(), "This must not be saved."
                ));

        assertEquals("Essay does not belong to the current user", exception.getMessage());
        assertEquals(originalActivity, readLastActivity(otherUsersEssay.getId()));
        assertTrue(readThroughFreshConnection(otherUsersEssay.getId()).isEmpty());
    }

    @Test
    void savingAssistantMessageDoesNotChangeLastActivity() throws Exception {
        LocalDateTime originalActivity = LocalDateTime.now().minusDays(4).truncatedTo(ChronoUnit.MICROS);
        setLastActivity(fixtureEssay.getId(), originalActivity);

        EssayMessage saved = persistenceService.saveAssistantMessage(
                fixtureEssay.getId(), "An assistant response should not reorder the conversation."
        );

        assertEquals(originalActivity, readLastActivity(fixtureEssay.getId()));
        assertEquals(List.of(new StoredMessage(
                saved.getId(), "ASSISTANT", "An assistant response should not reorder the conversation."
        )), readThroughFreshConnection(fixtureEssay.getId()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void committedUserSurvivesAiFailureAndOuterTransactionRollback(boolean timeout) throws Exception {
        assertTrue(AopUtils.isAopProxy(messageService));
        assertTrue(AopUtils.isAopProxy(persistenceService));
        LocalDateTime originalActivity = LocalDateTime.now().minusDays(5).truncatedTo(ChronoUnit.MICROS);
        setLastActivity(fixtureEssay.getId(), originalActivity);
        AtomicReference<Long> committedUserId = new AtomicReference<>();
        AtomicReference<LocalDateTime> committedActivity = new AtomicReference<>();
        when(aiChatService.chat(anyList())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "The AI request must run after USER commit and outside a database transaction");

            // A separate connection cannot see an uncommitted INSERT from the service.
            List<StoredMessage> visible = readThroughFreshConnection(fixtureEssay.getId());
            assertEquals(1, visible.size());
            assertEquals("USER", visible.getFirst().role());
            assertEquals("Please explain my grammar.", visible.getFirst().content());
            committedUserId.set(visible.getFirst().id());
            LocalDateTime visibleActivity = readLastActivity(fixtureEssay.getId());
            assertTrue(visibleActivity.isAfter(originalActivity),
                    "A fresh connection must see the activity update before the AI call starts");
            committedActivity.set(visibleActivity);
            throw new AiChatService.AiChatException("Simulated AI failure", timeout);
        });

        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);
        EssayMessageService.ChatResult result = outerTransaction.execute(status -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            EssayMessageService.ChatResult chat = messageService.createUserMessage(
                    fixtureUserId, fixtureEssay.getId(), "Please explain my grammar."
            );
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),
                    "The caller transaction must resume after the chat method");
            status.setRollbackOnly();
            return chat;
        });

        assertNotNull(result);
        assertEquals(timeout ? 504 : 502, result.status());
        assertNull(result.assistantMessage());
        assertNotNull(committedUserId.get());
        assertEquals(committedUserId.get(), result.userMessage().getId());
        List<StoredMessage> afterRollback = readThroughFreshConnection(fixtureEssay.getId());
        assertEquals(List.of(new StoredMessage(
                committedUserId.get(), "USER", "Please explain my grammar."
        )), afterRollback, "Both an AI failure and caller rollback must leave the USER row committed");
        assertEquals(committedActivity.get(), readLastActivity(fixtureEssay.getId()),
                "Both an AI failure and caller rollback must leave the activity update committed");
    }

    @Test
    void successfulAiReplyPersistsBothMessages() throws Exception {
        when(aiChatService.chat(anyList())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            List<StoredMessage> visible = readThroughFreshConnection(fixtureEssay.getId());
            assertEquals(1, visible.size());
            assertEquals("USER", visible.getFirst().role());
            return new AiChatService.ChatReply("Use a clearer topic sentence.", "test-model");
        });

        EssayMessageService.ChatResult result = messageService.createUserMessage(
                fixtureUserId, fixtureEssay.getId(), "How can I improve paragraph two?"
        );

        assertNotNull(result);
        assertEquals(201, result.status());
        assertEquals("test-model", result.aiModel());
        assertNotNull(result.assistantMessage());
        assertEquals(List.of(
                new StoredMessage(result.userMessage().getId(), "USER", "How can I improve paragraph two?"),
                new StoredMessage(result.assistantMessage().getId(), "ASSISTANT", "Use a clearer topic sentence.")
        ), readThroughFreshConnection(fixtureEssay.getId()));
    }

    @Test
    void recentHistorySqlSelectsOnlyNewestEightBeforeCurrentMessageForThisEssay() {
        List<EssayMessage> history = new ArrayList<>();
        for (int index = 0; index < 11; index++) {
            history.add(insertMessage(fixtureEssay.getId(), index % 2 == 0 ? "USER" : "ASSISTANT",
                    "History " + index));
        }
        Essay otherEssay = createEssay(fixtureUserId, "A second essay for history isolation.");
        insertMessage(otherEssay.getId(), "USER", "A different essay must never enter the context");
        EssayMessage current = insertMessage(fixtureEssay.getId(), "USER", "The latest question");
        insertMessage(fixtureEssay.getId(), "USER", "A later concurrent request");

        List<EssayMessage> recent = messageMapper.findRecentBefore(fixtureEssay.getId(), current.getId());

        List<Long> expectedIds = history.subList(3, 11).reversed().stream().map(EssayMessage::getId).toList();
        assertEquals(expectedIds, recent.stream().map(EssayMessage::getId).toList());
        assertTrue(recent.stream().allMatch(message -> message.getEssayId().equals(fixtureEssay.getId())));
        assertTrue(recent.stream().allMatch(message -> message.getId() < current.getId()));
    }

    @Test
    void deletingOwnedEssayDeletesItsWholeConversationByForeignKeyCascade() throws Exception {
        Long essayId = fixtureEssay.getId();
        insertMessage(essayId, "USER", "First question");
        insertMessage(essayId, "ASSISTANT", "First answer");

        assertFalse(essayService.deleteMyEssay(fixtureUserId + 1, essayId));
        assertNotNull(essayMapper.findByIdAndUserId(essayId, fixtureUserId));
        assertEquals(2, readThroughFreshConnection(essayId).size());

        assertTrue(essayService.deleteMyEssay(fixtureUserId, essayId));
        assertNull(essayMapper.findByIdAndUserId(essayId, fixtureUserId));
        assertTrue(readThroughFreshConnection(essayId).isEmpty());
    }

    private Long createUser() {
        User user = new User();
        user.setUsername("chat_it_" + UUID.randomUUID().toString().replace("-", ""));
        user.setEmail(user.getUsername() + "@example.invalid");
        user.setPassword("test-only-not-a-login-password");
        userMapper.insert(user);
        Long userId = userMapper.findByUsername(user.getUsername()).getId();
        fixtureUserIds.add(userId);
        return userId;
    }

    private Essay createEssay(Long userId, String question) {
        Essay essay = new Essay();
        essay.setUserId(userId);
        essay.setQuestion(question);
        essay.setContent("Reading provides knowledge and enjoyment.");
        essay.setEssayType("TASK_2");
        essay.setWordCount(6);
        essay.setEvaluationStatus(EvaluationStatus.NOT_EVALUATED);
        essayMapper.insert(essay);
        fixtureEssayIds.add(essay.getId());
        return essay;
    }

    private EssayMessage insertMessage(Long essayId, String role, String content) {
        EssayMessage message = new EssayMessage();
        message.setEssayId(essayId);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.of(2026, 1, 1, 12, 0));
        messageMapper.insert(message);
        return message;
    }

    private void setLastActivity(Long essayId, LocalDateTime activity) {
        new JdbcTemplate(dataSource).update(
                "UPDATE essay SET last_activity_at = ? WHERE id = ?",
                activity,
                essayId
        );
    }

    private LocalDateTime readLastActivity(Long essayId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT last_activity_at
                     FROM essay
                     WHERE id = ?
                     """)) {
            query.setLong(1, essayId);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                return rows.getObject("last_activity_at", LocalDateTime.class);
            }
        }
    }

    private List<StoredMessage> readThroughFreshConnection(Long essayId) throws SQLException {
        List<StoredMessage> messages = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT id, `role`, content
                     FROM essay_message
                     WHERE essay_id = ?
                     ORDER BY id ASC
                     """)) {
            query.setLong(1, essayId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    messages.add(new StoredMessage(rows.getLong("id"), rows.getString("role"), rows.getString("content")));
                }
            }
        }
        return messages;
    }

    private record StoredMessage(Long id, String role, String content) {
    }
}
