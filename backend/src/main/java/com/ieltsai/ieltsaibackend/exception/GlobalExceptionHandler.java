package com.ieltsai.ieltsaibackend.exception;

import com.ieltsai.ieltsaibackend.dto.ApiErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Spring chooses the original MVC status; only the error body is replaced.
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers,
        HttpStatusCode status, WebRequest request
    ) {
        if (status.is5xxServerError()) {
            log.error(
                    "event=http_5xx status={} request={}",
                    status.value(), request.getDescription(false), exception
            );
        }
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        // This also allows JSON errors from endpoints whose success response is a PNG.
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        String message = exception instanceof MethodArgumentNotValidException validationException
                ? validationMessage(validationException)
                : messageFor(status);
        ApiErrorResponse response = ApiErrorResponse.of(status.value(), message);
        return super.handleExceptionInternal(exception, response, responseHeaders, status, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(Exception exception, WebRequest request) {
        return handleExceptionInternal(
                exception, null, HttpHeaders.EMPTY, HttpStatus.INTERNAL_SERVER_ERROR, request
        );
    }

    private String validationMessage(MethodArgumentNotValidException exception) {
        // Only use constraint messages, never rejected values (which may include passwords).
        String message = exception.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(error -> Objects.requireNonNullElse(error.getDefaultMessage(), "Invalid request"))
                .distinct()
                .collect(Collectors.joining("; "));
        return message.isEmpty() ? "Invalid request" : message;
    }

    private String messageFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "Invalid request";
            case 401 -> "Not logged in";
            case 404 -> "Resource not found";
            case 429 -> "Too many AI evaluation requests";
            case 500 -> "Internal server error";
            case 502 -> "Bad gateway";
            case 504 -> "Gateway timeout";
            default -> {
                HttpStatus knownStatus = HttpStatus.resolve(status.value());
                yield knownStatus == null ? "Request failed" : knownStatus.getReasonPhrase();
            }
        };
    }
}
