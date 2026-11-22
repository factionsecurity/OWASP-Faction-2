package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.GlobalSearchResultDto;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.security.AuthenticatedOnly;
import com.faction.clientportal.service.GlobalSearchService;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
@Tag(name = "Search", description = "Global search across records")
@SecurityRequirement(name = "bearerAuth")
public class GlobalSearchController {

    private final GlobalSearchService globalSearchService;

    // No single permission fits: each group is gated by its own list's permissions inside the
    // service, and a caller who can read none of them still gets assessment types. Staff only.
    @GetMapping
    @AuthenticatedOnly
    @Operation(summary = "Search vulnerabilities, assessments, applications, users and assessment types",
            description = "Staff accounts only. Returns one group per kind of record the caller may read, "
                    + "each with its first `limit` matches (default 5, at most 10) and the number that "
                    + "matched in all. Each group applies the same access scope as that record's own list. "
                    + "Vulnerabilities match on name or tracking id, an exact tracking id first; assessments "
                    + "on their name or their application's; applications on name or application id; users "
                    + "on name, username or email. Text shorter than 2 characters returns no groups.")
    public ResponseEntity<JsonApiResponse<GlobalSearchResultDto>> search(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) Integer limit,
            Authentication authentication) {
        return ResponseUtil.success("Search results",
                globalSearchService.search(query, limit, authentication));
    }
}
