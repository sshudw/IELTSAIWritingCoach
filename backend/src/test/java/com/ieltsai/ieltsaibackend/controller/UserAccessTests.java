package com.ieltsai.ieltsaibackend.controller;

import com.ieltsai.ieltsaibackend.exception.GlobalExceptionHandler;

import com.ieltsai.ieltsaibackend.entity.User;
import com.ieltsai.ieltsaibackend.mapper.UserMapper;
import com.ieltsai.ieltsaibackend.service.AuthService;
import com.ieltsai.ieltsaibackend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserAccessTests {

    private UserMapper userMapper;
    private UserService userService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        userService = mock(UserService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new UserController(userService),
                new AuthController(new AuthService(userMapper))
        ).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void userIdEndpointIsRemovedForAnonymousAndLoggedInUsers() throws Exception {
        mockMvc.perform(get("/api/users/99"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/users/99").session(loggedInSession(7L)))
                .andExpect(status().isNotFound());

        verifyNoInteractions(userMapper, userService);
    }

    @Test
    void currentUserRequiresLoginEvenWhenClientSuppliesUserId() throws Exception {
        mockMvc.perform(get("/api/auth/me").param("userId", "99"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Not logged in"));

        verifyNoInteractions(userMapper, userService);
    }

    @Test
    void captchaSessionDoesNotGrantAccessToUserInformation() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("AUTH_CAPTCHA", "98X1");

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userMapper, userService);
    }

    @Test
    void currentUserComesFromSessionAndOnlyExposesUsernameAndEmail() throws Exception {
        User user = new User();
        user.setId(7L);
        user.setUsername("Alice");
        user.setEmail("alice@example.com");
        user.setPassword("must-not-be-exposed");
        when(userMapper.findById(7L)).thenReturn(user);

        mockMvc.perform(get("/api/auth/me")
                        .session(loggedInSession(7L))
                        .param("userId", "99")
                        .param("id", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("Alice"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist());

        verify(userMapper).findById(7L);
        verifyNoMoreInteractions(userMapper);
        verifyNoInteractions(userService);
    }

    @Test
    void missingSessionUserReturnsUnauthorizedAndClearsStoredIdentity() throws Exception {
        MockHttpSession session = loggedInSession(7L);

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Not logged in"));

        assertNull(session.getAttribute("AUTH_USER_ID"));
        verify(userMapper).findById(7L);
    }

    @Test
    void validLoginStillVerifiesHashedPasswordAndEstablishesSession() throws Exception {
        User user = new User();
        user.setId(7L);
        user.setUsername("Alice");
        user.setEmail("alice@example.com");
        user.setPassword(" secret ");
        // Exercise existing hashing and verification without writing to a real database.
        new UserService(userMapper).registerUser(user);
        assertTrue(user.getPassword().startsWith("pbkdf2_sha256$"));
        when(userMapper.findByUsername("Alice")).thenReturn(user);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("AUTH_CAPTCHA", "ABCD");
        String oldSessionId = session.getId();

        mockMvc.perform(post("/api/auth/login").session(session)
                        .contentType("application/json")
                        .content("{\"username\":\"Alice\",\"password\":\" secret \",\"captcha\":\"abcd\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Login successful"));

        assertEquals(7L, session.getAttribute("AUTH_USER_ID"));
        assertNull(session.getAttribute("AUTH_CAPTCHA"));
        assertNotEquals(oldSessionId, session.getId());
    }

    private static MockHttpSession loggedInSession(Long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("AUTH_USER_ID", userId);
        return session;
    }
}
