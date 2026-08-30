package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.LoginRequest;
import com.faction.clientportal.dto.LoginResponse;
import com.faction.clientportal.model.User;
import com.faction.clientportal.service.AuthService;
import com.faction.clientportal.service.JwtService;
import com.faction.clientportal.service.PasswordResetService;
import com.faction.clientportal.security.MediaAccessCookie;
import com.faction.clientportal.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Authentication endpoints")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;
    private final JwtService jwtService;
    private final PasswordResetService passwordResetService;
    private final MediaAccessCookie mediaAccessCookie;

    @PostMapping("/login")
    @Operation(
            summary = "User login",
            description = "Authenticate user and return JWT token with authorities",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Login successful",
                            content = @Content(schema = @Schema(implementation = LoginResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "Invalid credentials"
                    )
            }
    )
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest loginRequest,
                                               HttpServletRequest servletRequest) {
        String token = authService.login(loginRequest.getUsername(), loginRequest.getPassword());

        User user = userService.findByUsername(loginRequest.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<String> authorities = authService.getPermissions(user);

        LoginResponse response = LoginResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresIn(jwtService.getExpirationTime())
                .userId(user.getId())
                .username(user.getUsername())
                .authorities(authorities)
                .roles(authService.getRoleNames(user))
                .isInternal(user.getIsInternal())
                .build();

        // The same token as a cookie, so <img src> and <a href> can authenticate
        // against the image and file-download endpoints. It is accepted on those
        // read-only paths only — see MediaAccessCookie.
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, mediaAccessCookie.issue(token, servletRequest).toString())
                .body(response);
    }

    @PostMapping("/logout")
    @Operation(summary = "Log out",
               description = "Clears the media access cookie. The JWT itself is stateless, so the "
                       + "client must also discard its copy of the token.")
    public ResponseEntity<Map<String, String>> logout(HttpServletRequest servletRequest) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, mediaAccessCookie.clear(servletRequest).toString())
                .body(Map.of("message", "Logged out."));
    }

    @PostMapping("/forgot-password")
    @Operation(summary = "Request password reset",
               description = "Sends a password reset link to the given email if an account exists. Always returns 200 to avoid revealing whether the email is registered.")
    public ResponseEntity<Map<String, String>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestReset(request.getEmail());
        // Always return success to avoid revealing whether the email exists
        return ResponseEntity.ok(Map.of("message",
                "If that email is associated with an account, a reset link has been sent."));
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Reset password",
               description = "Sets a new password using a reset token from the password reset email. Returns 400 if the token is invalid or expired.")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        try {
            passwordResetService.resetPassword(request.getToken(), request.getNewPassword());
            return ResponseEntity.ok(Map.of("message", "Password updated successfully."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/me")
    @Operation(summary = "Get current principal",
               description = "Returns the authenticated principal's username, effective authorities, and user id (omitted for system API-key principals).")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<?> me(Authentication authentication) {
        // Authorities come from the Authentication — the single source of truth resolved once at
        // authentication time (a JWT carries its baked authorities; an API key carries the live,
        // scope-filtered set the ApiKeyAuthenticationFilter resolved). Re-deriving them from the
        // user here would duplicate that logic and, for a READ_ONLY key, wrongly advertise the
        // owner's mutating permissions that the key was filtered to exclude.
        List<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        String username = authentication.getName();

        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("authorities", authorities);
        // The user id isn't carried on the Authentication, so look it up — but only real user
        // principals (JWT / user keys) resolve. A system-key principal ("system:<name>") has no
        // user row, so "id" is omitted entirely rather than returned as null (or 500-ing).
        userService.findByUsername(username).ifPresent(user -> {
            body.put("id", user.getId());
            body.put("roles", authService.getRoleNames(user));
            body.put("isInternal", Boolean.TRUE.equals(user.getIsInternal()));
        });
        return ResponseEntity.ok(body);
    }

    @Data
    static class ForgotPasswordRequest {
        @NotBlank @Email
        private String email;
    }

    @Data
    static class ResetPasswordRequest {
        @NotBlank
        private String token;
        @NotBlank @Size(min = 8, message = "Password must be at least 8 characters")
        private String newPassword;
    }
}
