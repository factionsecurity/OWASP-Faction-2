package com.faction.clientportal.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Carries the caller's JWT to browser-initiated GETs that cannot set an
 * {@code Authorization} header: {@code <img src>} for inline and profile images,
 * and {@code <a href>} for file downloads.
 *
 * <p><strong>Why a cookie rather than a URL token.</strong> The alternative is a
 * signed token in the query string, which is a bearer credential written into
 * nginx access logs, browser history, and anything that forwards the link. These
 * files are pentest reports and screenshots of findings, so a credential that
 * leaks into logs is a finding in its own right. A cookie is never written to
 * the URL, and because it is re-presented on every request the endpoint runs the
 * full permission check each time — access revocation takes effect immediately
 * instead of after a presigned URL's lifetime.
 *
 * <p><strong>Why this is not a CSRF hole.</strong> {@link #isEligible} restricts
 * the cookie to GET on the explicitly enumerated read-only paths below. Every
 * state-changing endpoint remains header-only, so an attacker's cross-site
 * request carries no usable credential even if the browser attaches the cookie.
 * {@code SameSite=Strict} is a second layer, not the only one. Keep the list
 * exhaustive and read-only — adding a mutating path here would create the very
 * hole the restriction exists to prevent.
 */
@Component
public class MediaAccessCookie {

    public static final String NAME = "media_access";

    /**
     * Read-only paths that may authenticate via the cookie. Anchored and
     * segment-bounded so a prefix match can't be widened by a crafted path.
     */
    private static final List<Pattern> ELIGIBLE_PATHS = List.of(
            Pattern.compile("^/api/v1/inline-images/[^/]+$"),
            Pattern.compile("^/api/v1/profile-images/[^/]+$"),
            Pattern.compile("^/api/v1/assessments/[^/]+/files/[^/]+/content$"),
            Pattern.compile("^/api/v1/assessments/[^/]+/vulnerabilities/[^/]+/exception-files/[^/]+/content$"),
            Pattern.compile("^/api/v1/notebook/nodes/[^/]+/files/[^/]+/content$"),
            Pattern.compile("^/api/v1/reports/[^/]+/documents/[^/]+/content$"));

    @Value("${jwt.expiration:86400000}")
    private long jwtExpirationMs;

    /**
     * Whether this request may authenticate from the cookie rather than the
     * Authorization header.
     */
    public boolean isEligible(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        return ELIGIBLE_PATHS.stream().anyMatch(p -> p.matcher(path).matches());
    }

    /** Cookie issued alongside a successful login. */
    public ResponseCookie issue(String token, HttpServletRequest request) {
        return base(request)
                .value(token)
                .maxAge(Duration.ofMillis(jwtExpirationMs))
                .build();
    }

    /** Empty, immediately-expiring cookie that clears a previously issued one. */
    public ResponseCookie clear(HttpServletRequest request) {
        return base(request).value("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(HttpServletRequest request) {
        return ResponseCookie.from(NAME)
                .httpOnly(true)
                .secure(isHttps(request))
                .sameSite("Strict")
                // Scoped to the API so it is never attached to SPA document loads
                // or static asset requests.
                .path("/api/v1");
    }

    /**
     * TLS terminates at nginx in production, so the forwarded scheme is what says
     * whether the browser's leg of the connection was encrypted. Deriving the
     * flag instead of hard-coding it keeps plain-HTTP local development working
     * without leaving a "secure=false" default that would ship to production.
     */
    private boolean isHttps(HttpServletRequest request) {
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        if (forwardedProto != null) {
            return "https".equalsIgnoreCase(forwardedProto);
        }
        return request.isSecure();
    }
}
