package com.faction.clientportal.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Configuration
public class CorsConfig {

    @Value("${app.frontend-url:}")
    private String frontendUrl;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        // SSO callback endpoints must accept POSTs from any IdP origin
        CorsConfiguration ssoCallbacks = new CorsConfiguration();
        ssoCallbacks.setAllowedOriginPatterns(List.of("*"));
        ssoCallbacks.setAllowedMethods(Arrays.asList("GET", "POST", "OPTIONS"));
        ssoCallbacks.setAllowedHeaders(List.of("*"));
        ssoCallbacks.setAllowCredentials(false);
        source.registerCorsConfiguration("/api/v1/auth/saml/**", ssoCallbacks);
        source.registerCorsConfiguration("/api/v1/auth/oidc/**", ssoCallbacks);

        // All other endpoints: restrict to known frontend origins
        List<String> allowedOrigins = new ArrayList<>(Arrays.asList(
            "http://localhost:3000",
            "http://localhost:3001",
            "http://localhost:5173",
            "http://127.0.0.1:3000",
            "http://127.0.0.1:5173",
            "http://192.168.1.109:3000",
            "http://192.168.1.109"
        ));
        if (frontendUrl != null && !frontendUrl.isBlank()) {
            allowedOrigins.add(frontendUrl);
        }
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(Arrays.asList(
            "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
        ));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        configuration.setExposedHeaders(Arrays.asList("Authorization"));
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }
}
