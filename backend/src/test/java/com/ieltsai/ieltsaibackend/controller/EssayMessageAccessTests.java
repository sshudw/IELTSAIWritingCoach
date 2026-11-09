package com.ieltsai.ieltsaibackend.controller;

import com.ieltsai.ieltsaibackend.exception.GlobalExceptionHandler;
import com.ieltsai.ieltsaibackend.interceptor.LoginInterceptor;

import com.ieltsai.ieltsaibackend.entity.Essay;
import com.ieltsai.ieltsaibackend.entity.EssayMessage;
import com.ieltsai.ieltsaibackend.mapper.EssayMapper;
import com.ieltsai.ieltsaibackend.mapper.EssayMessageMapper;
import com.ieltsai.ieltsaibackend.service.EssayMessageService;
import com.ieltsai.ieltsaibackend.service.EssayMessagePersistenceService;
import com.ieltsai.ieltsaibackend.service.EssayChatContextBuilder;
import com.ieltsai.ieltsaibackend.service.AiChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EssayMessageAccessTests {

    private EssayMapper essayMapper;
    private EssayMessageMapper essayMessageMapper;
    private AiChatService aiChatService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        essayMapper = mock(EssayMapper.class);
        essayMessageMapper = mock(EssayMessageMapper.class);
        aiChatService = mock(AiChatService.class);
        EssayMessageService service = new EssayMessageService(
                essayMapper, essayMessageMapper,
                new EssayMessagePersistenceService(essayMessageMapper, essayMapper), aiChatService,
                new EssayChatContextBuilder(new ObjectMapper(), "Chinese")
        );
        AtomicLong nextId = new AtomicLong(31L);
        doAnswer(invocation -> {
            EssayMessage message = invocation.getArgument(0);
            message.setId(nextId.getAndIncrement());
            return null;
        }).when(essayMessageMapper).insert(any(EssayMessage.class));
        when(essayMapper.updateLastActivity(any(), any(), any())).thenReturn(1);
        mockMvc = MockMvcBuilders.standaloneSetup(new EssayMessageController(service))
                .addInterceptors(new LoginInterceptor(new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void unauthenticatedRequestReturnsUnauthorizedWithoutQueryingDatabase() throws Exception {
        mockMvc.perform(get("/api/essays/21/messages"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Not logged in"))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertNull(
                        result.getRequest().getSession(false)
                ));

        verifyNoInteractions(essayMapper, essayMessageMapper, aiChatService);
    }

    @Test
    void anotherUsersEssayReturnsNotFoundAndMessagesAreNotQueried() throws Exception {
        mockMvc.perform(get("/api/essays/21/messages").session(loggedInSession(7L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Essay not found"));

        verify(essayMapper).findByIdAndUserId(21L, 7L);
        verify(essayMessageMapper, never()).findByEssayId(21L);
    }

    @Test
    void ownedEssayWithoutMessagesReturnsEmptyArray() throws Exception {
        when(essayMapper.findByIdAndUserId(21L, 7L)).thenReturn(essay(21L, 7L));
        when(essayMessageMapper.findByEssayId(21L)).thenReturn(List.of());

        mockMvc.perform(get("/api/essays/21/messages").session(loggedInSession(7L)))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(essayMapper).findByIdAndUserId(21L, 7L);
        verify(essayMessageMapper).findByEssayId(21L);
    }

    @Test
    void ownedEssayReturnsMessagesInMapperOrder() throws Exception {
        when(essayMapper.findByIdAndUserId(21L, 7L)).thenReturn(essay(21L, 7L));
        when(essayMessageMapper.findByEssayId(21L)).thenReturn(List.of(
                message(1L, 21L, "user", "My essay", LocalDateTime.of(2026, 9, 10, 10, 0)),
                message(2L, 21L, "assistant", "Feedback", LocalDateTime.of(2026, 9, 10, 10, 1))
        ));

        mockMvc.perform(get("/api/essays/21/messages")
                        .session(loggedInSession(7L))
                        .param("userId", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].essayId").value(21))
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[0].content").value("My essay"))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].role").value("assistant"))
                .andExpect(jsonPath("$[1].content").value("Feedback"));

        verify(essayMapper).findByIdAndUserId(21L, 7L);
        verify(essayMessageMapper).findByEssayId(21L);
    }

    @Test
    void unauthenticatedUserCannotCreateMessage() throws Exception {
        mockMvc.perform(post("/api/essays/21/messages")
                        .contentType("application/json")
                        .content("{\"content\":\"Please explain this sentence.\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Not logged in"));

        verifyNoInteractions(essayMapper, essayMessageMapper, aiChatService);
    }

    @Test
    void blankMessageContentIsRejectedBeforeDatabaseQuery() throws Exception {
        mockMvc.perform(post("/api/essays/21/messages")
                        .session(loggedInSession(7L))
                        .contentType("application/json")
                        .content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("content is required"));

        verifyNoInteractions(essayMapper, essayMessageMapper, aiChatService);
    }

    @Test
    void messageCannotBeAddedToAnotherUsersEssay() throws Exception {
        mockMvc.perform(post("/api/essays/21/messages")
                        .session(loggedInSession(7L))
                        .contentType("application/json")
                        .content("{\"content\":\"Please explain this sentence.\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Essay not found"));

        verify(essayMapper).findByIdAndUserId(21L, 7L);
        verify(essayMessageMapper, never()).insert(any());
        verifyNoInteractions(aiChatService);
    }

    @Test
    void ownedEssayCreatesUserMessageAndReturnsIt() throws Exception {
        when(essayMapper.findByIdAndUserId(21L, 7L)).thenReturn(essay(21L, 7L));
        when(aiChatService.chat(any())).thenReturn(new AiChatService.ChatReply("Here is an explanation.", "actual-model"));

        mockMvc.perform(post("/api/essays/21/messages")
                        .session(loggedInSession(7L))
                        .contentType("application/json")
                        .content("""
                                {
                                  "content": "Please explain this sentence.",
                                  "role": "ASSISTANT",
                                  "essayId": 99,
                                  "id": 999,
                                  "createdAt": "2000-01-01T00:00:00",
                                  "userId": 99
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userMessage.id").value(31))
                .andExpect(jsonPath("$.userMessage.essayId").value(21))
                .andExpect(jsonPath("$.userMessage.role").value("USER"))
                .andExpect(jsonPath("$.userMessage.content").value("Please explain this sentence."))
                .andExpect(jsonPath("$.userMessage.createdAt").exists())
                .andExpect(jsonPath("$.assistantMessage.id").value(32))
                .andExpect(jsonPath("$.assistantMessage.role").value("ASSISTANT"))
                .andExpect(jsonPath("$.assistantMessage.content").value("Here is an explanation."))
                .andExpect(jsonPath("$.aiModel").value("actual-model"));

        org.mockito.ArgumentCaptor<EssayMessage> captor =
                org.mockito.ArgumentCaptor.forClass(EssayMessage.class);
        verify(essayMapper).findByIdAndUserId(21L, 7L);
        verify(essayMessageMapper, times(2)).insert(captor.capture());
        EssayMessage saved = captor.getAllValues().getFirst();
        assertEquals(21L, saved.getEssayId());
        assertEquals("USER", saved.getRole());
        assertEquals("Please explain this sentence.", saved.getContent());
        assertNotNull(saved.getCreatedAt());
        verify(essayMessageMapper).findRecentBefore(21L, 31L);
    }

    @Test
    void aiFailureReturnsSavedUserIdWithoutWritingAssistant() throws Exception {
        when(essayMapper.findByIdAndUserId(21L, 7L)).thenReturn(essay(21L, 7L));
        when(aiChatService.chat(any())).thenThrow(new AiChatService.AiChatException("Provider failed", false));

        mockMvc.perform(post("/api/essays/21/messages")
                        .session(loggedInSession(7L))
                        .contentType("application/json")
                        .content("{\"content\":\"Explain my essay.\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.userMessageId").value(31))
                .andExpect(jsonPath("$.message").value("USER message saved, but AI reply failed"));

        org.mockito.ArgumentCaptor<EssayMessage> captor = org.mockito.ArgumentCaptor.forClass(EssayMessage.class);
        verify(essayMessageMapper).insert(captor.capture());
        assertEquals("USER", captor.getValue().getRole());
    }

    @Test
    void aiTimeoutReturnsGatewayTimeoutWithSavedUserId() throws Exception {
        when(essayMapper.findByIdAndUserId(21L, 7L)).thenReturn(essay(21L, 7L));
        when(aiChatService.chat(any())).thenThrow(new AiChatService.AiChatException("Timed out", true));

        mockMvc.perform(post("/api/essays/21/messages")
                        .session(loggedInSession(7L))
                        .contentType("application/json")
                        .content("{\"content\":\"Explain my essay.\"}"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.userMessageId").value(31));

        verify(essayMessageMapper).insert(any(EssayMessage.class));
    }

    @Test
    void assistantSaveFailureReturnsSavedUserId() throws Exception {
        when(essayMapper.findByIdAndUserId(21L, 7L)).thenReturn(essay(21L, 7L));
        when(aiChatService.chat(any())).thenReturn(new AiChatService.ChatReply("AI reply", "actual-model"));
        doAnswer(invocation -> {
            EssayMessage message = invocation.getArgument(0);
            if ("ASSISTANT".equals(message.getRole())) {
                throw new DataAccessResourceFailureException("Database unavailable");
            }
            message.setId(31L);
            return null;
        }).when(essayMessageMapper).insert(any(EssayMessage.class));

        mockMvc.perform(post("/api/essays/21/messages")
                        .session(loggedInSession(7L))
                        .contentType("application/json")
                        .content("{\"content\":\"Explain my essay.\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.userMessageId").value(31))
                .andExpect(jsonPath("$.message").value("USER message saved, but the conversation could not be completed"));
    }

    @Test
    void captchaOnlySessionCannotSendMessages() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("AUTH_CAPTCHA", "98X1");
        mockMvc.perform(post("/api/essays/21/messages")
                        .session(session)
                        .contentType("application/json")
                        .content("{\"content\":\"Explain my essay.\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(essayMapper, essayMessageMapper, aiChatService);
    }

    @Test
    void missingOrOversizedContentDoesNotCallAiOrSaveMessages() throws Exception {
        for (String body : List.of("{}", "{\"content\":null}",
                "{\"content\":\"" + "文".repeat(20_001) + "\"}")) {
            mockMvc.perform(post("/api/essays/21/messages")
                            .session(loggedInSession(7L))
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(essayMapper, essayMessageMapper, aiChatService);
    }

    @Test
    void failedUserInsertPreventsAnyAiCall() throws Exception {
        when(essayMapper.findByIdAndUserId(21L, 7L)).thenReturn(essay(21L, 7L));
        doAnswer(invocation -> {
            throw new DataAccessResourceFailureException("Database unavailable");
        }).when(essayMessageMapper).insert(any(EssayMessage.class));

        mockMvc.perform(
                post("/api/essays/21/messages")
                        .session(loggedInSession(7L))
                        .contentType("application/json")
                        .content("{\"content\":\"Explain my essay.\"}")
        ).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("Internal server error"))
                .andExpect(jsonPath("$.userMessageId").doesNotExist());

        verify(essayMessageMapper, never()).findRecentBefore(any(), any());
        verifyNoInteractions(aiChatService);
    }

    private static MockHttpSession loggedInSession(Long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("AUTH_USER_ID", userId);
        return session;
    }

    private static Essay essay(Long id, Long userId) {
        Essay essay = new Essay();
        essay.setId(id);
        essay.setUserId(userId);
        return essay;
    }

    private static EssayMessage message(
            Long id,
            Long essayId,
            String role,
            String content,
            LocalDateTime createdAt
    ) {
        EssayMessage message = new EssayMessage();
        message.setId(id);
        message.setEssayId(essayId);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(createdAt);
        return message;
    }
}
