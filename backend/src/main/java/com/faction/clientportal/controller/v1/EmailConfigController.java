package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.EmailConfigDto;
import com.faction.clientportal.dto.TestEmailRequest;
import com.faction.clientportal.dto.TestSsoResponse;
import com.faction.clientportal.dto.UpdateEmailConfigRequest;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.service.EmailConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/email-config")
@RequiredArgsConstructor
@Tag(name = "Email Configuration")
public class EmailConfigController {

    private final EmailConfigService emailConfigService;

    private static final String ADMIN_AUTH = "hasAuthority('super_admin')";

    @GetMapping
    @PreAuthorize(ADMIN_AUTH)
    @Operation(summary = "Get SMTP email configuration")
    public ResponseEntity<JsonApiResponse<EmailConfigDto>> getConfig() {
        return ResponseEntity.ok(JsonApiResponse.success(emailConfigService.getConfig()));
    }

    @PutMapping
    @PreAuthorize(ADMIN_AUTH)
    @Operation(summary = "Update SMTP email configuration")
    public ResponseEntity<JsonApiResponse<EmailConfigDto>> updateConfig(
            @RequestBody UpdateEmailConfigRequest request) {
        return ResponseEntity.ok(JsonApiResponse.success(emailConfigService.updateConfig(request)));
    }

    @PostMapping("/test")
    @PreAuthorize(ADMIN_AUTH)
    @Operation(summary = "Test SMTP connection and optionally send a test email")
    public ResponseEntity<JsonApiResponse<TestSsoResponse>> test(
            @RequestBody TestEmailRequest request) {
        return ResponseEntity.ok(JsonApiResponse.success(emailConfigService.testConnection(request)));
    }
}
