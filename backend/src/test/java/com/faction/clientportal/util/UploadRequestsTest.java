package com.faction.clientportal.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Nothing downstream counts the bytes of a streamed upload, so these two checks
 * are the only thing standing between the request and an unbounded write.
 */
class UploadRequestsTest {

    private static final long MAX = 1_000L;

    private UploadRequests uploadRequests;

    @BeforeEach
    void setUp() {
        uploadRequests = new UploadRequests();
        ReflectionTestUtils.setField(uploadRequests, "maxUploadBytes", MAX);
    }

    private static MockHttpServletRequest requestOfLength(int length) {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/x/content");
        request.setContent(new byte[length]);
        return request;
    }

    @Test
    @DisplayName("a declared length within the ceiling passes through")
    void acceptsDeclaredLength() {
        assertThat(uploadRequests.contentLength(requestOfLength(512))).isEqualTo(512L);
    }

    @Test
    @DisplayName("a body at exactly the ceiling is still allowed")
    void acceptsBodyAtLimit() {
        assertThat(uploadRequests.contentLength(requestOfLength((int) MAX))).isEqualTo(MAX);
    }

    @Test
    @DisplayName("an oversized body is refused with 413")
    void rejectsOversizedBody() {
        assertThatThrownBy(() -> uploadRequests.contentLength(requestOfLength((int) MAX + 1)))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    @DisplayName("an undeclared length is refused rather than buffered to discover it")
    void rejectsUnknownLength() {
        MockHttpServletRequest chunked = new MockHttpServletRequest("PUT", "/api/v1/x/content");
        // No content set — getContentLengthLong() reports -1, as it does for a
        // chunked request.
        assertThat(chunked.getContentLengthLong()).isEqualTo(-1L);

        assertThatThrownBy(() -> uploadRequests.contentLength(chunked))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a missing content type falls back to octet-stream")
    void contentTypeFallsBack() {
        MockHttpServletRequest request = requestOfLength(4);
        assertThat(uploadRequests.contentType(request)).isEqualTo("application/octet-stream");

        request.setContentType("application/pdf");
        assertThat(uploadRequests.contentType(request)).isEqualTo("application/pdf");
    }
}
