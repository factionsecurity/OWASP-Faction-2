package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.EmailNotificationConfigDto;
import com.faction.clientportal.dto.UpdateEmailNotificationConfigRequest;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.service.EmailNotificationConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/email-notification-config")
@RequiredArgsConstructor
@Tag(name = "Email Notification Settings")
public class EmailNotificationConfigController {

    private static final String ADMIN_AUTH = "hasAuthority('super_admin')";

    private final EmailNotificationConfigService service;

    @GetMapping
    @PreAuthorize(ADMIN_AUTH)
    @Operation(summary = "Get which events email which audiences")
    public ResponseEntity<JsonApiResponse<EmailNotificationConfigDto>> getConfig() {
        return ResponseEntity.ok(JsonApiResponse.success(service.getConfig()));
    }

    @PutMapping
    @PreAuthorize(ADMIN_AUTH)
    @Operation(summary = "Update which events email which audiences")
    public ResponseEntity<JsonApiResponse<EmailNotificationConfigDto>> updateConfig(
            @RequestBody UpdateEmailNotificationConfigRequest request) {
        return ResponseEntity.ok(JsonApiResponse.success(service.updateConfig(request)));
    }
}
