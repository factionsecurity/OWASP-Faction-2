package com.faction.clientportal.exception;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A client dropping an SSE stream — closing the tab, sleeping the laptop — is routine, and
 * must not be logged as a server error.
 *
 * <p>It reached the catch-all handler only because {@code @ExceptionHandler(Exception.class)}
 * shadows Spring's own handling of this exception, so the specific handler has to exist.
 * These assertions pin the two properties that make it work, both of which are easy to
 * undo by accident.
 */
class GlobalExceptionHandlerDisconnectTest {

    private Method disconnectHandler() {
        return Arrays.stream(GlobalExceptionHandler.class.getDeclaredMethods())
                .filter(m -> {
                    ExceptionHandler annotation = m.getAnnotation(ExceptionHandler.class);
                    return annotation != null
                            && Arrays.asList(annotation.value()).contains(AsyncRequestNotUsableException.class);
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No @ExceptionHandler for AsyncRequestNotUsableException — a disconnected "
                        + "SSE client will be logged as an unexpected server error again"));
    }

    @Test
    void aDisconnectedClientIsHandledSpecifically() {
        assertThat(disconnectHandler()).isNotNull();
    }

    @Test
    void theHandlerWritesNoBody() {
        // Must return void. The socket is gone, and an SSE response already has
        // Content-Type: text/event-stream — there is no converter that will serialise an
        // ErrorResponse into that, so returning a body throws a second time.
        assertThat(disconnectHandler().getReturnType())
                .as("handler must return void so Spring leaves the response untouched")
                .isEqualTo(void.class);
    }

    @Test
    void theCatchAllStillExistsForRealFailures() {
        // Narrowing this exception must not have removed the general safety net.
        boolean hasCatchAll = Arrays.stream(GlobalExceptionHandler.class.getDeclaredMethods())
                .anyMatch(m -> {
                    ExceptionHandler annotation = m.getAnnotation(ExceptionHandler.class);
                    return annotation != null
                            && Arrays.asList(annotation.value()).contains(Exception.class);
                });
        assertThat(hasCatchAll).isTrue();
    }
}
