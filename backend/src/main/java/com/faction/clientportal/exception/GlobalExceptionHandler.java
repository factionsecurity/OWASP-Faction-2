package com.faction.clientportal.exception;

import com.faction.clientportal.dto.ErrorResponse;
import com.faction.clientportal.dto.UpgradeRequiredResponse;
import com.faction.clientportal.edition.EditionStatusService;
import com.faction.clientportal.edition.FeatureNotLicensedException;
import com.faction.clientportal.edition.QuotaExceededException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final EditionStatusService editionStatusService;

    public GlobalExceptionHandler(EditionStatusService editionStatusService) {
        this.editionStatusService = editionStatusService;
    }

    /**
     * A paid capability was used in the open source edition.
     *
     * <p>402 rather than 403 on purpose: 403 means the caller lacks permission and sends
     * an operator digging through role configuration, when the real answer is that this
     * build does not contain the feature. Logged at INFO — in the open source edition
     * this is expected traffic, not a fault.
     */
    @ExceptionHandler(FeatureNotLicensedException.class)
    public ResponseEntity<UpgradeRequiredResponse> handleFeatureNotLicensed(
            FeatureNotLicensedException ex,
            HttpServletRequest request) {
        log.info("Feature not licensed: {} at {}", ex.getFeature().getKey(), request.getRequestURI());

        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(
                UpgradeRequiredResponse.builder()
                        .code("FEATURE_NOT_LICENSED")
                        .feature(ex.getFeature().getKey())
                        .message(ex.getMessage())
                        .path(request.getRequestURI())
                        .upgradeUrl(editionStatusService.getUpgradeUrl())
                        .build());
    }

    /** A capped resource is already at its open source limit. Same 402, same prompt. */
    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<UpgradeRequiredResponse> handleQuotaExceeded(
            QuotaExceededException ex,
            HttpServletRequest request) {
        log.info("Quota exceeded: {} (limit {}) at {}",
                ex.getQuota().getKey(), ex.getLimit(), request.getRequestURI());

        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(
                UpgradeRequiredResponse.builder()
                        .code("QUOTA_EXCEEDED")
                        .quota(ex.getQuota().getKey())
                        .limit(ex.getLimit())
                        .message(ex.getMessage())
                        .path(request.getRequestURI())
                        .upgradeUrl(editionStatusService.getUpgradeUrl())
                        .build());
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentialsException(
            BadCredentialsException ex,
            HttpServletRequest request) {
        log.error("Bad credentials: {}", ex.getMessage());

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.UNAUTHORIZED.value())
                .error("Unauthorized")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(
            AuthenticationException ex,
            HttpServletRequest request) {
        log.error("Authentication error: {}", ex.getMessage());

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.UNAUTHORIZED.value())
                .error("Unauthorized")
                .message("Authentication failed")
                .path(request.getRequestURI())
                .build();

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentialsException(
            InvalidCredentialsException ex,
            HttpServletRequest request) {
        log.error("Invalid credentials: {}", ex.getMessage());

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.UNAUTHORIZED.value())
                .error("Unauthorized")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAuthorizationDeniedException(
            AuthorizationDeniedException ex,
            HttpServletRequest request) {
        log.error("Authorization denied: {}", ex.getMessage());

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.FORBIDDEN.value())
                .error("Forbidden")
                .message("Access denied")
                .path(request.getRequestURI())
                .build();

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(
            AccessDeniedException ex,
            HttpServletRequest request) {
        log.error("Access denied: {}", ex.getMessage());

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.FORBIDDEN.value())
                .error("Forbidden")
                .message("Access denied")
                .path(request.getRequestURI())
                .build();

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFoundException(
            ResourceNotFoundException ex,
            HttpServletRequest request) {
        log.error("Resource not found: {}", ex.getMessage());

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.NOT_FOUND.value())
                .error("Not Found")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
    }

    /**
     * Honour the status a {@link ResponseStatusException} was thrown with. Without this the
     * catch-all below turned every one of them into a 500 — so the deliberate 409s (duplicate
     * user assignment, report actions on a completed assessment) and the 422 for unanswered
     * blocking checklists all surfaced as server errors, hiding a clear reason from the client.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatusException(
            ResponseStatusException ex,
            HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        log.warn("Request rejected with {}: {}", status.value(), ex.getReason());

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(ex.getReason() != null ? ex.getReason() : status.getReasonPhrase())
                .path(request.getRequestURI())
                .build();

        return ResponseEntity.status(status).body(errorResponse);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException ex,
            HttpServletRequest request) {
        log.error("Invalid argument: {}", ex.getMessage());

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Bad Request")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        String errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Bad Request")
                .message(errors)
                .path(request.getRequestURI())
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * A client vanished mid-response — almost always an SSE stream whose tab was closed,
     * navigated away from, or put to sleep.
     *
     * <p>Nothing is wrong, and nothing can be done: the socket is gone, so there is nobody
     * to send a response to. Returning {@code void} tells Spring the exception is handled
     * and leaves the response alone. Attempting to write a body here fails a second time —
     * an SSE response already has {@code Content-Type: text/event-stream}, and there is no
     * converter that will serialise an {@link ErrorResponse} into that, which produced a
     * second stack trace immediately after the first.
     *
     * <p>Spring's own {@code DefaultHandlerExceptionResolver} handles this exception exactly
     * this way. It only reached the catch-all below because
     * {@code @ExceptionHandler(Exception.class)} shadows it, so the specific handler has to
     * be declared explicitly.
     *
     * <p>Note the failure is unavoidable at the point it happens: the emitter's own
     * {@code catch} does prune the dead client, but the failed write has already poisoned
     * Tomcat's async context, and Tomcat then error-dispatches independently.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleDisconnectedClient(AsyncRequestNotUsableException ex) {
        log.debug("Client disconnected before the response completed: {}", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception ex,
            HttpServletRequest request) {
        log.error("Unexpected error: ", ex);

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error("Internal Server Error")
                .message("An unexpected error occurred")
                .path(request.getRequestURI())
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }
}
