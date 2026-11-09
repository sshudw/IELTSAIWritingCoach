package com.ieltsai.ieltsaibackend.controller;

import com.ieltsai.ieltsaibackend.entity.Essay;
import com.ieltsai.ieltsaibackend.entity.EssayMessage;
import com.ieltsai.ieltsaibackend.entity.EvaluationStatus;
import com.ieltsai.ieltsaibackend.entity.User;
import tools.jackson.databind.json.JsonMapper;
import org.mockito.ArgumentCaptor;
import com.ieltsai.ieltsaibackend.service.AuthService;
import com.ieltsai.ieltsaibackend.service.EssayMessageService;
import com.ieltsai.ieltsaibackend.service.EssayService;
import com.ieltsai.ieltsaibackend.service.EvaluationSubmissionService;
import com.ieltsai.ieltsaibackend.service.EvaluationTaskService;
import com.ieltsai.ieltsaibackend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Use Boot's actual MVC configuration, including static-resource handling for unknown paths.
// Services are mocked so these HTTP-contract tests need neither MySQL nor DeepSeek.
@SpringBootTest(properties = "evaluation.worker.enabled=false")
class ApiErrorResponseTests {
    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private EssayService essayService;
    @MockitoBean
    private EvaluationSubmissionService submissionService;
    @MockitoBean
    private EvaluationTaskService taskService;
    @MockitoBean
    private EssayMessageService messageService;
    @MockitoBean
    private AuthService authService;
    @MockitoBean
    private UserService userService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void controllerErrorsUseTheCommonBody() throws Exception {
        assertError(mvc.perform(get("/api/essays")), 401, "Not logged in");
        assertError(mvc.perform(get("/api/essays").session(session()).param("page", "0")),
                400, "page must be at least 1");
        assertError(mvc.perform(get("/api/essays/21").session(session())),
                404, "Essay not found");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/essays?page=abc", "/api/essays/abc", "/api/essays/abc/messages"})
    void mvcRejectsInvalidNumberParametersWithJson(String path) throws Exception {
        assertError(mvc.perform(get(path).session(session())), 400, "Invalid request");
        verifyNoInteractions(essayService, messageService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{", "[]"})
    void mvcRejectsMissingOrMalformedBodiesWithJson(String body) throws Exception {
        assertError(mvc.perform(post("/api/essays/21/messages").session(session())
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                400, "Invalid request");
        verifyNoInteractions(messageService);
    }

    @Test
    void unknownPathReturnsJsonEvenForHtmlAcceptHeader() throws Exception {
        assertError(mvc.perform(get("/api/no-such-endpoint").session(session()).accept(MediaType.TEXT_HTML)),
                404, "Resource not found");
    }

    @Test
    void mvcKeepsMethodNotAllowedStatusAndAllowHeader() throws Exception {
        assertError(mvc.perform(put("/api/essays").session(session())), 405, "Method Not Allowed")
                .andExpect(header().string("Allow", containsString("GET")));
    }

    @Test
    void mvcKeepsUnsupportedMediaTypeStatus() throws Exception {
        assertError(mvc.perform(post("/api/essays/21/messages").session(session())
                        .contentType(MediaType.TEXT_PLAIN).content("hello")),
                415, "Unsupported Media Type");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 404, 429, 500, 502, 504})
    void springStatusExceptionsKeepTheirStatusWithoutLeakingTheirReason(int code) throws Exception {
        when(essayService.getMyEssayById(7L, 21L)).thenThrow(
                new ResponseStatusException(HttpStatus.valueOf(code), "internal-sensitive-reason"));
        mvc.perform(get("/api/essays/21").session(session()))
                .andExpect(status().is(code))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(code))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(content().string(not(containsString("internal-sensitive-reason"))))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    void databaseFailureDuringRegistrationReturnsSafeJson() throws Exception {
        doThrow(new DataAccessResourceFailureException("sensitive SQL connection detail"))
                .when(userService).registerUser(any());
        assertError(mvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"email\":\"a@example.com\",\"password\":\"secret\"}")),
                500, "Internal server error")
                .andExpect(content().string(not(containsString("sensitive"))))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void unexpectedArgumentExceptionIsNotMisclassifiedAsClientError() throws Exception {
        when(essayService.getMyEssayById(7L, 21L)).thenThrow(new IllegalArgumentException("internal bug"));
        assertError(mvc.perform(get("/api/essays/21").session(session())),
                500, "Internal server error");
    }

    @Test
    void captchaFailureReturnsJsonEvenThoughSuccessProducesPng() throws Exception {
        when(authService.createCaptcha(any())).thenThrow(new IllegalStateException("image failure"));
        assertError(mvc.perform(get("/api/auth/captcha").accept(MediaType.IMAGE_PNG)),
                500, "Internal server error");
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 504})
    void chatFailuresKeepTheSavedUserMessageId(int code) throws Exception {
        EssayMessage saved = new EssayMessage();
        saved.setId(31L);
        when(messageService.createUserMessage(7L, 21L, "Explain"))
                .thenReturn(new EssayMessageService.ChatResult(saved, null, null, code, "USER message saved"));
        assertError(mvc.perform(post("/api/essays/21/messages").session(session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Explain\"}")),
                code, "USER message saved")
                .andExpect(jsonPath("$.userMessageId").value(31))
                .andExpect(jsonPath("$.essayId").doesNotExist());
    }

    @Test
    void evaluationSubmissionDatabaseFailureReturnsSafeJson() throws Exception {
        when(submissionService.submit(eq(7L), eq("request-1"), any()))
                .thenThrow(new DataAccessResourceFailureException("sensitive SQL detail"));
        assertError(mvc.perform(post("/api/essays/evaluate").session(session())
                        .header("Idempotency-Key", "request-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Discuss transport\",\"content\":\"My essay\",\"essayType\":\"TASK_2\"}")),
                500, "Internal server error")
                .andExpect(content().string(not(containsString("sensitive SQL detail"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CAPTCHA_EXPIRED", "INVALID_CAPTCHA", "INVALID_CREDENTIALS"})
    void loginFailureUsesCommonBodyWithoutChangingLoginDecisions(String loginStatus) throws Exception {
        when(authService.login(any(), any(), any(), any()))
                .thenReturn(new AuthService.LoginResult(AuthService.LoginStatus.valueOf(loginStatus)));
        int code = loginStatus.equals("INVALID_CREDENTIALS") ? 401 : 400;
        mvc.perform(post("/api/auth/login").session(session()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"secret\",\"captcha\":\"ABCD\"}"))
                .andExpect(status().is(code))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(code))
                .andExpect(jsonPath("$.message").isString());
    }

    @ParameterizedTest
    @MethodSource("blankRequestFields")
    void blankFieldsReturn400BeforeAnyBusinessCall(String path, String body, String field) throws Exception {
        MockHttpServletRequestBuilder request = post(path)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (path.startsWith("/api/essays")) {
            request.session(session());
        }
        assertError(mvc.perform(request),
                400, field + " is required")
                .andExpect(content().string(not(containsString("secret-password"))));
        verifyNoInteractions(essayService, messageService, userService, authService);
    }

    @Test
    void loginInterceptorRejectsAnonymousAndAllowsAuthenticatedRequests() throws Exception {
        mvc.perform(get("/api/essays"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Not logged in"))
                .andExpect(result -> assertNull(result.getRequest().getSession(false)));
        verifyNoInteractions(essayService);

        mvc.perform(get("/api/essays").session(session()))
                .andExpect(status().isOk());
        verify(essayService).getMyEssays(7L, 1, 10);
    }

    @Test
    void loginRegistrationAndCaptchaRemainPublicButCurrentUserIsProtected() throws Exception {
        when(authService.createCaptcha(any())).thenReturn(new byte[]{1, 2, 3});
        when(authService.login(any(), any(), any(), any()))
                .thenReturn(new AuthService.LoginResult(AuthService.LoginStatus.SUCCESS));

        mvc.perform(get("/api/auth/captcha"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").session(new MockHttpSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"secret\",\"captcha\":\"ABCD\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"email\":\"a@example.com\",\"password\":\"secret\"}"))
                .andExpect(status().isOk());
        assertError(mvc.perform(get("/api/auth/me")), 401, "Not logged in");

        verify(authService).createCaptcha(any());
        verify(authService).login(eq("alice"), eq("secret"), eq("ABCD"), any());
        verify(userService).registerUser(any());
    }

    static Stream<Arguments> blankRequestFields() {
        Map<String, Map<String, String>> requests = new LinkedHashMap<>();
        requests.put("/api/users/register", Map.of(
                "username", "alice", "email", "a@example.com", "password", "secret-password"));
        requests.put("/api/auth/login", Map.of(
                "username", "alice", "password", "secret-password", "captcha", "ABCD"));
        Map<String, String> essay = Map.of(
                "question", "Discuss transport", "content", "My essay", "essayType", "TASK_2");
        requests.put("/api/essays", essay);
        requests.put("/api/essays/evaluate", essay);
        requests.put("/api/essays/21/messages", Map.of("content", "Explain"));
        JsonMapper json = new JsonMapper();
        return requests.entrySet().stream().flatMap(request -> request.getValue().keySet().stream()
                .flatMap(field -> Stream.of("missing", "null", "empty", "whitespace").map(kind -> {
                    Map<String, String> body = new LinkedHashMap<>(request.getValue());
                    switch (kind) {
                        case "missing" -> body.remove(field);
                        case "null" -> body.put(field, null);
                        case "empty" -> body.put(field, "");
                        default -> body.put(field, " \t\r\n ");
                    }
                    return Arguments.of(request.getKey(), json.writeValueAsString(body), field);
                })));
    }

    @Test
    void multipleValidationErrorsHaveStableOrderAndDoNotExposeSubmittedValues() throws Exception {
        assertError(mvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"secret-password\"}")),
                400, "email is required; username is required")
                .andExpect(content().string(not(containsString("secret-password"))));
        verifyNoInteractions(userService);
    }

    @Test
    void invalidLoginDoesNotConsumeTheSessionCaptcha() throws Exception {
        MockHttpSession loginSession = new MockHttpSession();
        loginSession.setAttribute("AUTH_CAPTCHA", "ABCD");
        assertError(mvc.perform(post("/api/auth/login").session(loginSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\" \",\"captcha\":\"ABCD\"}")),
                400, "password is required");
        assertEquals("ABCD", loginSession.getAttribute("AUTH_CAPTCHA"));
        assertNull(loginSession.getAttribute("AUTH_USER_ID"));
        verifyNoInteractions(authService);
    }

    @Test
    void validRegistrationMapsOnlyEditableFieldsWithoutTrimmingPassword() throws Exception {
        mvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"email\":\"a@example.com\",\"password\":\" secret \",\"id\":99}"))
                .andExpect(status().isOk())
                .andExpect(content().string("User registered successfully"));
        ArgumentCaptor<User> captured = ArgumentCaptor.forClass(User.class);
        verify(userService).registerUser(captured.capture());
        assertEquals("alice", captured.getValue().getUsername());
        assertEquals("a@example.com", captured.getValue().getEmail());
        assertEquals(" secret ", captured.getValue().getPassword());
        assertNull(captured.getValue().getId());
        assertNull(captured.getValue().getCreatedAt());
    }

    @Test
    void validAnonymousEssayRequestsStillReturn401() throws Exception {
        for (String path : List.of("/api/essays", "/api/essays/evaluate")) {
            assertError(mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"Discuss transport\",\"content\":\"My essay\",\"essayType\":\"TASK_2\"}")),
                    401, "Not logged in");
        }
        assertError(mvc.perform(post("/api/essays/21/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Explain\"}")), 401, "Not logged in");
        verifyNoInteractions(essayService, messageService);
    }

    private ResultActions assertError(ResultActions result, int code, String message) throws Exception {
        return result.andExpect(status().is(code))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(code))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.timestamp").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("AUTH_USER_ID", 7L);
        return session;
    }
}
