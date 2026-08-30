package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.NotificationPreferenceDto;
import com.faction.clientportal.dto.UpdateNotificationPreferencesRequest;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.security.AuthenticatedOnly;
import com.faction.clientportal.service.NotificationPreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * A user's own notification settings. Self-service only — the username comes from the
 * authenticated principal and is never taken from the request, so nobody can read or
 * change someone else's preferences.
 */
@RestController
@RequestMapping("/api/v1/users/me/notification-preferences")
@RequiredArgsConstructor
@Tag(name = "Notification Preferences")
@AuthenticatedOnly
public class NotificationPreferenceController {

    private final NotificationPreferenceService preferenceService;

    @GetMapping
    @Operation(summary = "Get the current user's notification preferences")
    public ResponseEntity<JsonApiResponse<List<NotificationPreferenceDto>>> get(Authentication authentication) {
        return ResponseEntity.ok(JsonApiResponse.success(
                preferenceService.getForUser(authentication.getName())));
    }

    @PutMapping
    @Operation(summary = "Update the current user's notification preferences")
    public ResponseEntity<JsonApiResponse<List<NotificationPreferenceDto>>> update(
            Authentication authentication,
            @RequestBody UpdateNotificationPreferencesRequest request) {
        return ResponseEntity.ok(JsonApiResponse.success(
                preferenceService.update(authentication.getName(), request)));
    }
}
