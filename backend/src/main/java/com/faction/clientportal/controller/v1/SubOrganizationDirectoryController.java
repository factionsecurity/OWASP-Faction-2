package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.SubOrganizationDto;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.service.SubOrganizationService;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Cross-organization directory of sub-organizations. The nested
 * {@code /organizations/{id}/sub-organizations} endpoints stay the place to manage a single
 * organization's divisions; this one answers "which organization owns the division called X?",
 * which importers and pickers need before they have an organization in hand.
 *
 * <p>Read-only and scoped to the organizations the caller can already see.
 */
@RestController
@RequestMapping("/api/v1/sub-organizations")
@RequiredArgsConstructor
@Tag(name = "Sub-Organizations", description = "Divisions within an organization")
@SecurityRequirement(name = "bearerAuth")
public class SubOrganizationDirectoryController {

    private final SubOrganizationService subOrganizationService;

    @GetMapping
    @RequiresPermission({Permission.ORGANIZATIONS_READ_ALL, Permission.ORGANIZATIONS_READ_OWNED,
            Permission.ORGANIZATIONS_READ_ORG})
    @Operation(
        summary = "List sub-organizations across organizations",
        description = "Returns every division the caller can see, each with its owning organization "
                + "id and name plus the number of applications attributed to it. Pass `name` to "
                + "look a division up by name — names are unique per organization, so a name shared "
                + "by two organizations returns both.",
        parameters = @Parameter(name = "name", description = "Exact division name, case-insensitive",
                example = "Payments"),
        responses = @ApiResponse(responseCode = "200", description = "Sub-organizations retrieved successfully")
    )
    public ResponseEntity<JsonApiResponse<List<SubOrganizationDto>>> list(
            @RequestParam(required = false) String name,
            Authentication authentication) {
        return ResponseUtil.success("Sub-organizations retrieved successfully",
                subOrganizationService.listAll(name, authentication));
    }
}
