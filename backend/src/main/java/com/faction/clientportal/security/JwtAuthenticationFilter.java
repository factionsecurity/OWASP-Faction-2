package com.faction.clientportal.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final MediaAccessCookie mediaAccessCookie;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        try {
            String jwt = getJwtFromRequest(request);

            if (StringUtils.hasText(jwt)
                    && SecurityContextHolder.getContext().getAuthentication() == null
                    && jwtTokenProvider.validateToken(jwt)) {
                String username = jwtTokenProvider.getUsername(jwt);
                List<String> authorities = jwtTokenProvider.getAuthorities(jwt);

                List<SimpleGrantedAuthority> grantedAuthorities = authorities.stream()
                        .map(SimpleGrantedAuthority::new)
                        .collect(Collectors.toList());

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(username, null, grantedAuthorities);
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.debug("Set authentication for user: {}", username);

                issueMediaCookieIfMissing(request, response, jwt);
            }
        } catch (Exception ex) {
            log.error("Could not set user authentication in security context", ex);
        }

        filterChain.doFilter(request, response);
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        // Fall back to the media cookie only for the read-only image/download
        // endpoints, which the browser requests via <img src> and <a href> and
        // therefore cannot give an Authorization header. Restricting it to those
        // paths keeps every state-changing endpoint header-only, so the cookie
        // cannot be replayed cross-site. See MediaAccessCookie.
        if (mediaAccessCookie.isEligible(request)) {
            return getMediaCookieValue(request);
        }
        return null;
    }

    /**
     * Top up the media cookie for a session that authenticated by header but has
     * no cookie yet.
     *
     * <p>Without this, a user holding a valid token from before the cookie
     * existed — or whose cookie expired first — would see every inline image and
     * download 403 until they signed in again. It carries the same token the
     * caller just presented, so it grants nothing they did not already have.
     */
    private void issueMediaCookieIfMissing(
            HttpServletRequest request, HttpServletResponse response, String jwt) {
        if (response.isCommitted() || getMediaCookieValue(request) != null) {
            return;
        }
        response.addHeader(HttpHeaders.SET_COOKIE,
                mediaAccessCookie.issue(jwt, request).toString());
    }

    private String getMediaCookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (MediaAccessCookie.NAME.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
