package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.model.PasswordPolicy;
import com.faction.clientportal.security.AuthenticatedOnly;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.service.PasswordPolicyService;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/config/password-policy")
@RequiredArgsConstructor
@Tag(name = "Password Policy", description = "Password and sign-in rules for this installation")
@SecurityRequirement(name = "bearerAuth")
public class PasswordPolicyController {

    private final PasswordPolicyService service;

    @GetMapping
    @AuthenticatedOnly
    @Operation(
        summary = "Get the password policy",
        description = "Readable by any signed-in user, because every password field has to show "
                + "the rules it is about to enforce. It describes requirements, not secrets.")
    public ResponseEntity<JsonApiResponse<PasswordPolicy>> getPolicy() {
        return ResponseUtil.success("Password policy retrieved successfully", service.getPolicy());
    }

    @PutMapping
    @RequiresPermission(Permission.CONFIG_WRITE)
    @Operation(
        summary = "Update the password policy",
        description = "Replaces the installation's password and sign-in rules. A lockout duration "
                + "of 0 means the account stays locked until an administrator re-enables it; any "
                + "other value is a cooldown that lifts itself. A failed-attempt limit of 0 "
                + "switches lockout off entirely.",
        responses = {
            @ApiResponse(responseCode = "200", description = "The saved policy"),
            @ApiResponse(responseCode = "400", description = "A value outside its allowed range"),
            @ApiResponse(responseCode = "403", description = "Forbidden - requires config:write")
        }
    )
    public ResponseEntity<JsonApiResponse<PasswordPolicy>> updatePolicy(
            @RequestBody PasswordPolicy policy) {
        return ResponseUtil.success("Password policy updated successfully",
                service.updatePolicy(policy));
    }
}
