package com.ieltsai.ieltsaibackend.interceptor;

import com.ieltsai.ieltsaibackend.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.ObjectMapper;

@Component
public class LoginInterceptor implements HandlerInterceptor {
    public static final String AUTH_USER_ID_SESSION_KEY = "AUTH_USER_ID";

    private final ObjectMapper objectMapper;

    public LoginInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (isPublicEndpoint(request)) {
            return true;
        }

        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(AUTH_USER_ID_SESSION_KEY) instanceof Long) {
            return true;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream().write(
                objectMapper.writeValueAsBytes(ApiErrorResponse.of(401, "Not logged in"))
        );
        return false;
    }

    private boolean isPublicEndpoint(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return HttpMethod.GET.matches(request.getMethod()) && "/api/auth/captcha".equals(path)
                || HttpMethod.POST.matches(request.getMethod()) && "/api/auth/login".equals(path)
                || HttpMethod.POST.matches(request.getMethod()) && "/api/users/register".equals(path);
    }
}
