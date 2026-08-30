package com.faction.clientportal.config;

import com.faction.clientportal.security.ApiKeyAuthenticationFilter;
import com.faction.clientportal.security.JwtAuthenticationFilter;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.security.RequiresPermissionAuthorizationManager;
import lombok.RequiredArgsConstructor;
import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.Pointcuts;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import jakarta.servlet.DispatcherType;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;
    private final CorsConfigurationSource corsConfigurationSource;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // SSE endpoints return an SseEmitter, so the container re-dispatches
                        // the request as ASYNC when the response completes. Since Spring
                        // Security 5.7 the authorization filter runs on every dispatcher
                        // type, and this app is STATELESS — there is no session to restore
                        // the SecurityContext from, so the async continuation authenticates
                        // as anonymous and is denied. The result was an AuthorizationDenied
                        // stack trace, plus "response is already committed" because the SSE
                        // headers had long since been flushed, on every stream teardown.
                        //
                        // Scoped to ASYNC only, deliberately: FORWARD/INCLUDE/ERROR stay
                        // authorized. An ASYNC dispatch is the continuation of a request
                        // that this same filter already authorized on its REQUEST dispatch.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // Unsubscribing must not require a login — the token in the
                        // email is the authority, and demanding a sign-in to stop
                        // receiving mail is how people mark messages as spam instead.
                        .requestMatchers(HttpMethod.POST, "/api/v1/email/unsubscribe").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/status").permitAll()
                        // The sign-in page paints its logo and background before anyone
                        // has a session, so branding reads cannot require a token. Writes
                        // stay admin-only via @PreAuthorize on the controller.
                        .requestMatchers(HttpMethod.GET, "/api/v1/branding", "/api/v1/branding/assets/*").permitAll()
                        // Inline images (screenshots of findings) and profile images
                        // are NOT public. They were served on the strength of an
                        // unguessable id alone, which put finding evidence behind
                        // nothing but URL secrecy. They now authenticate like any
                        // other endpoint; because <img src> cannot send an
                        // Authorization header, the browser presents the media
                        // cookie instead — see MediaAccessCookie.
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(apiKeyAuthenticationFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Enforces {@link RequiresPermission} on controller methods — the type-safe
     * replacement for string-based @PreAuthorize. super_admin is implied by the
     * manager, so endpoints never list it.
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor requiresPermissionAuthorization() {
        Pointcut pointcut = Pointcuts.union(
                new AnnotationMatchingPointcut(null, RequiresPermission.class, true),
                new AnnotationMatchingPointcut(RequiresPermission.class, true));
        AuthorizationManagerBeforeMethodInterceptor interceptor =
                new AuthorizationManagerBeforeMethodInterceptor(
                        pointcut, new RequiresPermissionAuthorizationManager());
        interceptor.setOrder(AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder() + 1);
        return interceptor;
    }

    @Bean
    public HttpFirewall httpFirewall() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowSemicolon(true);
        return firewall;
    }

}
