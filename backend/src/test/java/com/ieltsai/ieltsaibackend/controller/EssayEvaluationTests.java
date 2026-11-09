package com.ieltsai.ieltsaibackend.controller;

import com.ieltsai.ieltsaibackend.dto.*;
import com.ieltsai.ieltsaibackend.entity.*;
import com.ieltsai.ieltsaibackend.exception.GlobalExceptionHandler;
import com.ieltsai.ieltsaibackend.interceptor.LoginInterceptor;
import com.ieltsai.ieltsaibackend.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EssayEvaluationTests {
    private static final String BODY = """
            {"userId":99,"question":"Discuss transport.","content":"My essay.","essayType":"Task 2"}
            """;
    private EvaluationSubmissionService submissions;
    private EvaluationTaskService tasks;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        submissions = mock(EvaluationSubmissionService.class);
        tasks = mock(EvaluationTaskService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new EssayController(mock(EssayService.class), submissions), new EvaluationTaskController(tasks))
                .addInterceptors(new LoginInterceptor(new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void submissionReturns202AndLocationUsingOnlySessionOwner() throws Exception {
        EvaluationTask task = new EvaluationTask();
        task.setId(12L);
        task.setEssayId(31L);
        task.setStatus(EvaluationTaskStatus.PENDING);
        when(submissions.submit(eq(7L), eq("test-key"), any())).thenReturn(task);
        mvc.perform(post("/api/essays/evaluate").session(session())
                        .header("Idempotency-Key", "test-key").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/evaluation-tasks/12"))
                .andExpect(jsonPath("$.taskId").value(12))
                .andExpect(jsonPath("$.essayId").value(31))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.result").doesNotExist());
        verify(submissions).submit(7L, "test-key",
                new CreateEssayRequest("Discuss transport.", "My essay.", "TASK_2"));
    }

    @Test
    void missingKeyIsRejected() throws Exception {
        mvc.perform(post("/api/essays/evaluate").session(session())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(submissions);
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "a b", "中文"})
    void invalidKeyIsRejected(String key) throws Exception {
        mvc.perform(post("/api/essays/evaluate").session(session()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(submissions);
    }

    @Test
    void overlongKeyAndContentAreRejected() throws Exception {
        mvc.perform(post("/api/essays/evaluate").session(session()).header("Idempotency-Key", "a".repeat(129))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/essays/evaluate").session(session()).header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("My essay.", "a".repeat(15001))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("content is too long"));
        verifyNoInteractions(submissions);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"question\":\" \",\"content\":\"essay\",\"essayType\":\"TASK_2\"}",
            "{\"question\":\"q\",\"content\":null,\"essayType\":\"TASK_2\"}",
            "{\"question\":\"q\",\"content\":\"essay\",\"essayType\":\"TASK_3\"}"
    })
    void invalidRequestsDoNotSubmit(String body) throws Exception {
        mvc.perform(post("/api/essays/evaluate").session(session()).header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(submissions);
    }

    @Test
    void conflictingKeyReturns409WithCommonErrorBody() throws Exception {
        when(submissions.submit(anyLong(), anyString(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT));
        mvc.perform(post("/api/essays/evaluate").session(session()).header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void rateLimitedSubmissionReturns429WithCommonErrorBody() throws Exception {
        when(submissions.submit(anyLong(), anyString(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS));
        mvc.perform(post("/api/essays/evaluate").session(session()).header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").value("Too many AI evaluation requests"))
                .andExpect(jsonPath("$.exception").doesNotExist());
    }

    @Test
    void statusRequiresSessionAndDoesNotAcceptClientUserId() throws Exception {
        mvc.perform(get("/api/evaluation-tasks/12").param("userId", "7"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/evaluation-tasks/12").session(session()).param("userId", "99"))
                .andExpect(status().isNotFound());
        verify(tasks).getOwned(7L, 12L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PROCESSING"})
    void unfinishedStatusDoesNotExposeResults(String taskStatus) throws Exception {
        when(tasks.getOwned(7L, 12L)).thenReturn(new EvaluationTaskResponse(
                12L, 31L, EvaluationTaskStatus.valueOf(taskStatus), null, null));
        mvc.perform(get("/api/evaluation-tasks/12").session(session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(taskStatus))
                .andExpect(jsonPath("$.result").doesNotExist())
                .andExpect(jsonPath("$.errorMessage").doesNotExist());
    }

    @Test
    void completedReturnsScoresAndFailedReturnsOnlySafeError() throws Exception {
        BigDecimal score = new BigDecimal("6.5");
        when(tasks.getOwned(7L, 12L)).thenReturn(new EvaluationTaskResponse(12L, 31L,
                EvaluationTaskStatus.COMPLETED, new EvaluateEssayResponse(31L, 2, EvaluationStatus.COMPLETED,
                score, score, score, score, score, "Feedback", "model"), null));
        mvc.perform(get("/api/evaluation-tasks/12").session(session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.overallScore").value(6.5))
                .andExpect(jsonPath("$.result.aiFeedback").value("Feedback"));
        when(tasks.getOwned(7L, 12L)).thenReturn(new EvaluationTaskResponse(
                12L, 31L, EvaluationTaskStatus.FAILED, null, "AI evaluation failed"));
        mvc.perform(get("/api/evaluation-tasks/12").session(session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorMessage").value("AI evaluation failed"))
                .andExpect(jsonPath("$.result").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("AUTH_USER_ID", 7L);
        return session;
    }
}
