package com.faction.clientportal.security;

import com.faction.clientportal.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

/**
 * Authenticates API keys presented as {@code Authorization: Bearer sk_fac_<random>}.
 *
 * <p>Registered <em>before</em> {@link JwtAuthenticationFilter}. It only handles bearer values with
 * the {@code sk_fac_} prefix; anything else (i.e. a JWT) is left untouched for the JWT filter. On a
 * valid key it populates the {@code SecurityContext} with the same {@link UsernamePasswordAuthenticationToken}
 * shape the JWT filter uses, so all downstream {@code @PreAuthorize} / service scope checks work
 * unchanged. The authenticating key's id is stashed as a request attribute for audit logging.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    /** Request attribute holding the id of the API key that authenticated the request, if any. */
    public static final String API_KEY_ID_ATTRIBUTE = "apiKeyId";

    private final ApiKeyService apiKeyService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        try {
            String token = getBearerToken(request);
            if (token != null
                    && token.startsWith(ApiKeyService.KEY_PREFIX)
                    && SecurityContextHolder.getContext().getAuthentication() == null) {

                apiKeyService.authenticate(token).ifPresent(result -> {
                    List<SimpleGrantedAuthority> authorities = result.authorities().stream()
                            .map(SimpleGrantedAuthority::new)
                            .collect(Collectors.toList());

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(result.principal(), null, authorities);
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    request.setAttribute(API_KEY_ID_ATTRIBUTE, result.keyId());
                    log.debug("Authenticated request via API key {} as {}", result.keyId(), result.principal());
                });
            }
        } catch (Exception ex) {
            log.error("Could not authenticate API key", ex);
        }

        filterChain.doFilter(request, response);
    }

    private String getBearerToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
