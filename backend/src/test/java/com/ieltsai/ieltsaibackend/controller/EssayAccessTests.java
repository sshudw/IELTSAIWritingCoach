package com.ieltsai.ieltsaibackend.controller;

import com.ieltsai.ieltsaibackend.exception.GlobalExceptionHandler;
import com.ieltsai.ieltsaibackend.interceptor.LoginInterceptor;

import com.ieltsai.ieltsaibackend.entity.Essay;
import com.ieltsai.ieltsaibackend.entity.EvaluationStatus;
import com.ieltsai.ieltsaibackend.mapper.EssayMapper;
import com.ieltsai.ieltsaibackend.service.AiEvaluationService;
import com.ieltsai.ieltsaibackend.service.EssayService;
import com.ieltsai.ieltsaibackend.service.EvaluationSubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EssayAccessTests {

    private static final String CREATE_REQUEST = """
            {
              "question": "Discuss public transport.",
              "content": "Public transport helps many people.",
              "essayType": "Task 2"
            }
            """;

    private EssayMapper essayMapper;
    private AiEvaluationService aiEvaluationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        essayMapper = mock(EssayMapper.class);
        aiEvaluationService = mock(AiEvaluationService.class);
        EssayService essayService = new EssayService(essayMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new EssayController(essayService, mock(EvaluationSubmissionService.class)))
                .addInterceptors(new LoginInterceptor(new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void allEssayEndpointsRejectUnauthenticatedRequestsWithoutCreatingSession() throws Exception {
        for (MockHttpServletRequestBuilder request : essayRequests()) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Not logged in"))
                    .andExpect(result -> assertNull(result.getRequest().getSession(false)));
        }

        verifyNoInteractions(essayMapper, aiEvaluationService);
    }

    @Test
    void captchaSessionAloneDoesNotAllowAccessToAnyEssayEndpoint() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("AUTH_CAPTCHA", "98X1");

        for (MockHttpServletRequestBuilder request : essayRequests()) {
            mockMvc.perform(request.session(session))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Not logged in"));
        }

        verifyNoInteractions(essayMapper, aiEvaluationService);
    }

    @Test
    void createsEssayUsingSessionOwnerAndOnlyClientEditableFields() throws Exception {
        mockMvc.perform(post("/api/essays")
                        .session(loggedInSession(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_REQUEST))
                .andExpect(status().isOk())
                .andExpect(content().string("Essay created successfully"));

        ArgumentCaptor<Essay> captured = ArgumentCaptor.forClass(Essay.class);
        verify(essayMapper).insert(captured.capture());
        Essay essay = captured.getValue();
        assertEquals(7L, essay.getUserId());
        assertEquals("Discuss public transport.", essay.getQuestion());
        assertEquals("Public transport helps many people.", essay.getContent());
        assertEquals("Task 2", essay.getEssayType());
        assertEquals(5, essay.getWordCount());
        assertEquals(EvaluationStatus.NOT_EVALUATED, essay.getEvaluationStatus());
        verifyNoMoreInteractions(essayMapper);
        verifyNoInteractions(aiEvaluationService);
    }

    @Test
    void forgedOwnershipAndServerManagedFieldsCannotChangeSavedEssay() throws Exception {
        mockMvc.perform(post("/api/essays")
                        .session(loggedInSession(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": 99,
                                  "id": 123,
                                  "wordCount": 999,
                                  "createdAt": "2000-01-01T00:00:00",
                                  "question": "Discuss public transport.",
                                  "content": "  Public   transport\\nhelps\\tpeople.  ",
                                  "essayType": "Task 2"
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<Essay> captured = ArgumentCaptor.forClass(Essay.class);
        verify(essayMapper).insert(captured.capture());
        Essay essay = captured.getValue();
        assertEquals(7L, essay.getUserId());
        assertEquals(4, essay.getWordCount());
        assertNull(essay.getId());
        assertNull(essay.getCreatedAt());
        verifyNoMoreInteractions(essayMapper);
        verifyNoInteractions(aiEvaluationService);
    }

    @Test
    void listQueryUsesSessionOwnerAndDefaultPaginationEvenWhenClientSuppliesAnotherUserId()
            throws Exception {
        Essay essay = essay(21L, 7L);
        essay.setOverallScore(new BigDecimal("6.5"));
        essay.setEvaluationStatus(EvaluationStatus.COMPLETED);
        essay.setLastActivityAt(LocalDateTime.of(2026, 9, 14, 15, 30));
        when(essayMapper.countByUserId(7L)).thenReturn(1L);
        when(essayMapper.findPageByUserId(7L, 10, 0L)).thenReturn(List.of(essay));

        mockMvc.perform(get("/api/essays")
                        .session(loggedInSession(7L))
                        .param("userId", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(10))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.essays.length()").value(1))
                .andExpect(jsonPath("$.essays[0].id").value(21))
                .andExpect(jsonPath("$.essays[0].title").value("Discuss public transport."))
                .andExpect(jsonPath("$.essays[0].essayType").value("Task 2"))
                .andExpect(jsonPath("$.essays[0].wordCount").value(5))
                .andExpect(jsonPath("$.essays[0].overallScore").value(6.5))
                .andExpect(jsonPath("$.essays[0].evaluationStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.essays[0].lastActivityAt")
                        .value("2026-09-14T15:30:00"))
                .andExpect(jsonPath("$.essays[0].userId").doesNotExist())
                .andExpect(jsonPath("$.essays[0].content").doesNotExist());

        verify(essayMapper).countByUserId(7L);
        verify(essayMapper).findPageByUserId(7L, 10, 0L);
        verifyNoMoreInteractions(essayMapper);
    }

    @Test
    void loggedInUserWithoutEssaysReceivesEmptyFirstPage() throws Exception {
        when(essayMapper.countByUserId(7L)).thenReturn(0L);
        when(essayMapper.findPageByUserId(7L, 10, 0L)).thenReturn(List.of());

        mockMvc.perform(get("/api/essays").session(loggedInSession(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(10))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.essays").isEmpty());

        verify(essayMapper).countByUserId(7L);
        verify(essayMapper).findPageByUserId(7L, 10, 0L);
        verifyNoMoreInteractions(essayMapper);
    }

    @Test
    void requestedPageCalculatesOffsetAndRoundsTotalPagesUp() throws Exception {
        when(essayMapper.countByUserId(7L)).thenReturn(23L);
        when(essayMapper.findPageByUserId(7L, 10, 20L)).thenReturn(List.of());

        mockMvc.perform(get("/api/essays")
                        .session(loggedInSession(7L))
                        .param("page", "3")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(3))
                .andExpect(jsonPath("$.pageSize").value(10))
                .andExpect(jsonPath("$.total").value(23))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.essays").isEmpty());

        verify(essayMapper).countByUserId(7L);
        verify(essayMapper).findPageByUserId(7L, 10, 20L);
        verifyNoMoreInteractions(essayMapper);
    }

    @Test
    void pageMustBeAtLeastOne() throws Exception {
        mockMvc.perform(get("/api/essays")
                        .session(loggedInSession(7L))
                        .param("page", "0")
                        .param("pageSize", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("page must be at least 1"));

        verifyNoInteractions(essayMapper, aiEvaluationService);
    }

    @Test
    void pageSizeIsFixedAtTen() throws Exception {
        mockMvc.perform(get("/api/essays")
                        .session(loggedInSession(7L))
                        .param("page", "1")
                        .param("pageSize", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("pageSize must be 10"));

        verifyNoInteractions(essayMapper, aiEvaluationService);
        verifyNoMoreInteractions(essayMapper);
    }

    @Test
    void singleEssayQueryUsesBothEssayIdAndSessionOwner() throws Exception {
        when(essayMapper.findByIdAndUserId(21L, 7L)).thenReturn(essay(21L, 7L));

        mockMvc.perform(get("/api/essays/21")
                        .session(loggedInSession(7L))
                        .param("userId", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(21))
                .andExpect(jsonPath("$.userId").value(7));

        verify(essayMapper).findByIdAndUserId(21L, 7L);
        verifyNoMoreInteractions(essayMapper);
    }

    @Test
    void anotherUsersEssayCannotBeReadByChangingEssayId() throws Exception {
        when(essayMapper.findByIdAndUserId(21L, 99L)).thenReturn(essay(21L, 99L));

        mockMvc.perform(get("/api/essays/21").session(loggedInSession(7L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Essay not found"));

        verify(essayMapper).findByIdAndUserId(21L, 7L);
        verifyNoMoreInteractions(essayMapper);
    }

    @Test
    void missingEssayReturnsSameNotFoundResponseAsAnotherUsersEssay() throws Exception {
        mockMvc.perform(get("/api/essays/999").session(loggedInSession(7L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Essay not found"));

        verify(essayMapper).findByIdAndUserId(999L, 7L);
        verifyNoMoreInteractions(essayMapper);
    }

    @Test
    void oldUserIdPathNoLongerExposesEssays() throws Exception {
        mockMvc.perform(get("/api/essays/user/99").session(loggedInSession(7L)))
                .andExpect(status().isNotFound());

        verifyNoInteractions(essayMapper);
    }

    @Test
    void loggedInUserDeletesOnlyOwnEssayConversation() throws Exception {
        when(essayMapper.deleteByIdAndUserId(21L, 7L)).thenReturn(1);

        mockMvc.perform(delete("/api/essays/21")
                        .session(loggedInSession(7L))
                        .param("userId", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("Essay conversation deleted successfully"));

        verify(essayMapper).deleteByIdAndUserId(21L, 7L);
        verifyNoMoreInteractions(essayMapper);
        verifyNoInteractions(aiEvaluationService);
    }

    @Test
    void missingOrAnotherUsersEssayCannotBeDeleted() throws Exception {
        mockMvc.perform(delete("/api/essays/21").session(loggedInSession(7L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Essay not found"));

        verify(essayMapper).deleteByIdAndUserId(21L, 7L);
        verifyNoMoreInteractions(essayMapper);
        verifyNoInteractions(aiEvaluationService);
    }

    private static List<MockHttpServletRequestBuilder> essayRequests() {
        return List.of(
                post("/api/essays").contentType(MediaType.APPLICATION_JSON).content(CREATE_REQUEST),
                post("/api/essays/evaluate").contentType(MediaType.APPLICATION_JSON).content(CREATE_REQUEST),
                get("/api/essays"),
                get("/api/essays/21"),
                delete("/api/essays/21")
        );
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
        essay.setQuestion("Discuss public transport.");
        essay.setContent("Public transport helps many people.");
        essay.setEssayType("Task 2");
        essay.setWordCount(5);
        return essay;
    }
}
