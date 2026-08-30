package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.ApiKeyDto;
import com.faction.clientportal.dto.CreateApiKeyRequest;
import com.faction.clientportal.dto.CreateApiKeyResponse;
import com.faction.clientportal.dto.CreateSystemApiKeyRequest;
import com.faction.clientportal.dto.UpdateSystemApiKeyRequest;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.ApiKey;
import com.faction.clientportal.model.User;
import com.faction.clientportal.service.ApiKeyService;
import com.faction.clientportal.service.UserService;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Management of programmatic API keys. Two families of key live here:
 *
 * <ul>
 *   <li><b>User keys</b> ({@code /api/v1/api-keys}) — self-service; every operation is scoped to the
 *       caller's own keys (the caller's username resolves the owner, and the service filters strictly
 *       by that owner). A user key stores no permissions — its authorities are derived live from the
 *       owner on every request, shaped by its {@link com.faction.clientportal.model.ApiKeyScope}:
 *       {@code READ_WRITE} (all of the owner's current permissions, the default) or {@code READ_ONLY}
 *       (the read-action slice of them). Gated by {@code super_admin} or the matching
 *       {@code apikeys:<verb>:self} authority.</li>
 *   <li><b>System keys</b> ({@code /api/v1/api-keys/system}) — ownerless service accounts, managed
 *       globally, always {@code CUSTOM}-scoped: the stored permission list IS the key's authorities.
 *       Because minting/re-scoping one is an unbounded permission grant, <b>create and edit are
 *       {@code super_admin}-only</b>; the non-escalating verbs (list, revoke) are delegable via
 *       {@code apikeys:read:system} / {@code apikeys:delete:system}.</li>
 * </ul>
 *
 * <p>These gates uphold a single invariant: the API-key mechanism can never confer a permission its
 * creator does not already hold (user keys structurally, since a derivation can only pass through or
 * subtract; system keys because only a {@code super_admin}, who already holds everything, may assign
 * their permissions).
 *
 * <p>Beyond the permission gate, external/portal users are always rejected (403) even if they
 * somehow hold an apikeys permission. The user-key endpoints additionally require the caller to
 * resolve to a real internal user — they operate on the caller's own keys, so a non-user principal
 * (e.g. a system key) has nothing to own there. The system-key endpoints require only that the
 * caller is not an external user: a system key granted a delegable {@code :system} verb is a
 * legitimate caller (e.g. security automation revoking compromised keys).
 */
@RestController
@RequestMapping("/api/v1/api-keys")
@RequiredArgsConstructor
@Tag(name = "API Keys", description = "Management of programmatic API keys")
@SecurityRequirement(name = "bearerAuth")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final UserService userService;

    // ── User keys (self-service) ─────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasAnyAuthority('super_admin','apikeys:create:self')")
    @Operation(
            summary = "Create your API key",
            description = "Mint a new API key owned by the authenticated user. Scope is READ_WRITE "
                    + "(all of your live permissions — the default) or READ_ONLY (the read-only slice "
                    + "of them); either way the key's authorities are resolved fresh on every request, "
                    + "so they always track your current access. The plaintext key is returned exactly "
                    + "once in the response and is never stored — save it immediately. External "
                    + "(portal) users cannot create keys.",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Key created; plaintext returned once",
                            content = @Content(schema = @Schema(implementation = CreateApiKeyResponse.class))),
                    @ApiResponse(responseCode = "400", description = "Bad Request - Invalid input (e.g. CUSTOM scope, "
                            + "which is not permitted on user keys)"),
                    @ApiResponse(responseCode = "403", description = "Forbidden - Missing permission or external user"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing credentials")
            }
    )
    public ResponseEntity<JsonApiResponse<CreateApiKeyResponse>> createApiKey(
            @Valid @RequestBody CreateApiKeyRequest request,
            Authentication authentication) {
        User user = requireInternalUser(authentication);

        ApiKeyService.GeneratedApiKey generated =
                apiKeyService.createUserKey(user.getId(), request.getName(), request.getScope());
        return ResponseUtil.created("API key created successfully", toCreateResponse(generated));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('super_admin','apikeys:read:self')")
    @Operation(
            summary = "List your API keys",
            description = "List the authenticated user's active (non-revoked) API keys, newest first. "
                    + "The secret is never included — only non-secret metadata and the display hint.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Keys retrieved",
                            content = @Content(schema = @Schema(implementation = ApiKeyDto.class))),
                    @ApiResponse(responseCode = "403", description = "Forbidden - Missing permission or external user"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing credentials")
            }
    )
    public ResponseEntity<JsonApiResponse<List<ApiKeyDto>>> listApiKeys(Authentication authentication) {
        User user = requireInternalUser(authentication);

        List<ApiKeyDto> keys = apiKeyService.listUserKeys(user.getId()).stream()
                .map(ApiKeyDto::from)
                .toList();

        return ResponseUtil.success("API keys retrieved successfully", keys);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('super_admin','apikeys:delete:self')")
    @Operation(
            summary = "Revoke your API key",
            description = "Revoke one of the authenticated user's own API keys. Revocation is "
                    + "permanent and takes effect immediately.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Key revoked"),
                    @ApiResponse(responseCode = "404", description = "Not Found - No such active key owned by you"),
                    @ApiResponse(responseCode = "403", description = "Forbidden - Missing permission or external user"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing credentials")
            }
    )
    public ResponseEntity<JsonApiResponse<Void>> revokeApiKey(
            @PathVariable String id,
            Authentication authentication) {
        User user = requireInternalUser(authentication);

        if (!apiKeyService.revokeUserKey(user.getId(), id)) {
            throw new ResourceNotFoundException("API key not found");
        }

        return ResponseUtil.success("API key revoked successfully");
    }

    // User keys are deliberately not editable: their only mutable state is a binary scope
    // (READ_WRITE/READ_ONLY) and a cosmetic name. Changing a key's security posture goes through
    // revoke + create, so a scope change always yields a fresh secret (auditable, fail-closed for
    // holders of the old secret) rather than silently re-scoping a live credential in place.

    // ── System keys (service accounts, managed globally) ─────────────────────

    @PostMapping("/system")
    @PreAuthorize("hasAuthority('super_admin')")
    @Operation(
            summary = "Create a system API key",
            description = "Mint an ownerless system (service-account) key with the given permissions. "
                    + "Restricted to super admins: a system key is an unbounded, ownerless credential "
                    + "that can carry any authority, so minting one is an administrative act. The "
                    + "plaintext key is returned exactly once and is never stored. A key created with "
                    + "no permissions authenticates but is authorized for nothing.",
            responses = {
                    @ApiResponse(responseCode = "201", description = "System key created; plaintext returned once",
                            content = @Content(schema = @Schema(implementation = CreateApiKeyResponse.class))),
                    @ApiResponse(responseCode = "400", description = "Bad Request - Invalid input"),
                    @ApiResponse(responseCode = "403", description = "Forbidden - Missing permission or external user"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing credentials")
            }
    )
    public ResponseEntity<JsonApiResponse<CreateApiKeyResponse>> createSystemApiKey(
            @Valid @RequestBody CreateSystemApiKeyRequest request,
            Authentication authentication) {
        requireNotExternalUser(authentication);

        ApiKeyService.GeneratedApiKey generated =
                apiKeyService.createSystemKey(request.getName(), request.getPermissions());
        return ResponseUtil.created("System API key created successfully", toCreateResponse(generated));
    }

    @GetMapping("/system")
    @PreAuthorize("hasAnyAuthority('super_admin','apikeys:read:system')")
    @Operation(
            summary = "List system API keys",
            description = "List all active (non-revoked) system keys, newest first. The secret is "
                    + "never included — only non-secret metadata, assigned permissions, and the hint.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "System keys retrieved",
                            content = @Content(schema = @Schema(implementation = ApiKeyDto.class))),
                    @ApiResponse(responseCode = "403", description = "Forbidden - Missing permission or external user"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing credentials")
            }
    )
    public ResponseEntity<JsonApiResponse<List<ApiKeyDto>>> listSystemApiKeys(Authentication authentication) {
        requireNotExternalUser(authentication);

        List<ApiKeyDto> keys = apiKeyService.listSystemKeys().stream()
                .map(ApiKeyDto::from)
                .toList();

        return ResponseUtil.success("System API keys retrieved successfully", keys);
    }

    @DeleteMapping("/system/{id}")
    @PreAuthorize("hasAnyAuthority('super_admin','apikeys:delete:system')")
    @Operation(
            summary = "Revoke a system API key",
            description = "Revoke a system (service-account) key. Revocation is permanent and takes "
                    + "effect immediately.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "System key revoked"),
                    @ApiResponse(responseCode = "404", description = "Not Found - No such active system key"),
                    @ApiResponse(responseCode = "403", description = "Forbidden - Missing permission or external user"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing credentials")
            }
    )
    public ResponseEntity<JsonApiResponse<Void>> revokeSystemApiKey(
            @PathVariable String id,
            Authentication authentication) {
        requireNotExternalUser(authentication);

        if (!apiKeyService.revokeSystemKey(id)) {
            throw new ResourceNotFoundException("System API key not found");
        }

        return ResponseUtil.success("System API key revoked successfully");
    }

    @PutMapping("/system/{id}")
    @PreAuthorize("hasAuthority('super_admin')")
    @Operation(
            summary = "Update a system API key",
            description = "Rename and/or re-scope a system (service-account) key. Restricted to super "
                    + "admins, since re-scoping can grant any authority. Permissions are unrestricted; "
                    + "an empty list leaves the key inert. Does not change the secret.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "System key updated",
                            content = @Content(schema = @Schema(implementation = ApiKeyDto.class))),
                    @ApiResponse(responseCode = "400", description = "Bad Request - Invalid input"),
                    @ApiResponse(responseCode = "404", description = "Not Found - No such active system key"),
                    @ApiResponse(responseCode = "403", description = "Forbidden - Missing permission or external user"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing credentials")
            }
    )
    public ResponseEntity<JsonApiResponse<ApiKeyDto>> updateSystemApiKey(
            @PathVariable String id,
            @Valid @RequestBody UpdateSystemApiKeyRequest request,
            Authentication authentication) {
        requireNotExternalUser(authentication);

        ApiKey updated = apiKeyService
                .updateSystemKey(id, request.getName(), request.getPermissions())
                .orElseThrow(() -> new ResourceNotFoundException("System API key not found"));

        return ResponseUtil.success("System API key updated successfully", ApiKeyDto.from(updated));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private CreateApiKeyResponse toCreateResponse(ApiKeyService.GeneratedApiKey generated) {
        return CreateApiKeyResponse.builder()
                .key(generated.secret())
                .apiKey(ApiKeyDto.from(generated.apiKey()))
                .build();
    }

    /**
     * Resolve the caller to an internal user, or reject with 403. Used by the user-key endpoints,
     * which operate on the caller's own keys and therefore need a real internal owner: external/
     * portal users are blocked, and so are non-user principals (e.g. system keys, whose synthetic
     * {@code system:<name>} principal is not a username) — a system key has nothing to own here.
     */
    private User requireInternalUser(Authentication authentication) {
        return userService.findByUsername(authentication.getName())
                .filter(u -> Boolean.TRUE.equals(u.getIsInternal()))
                .orElseThrow(() -> new AccessDeniedException("API key management is restricted to internal users"));
    }

    /**
     * Reject principals that resolve to an EXTERNAL user; everything else passes. Used by the
     * system-key endpoints, which need no owner identity: internal users pass, and so do principals
     * that resolve to no user at all — i.e. system-key principals — so a system key granted a
     * delegable {@code :system} verb (e.g. a security-automation service account revoking
     * compromised keys) can actually use it. {@code @PreAuthorize} remains the real permission
     * gate. (The {@code system:} username prefix is reserved by {@code UserService}, so a real
     * user can never collide with a system-key principal.)
     */
    private void requireNotExternalUser(Authentication authentication) {
        userService.findByUsername(authentication.getName())
                .filter(u -> !Boolean.TRUE.equals(u.getIsInternal()))
                .ifPresent(u -> {
                    throw new AccessDeniedException("API key management is restricted to internal users");
                });
    }
}
