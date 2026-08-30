package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.CampaignDto;
import com.faction.clientportal.util.PageableUtil;
import com.faction.clientportal.util.PageableUtil.SortField;
import com.faction.clientportal.dto.CreateCampaignRequest;
import com.faction.clientportal.dto.UpdateCampaignRequest;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.service.CampaignService;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/campaigns")
@RequiredArgsConstructor
@Tag(name = "Campaigns", description = "Campaign management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class CampaignController {

    private final CampaignService campaignService;

    /** Sortable campaign columns. */
    private static final Map<String, SortField> SORTABLE_FIELDS = Map.of(
            "name", SortField.text("name"),
            "isDefault", SortField.value("isDefault"),
            "createdAt", SortField.value("createdAt"));

    private static final Sort DEFAULT_SORT = Sort.by("name");

    @GetMapping
    @RequiresPermission(Permission.CAMPAIGNS_READ_ALL)
    @Operation(summary = "Get all campaigns",
            description = "Retrieves campaigns with pagination and optional case-insensitive name search")
    public ResponseEntity<JsonApiResponse<List<CampaignDto>>> getAllCampaigns(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "name,asc") String sort,
            @RequestParam(required = false) String search) {
        Pageable pageable = PageableUtil.of(page, size, sort, DEFAULT_SORT, SORTABLE_FIELDS);

        Page<CampaignDto> campaigns = campaignService.searchCampaigns(search, pageable);
        return ResponseUtil.paginated(campaigns);
    }

    @GetMapping("/all")
    @RequiresPermission(Permission.CAMPAIGNS_READ_ALL)
    @Operation(summary = "Get all campaigns (unpaged)",
            description = "Retrieves every campaign, for dropdown selectors")
    public ResponseEntity<JsonApiResponse<List<CampaignDto>>> getAllCampaignsUnpaged() {
        return ResponseUtil.success(campaignService.getAllCampaigns());
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permission.CAMPAIGNS_READ_ALL)
    @Operation(summary = "Get campaign by ID")
    public ResponseEntity<JsonApiResponse<CampaignDto>> getCampaignById(@PathVariable String id) {
        return ResponseUtil.success(campaignService.getCampaignById(id));
    }

    @PostMapping
    @RequiresPermission(Permission.CAMPAIGNS_CREATE_ALL)
    @Operation(summary = "Create campaign")
    public ResponseEntity<JsonApiResponse<CampaignDto>> createCampaign(
            @Valid @RequestBody CreateCampaignRequest request) {
        return ResponseUtil.created(campaignService.createCampaign(request));
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.CAMPAIGNS_EDIT_ALL)
    @Operation(summary = "Update campaign",
            description = "Renames a campaign and/or toggles its default flag (only one campaign is default at a time)")
    public ResponseEntity<JsonApiResponse<CampaignDto>> updateCampaign(
            @PathVariable String id,
            @Valid @RequestBody UpdateCampaignRequest request) {
        return ResponseUtil.success(campaignService.updateCampaign(id, request));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permission.CAMPAIGNS_DELETE_ALL)
    @Operation(summary = "Delete campaign",
            description = "Deletes a campaign. Rejected while any assessment references it.")
    public ResponseEntity<JsonApiResponse<Void>> deleteCampaign(@PathVariable String id) {
        campaignService.deleteCampaign(id);
        return ResponseUtil.success("Campaign deleted successfully");
    }
}
