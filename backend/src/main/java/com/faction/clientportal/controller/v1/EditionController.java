package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.EditionStatusDto;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.edition.EditionStatusService;
import com.faction.clientportal.security.AuthenticatedOnly;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/edition")
@RequiredArgsConstructor
@Tag(name = "Edition", description = "Which capabilities this build includes")
@SecurityRequirement(name = "bearerAuth")
public class EditionController {

    private final EditionStatusService editionStatusService;

    /**
     * Readable by any signed-in user, not just admins: the diamond badges and quota
     * counters appear throughout the app, so every session needs this to render at all.
     * It discloses nothing an operator could not learn by clicking an upgrade link.
     */
    @GetMapping
    @AuthenticatedOnly
    @Operation(summary = "Get edition status",
               description = "Feature availability, quota limits and current usage for this build.")
    public ResponseEntity<JsonApiResponse<EditionStatusDto>> getEdition() {
        return ResponseUtil.success("Edition status retrieved successfully", editionStatusService.status());
    }
}
