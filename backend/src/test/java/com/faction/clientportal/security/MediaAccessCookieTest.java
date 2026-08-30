package com.faction.clientportal.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cookie's whole safety argument rests on it being usable for nothing but
 * reading media, so the eligibility rule is what these tests pin down.
 */
class MediaAccessCookieTest {

    private final MediaAccessCookie cookie = new MediaAccessCookie();

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    @Test
    @DisplayName("the read-only media paths accept the cookie")
    void eligiblePaths() {
        assertThat(cookie.isEligible(request("GET", "/api/v1/inline-images/abc123"))).isTrue();
        assertThat(cookie.isEligible(request("GET", "/api/v1/profile-images/img-1"))).isTrue();
        assertThat(cookie.isEligible(request("GET", "/api/v1/assessments/a1/files/f1/content"))).isTrue();
        assertThat(cookie.isEligible(
                request("GET", "/api/v1/assessments/a1/vulnerabilities/v1/exception-files/f1/content"))).isTrue();
        assertThat(cookie.isEligible(request("GET", "/api/v1/notebook/nodes/n1/files/f1/content"))).isTrue();
        assertThat(cookie.isEligible(request("GET", "/api/v1/reports/a1/documents/DOCX/content"))).isTrue();
    }

    @Test
    @DisplayName("a non-GET method is never eligible, even on an allowlisted path")
    void mutatingMethodsRejected() {
        assertThat(cookie.isEligible(request("PUT", "/api/v1/assessments/a1/files/f1/content"))).isFalse();
        assertThat(cookie.isEligible(request("POST", "/api/v1/assessments/a1/files/f1/content"))).isFalse();
        assertThat(cookie.isEligible(request("DELETE", "/api/v1/inline-images/abc123"))).isFalse();
    }

    @Test
    @DisplayName("ordinary API endpoints never accept the cookie")
    void otherPathsRejected() {
        assertThat(cookie.isEligible(request("GET", "/api/v1/assessments"))).isFalse();
        assertThat(cookie.isEligible(request("GET", "/api/v1/users"))).isFalse();
        assertThat(cookie.isEligible(request("GET", "/api/v1/users/avatars"))).isFalse();
        assertThat(cookie.isEligible(request("GET", "/api/v1/admin/logs/ai"))).isFalse();
    }

    @Test
    @DisplayName("the patterns are anchored, so no prefix or suffix widens them")
    void patternsAreAnchored() {
        // A crafted path must not slip past by appending or prepending segments.
        assertThat(cookie.isEligible(request("GET", "/api/v1/inline-images/abc/../../users"))).isFalse();
        assertThat(cookie.isEligible(request("GET", "/api/v1/inline-images/abc/extra"))).isFalse();
        assertThat(cookie.isEligible(request("GET", "/prefix/api/v1/inline-images/abc"))).isFalse();
        assertThat(cookie.isEligible(request("GET", "/api/v1/assessments/a1/files/f1/content/more"))).isFalse();
    }

    @Test
    @DisplayName("the cookie is httpOnly, SameSite=Strict, and scoped to the API")
    void cookieAttributes() {
        ReflectionTestUtils.setField(cookie, "jwtExpirationMs", 86_400_000L);

        var issued = cookie.issue("the-token", request("POST", "/api/v1/auth/login"));

        assertThat(issued.isHttpOnly()).isTrue();
        assertThat(issued.getSameSite()).isEqualTo("Strict");
        assertThat(issued.getPath()).isEqualTo("/api/v1");
        assertThat(issued.getValue()).isEqualTo("the-token");
    }

    @Test
    @DisplayName("Secure is set from the forwarded scheme, since TLS ends at the proxy")
    void secureFlagFollowsForwardedProto() {
        ReflectionTestUtils.setField(cookie, "jwtExpirationMs", 86_400_000L);

        MockHttpServletRequest proxied = request("POST", "/api/v1/auth/login");
        proxied.addHeader("X-Forwarded-Proto", "https");
        assertThat(cookie.issue("t", proxied).isSecure()).isTrue();

        // Plain HTTP local development must still be able to set the cookie.
        assertThat(cookie.issue("t", request("POST", "/api/v1/auth/login")).isSecure()).isFalse();
    }

    @Test
    @DisplayName("clearing emits an immediately-expiring cookie")
    void clearExpiresImmediately() {
        ReflectionTestUtils.setField(cookie, "jwtExpirationMs", 86_400_000L);

        var cleared = cookie.clear(request("POST", "/api/v1/auth/logout"));

        assertThat(cleared.getValue()).isEmpty();
        assertThat(cleared.getMaxAge()).isZero();
    }
}
