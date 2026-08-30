package com.faction.clientportal.controller.v1;

import com.faction.clientportal.security.AuthenticatedOnly;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.service.RegionConfigService;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/config/regions")
@RequiredArgsConstructor
@Tag(name = "Region Config", description = "Manage application regions")
@SecurityRequirement(name = "bearerAuth")
public class RegionConfigController {

    private final RegionConfigService regionConfigService;

    @GetMapping
    @AuthenticatedOnly
    @Operation(summary = "Get regions",
               description = "Retrieve the configured list of application regions.")
    public ResponseEntity<JsonApiResponse<List<String>>> getRegions() {
        return ResponseUtil.success("Regions retrieved successfully", regionConfigService.getRegions());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('super_admin')")
    @Operation(summary = "Update regions",
               description = "Replace the configured list of application regions. Only accessible to Super Admins.")
    public ResponseEntity<JsonApiResponse<List<String>>> updateRegions(@RequestBody List<String> regions) {
        return ResponseUtil.success("Regions updated successfully", regionConfigService.updateRegions(regions));
    }
}
