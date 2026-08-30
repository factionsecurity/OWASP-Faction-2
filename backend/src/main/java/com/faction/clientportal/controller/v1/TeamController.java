package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.CreateTeamRequest;
import com.faction.clientportal.util.PageableUtil;
import com.faction.clientportal.util.PageableUtil.SortField;
import com.faction.clientportal.dto.TeamDto;
import com.faction.clientportal.dto.UpdateTeamRequest;
import com.faction.clientportal.dto.UserDto;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.service.TeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/teams")
@RequiredArgsConstructor
@Tag(name = "Teams", description = "Team management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class TeamController {

    private final TeamService teamService;

    /** Sortable team columns. The Users column is a member count resolved per row, not a column. */
    private static final Map<String, SortField> SORTABLE_FIELDS = Map.of(
            "name", SortField.text("name"),
            "description", SortField.text("description"),
            "createdAt", SortField.value("createdAt"));

    private static final Sort DEFAULT_SORT =
            Sort.by("name");

    @GetMapping
    @PreAuthorize("hasAnyAuthority('super_admin', 'users:read:all', 'users:read:team')")
    @Operation(
            summary = "Get all teams",
            description = "Retrieves all teams with pagination support and optional search by name or description (case-insensitive)"
    )
    public ResponseEntity<JsonApiResponse<List<TeamDto>>> getAllTeams(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "name,asc") String sort,
            @RequestParam(required = false) String search) {

        Pageable pageable = PageableUtil.of(page, size, sort, DEFAULT_SORT, SORTABLE_FIELDS);

        Page<TeamDto> teams = teamService.searchTeams(search, pageable);
        return ResponseEntity.ok(JsonApiResponse.success(teams.getContent()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('super_admin', 'users:read:all', 'users:read:team')")
    @Operation(
            summary = "Get team by ID",
            description = "Retrieves a specific team by its ID"
    )
    public ResponseEntity<JsonApiResponse<TeamDto>> getTeamById(@PathVariable String id) {
        TeamDto team = teamService.getTeamById(id);
        return ResponseEntity.ok(JsonApiResponse.success(team));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('super_admin')")
    @Operation(
            summary = "Create team",
            description = "Creates a new team"
    )
    public ResponseEntity<JsonApiResponse<TeamDto>> createTeam(@Valid @RequestBody CreateTeamRequest request) {
        TeamDto team = teamService.createTeam(request);
        return ResponseEntity.ok(JsonApiResponse.success(team));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('super_admin')")
    @Operation(
            summary = "Update team",
            description = "Updates an existing team"
    )
    public ResponseEntity<JsonApiResponse<TeamDto>> updateTeam(
            @PathVariable String id,
            @Valid @RequestBody UpdateTeamRequest request) {
        TeamDto team = teamService.updateTeam(id, request);
        return ResponseEntity.ok(JsonApiResponse.success(team));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('super_admin')")
    @Operation(
            summary = "Delete team",
            description = "Deletes a team and removes it from all users"
    )
    public ResponseEntity<JsonApiResponse<Void>> deleteTeam(@PathVariable String id) {
        teamService.deleteTeam(id);
        return ResponseEntity.ok(JsonApiResponse.success(null));
    }

    @PostMapping("/{teamId}/users/{userId}")
    @PreAuthorize("hasAuthority('super_admin')")
    @Operation(
            summary = "Add user to team",
            description = "Adds a user to a team"
    )
    public ResponseEntity<JsonApiResponse<Void>> addUserToTeam(
            @PathVariable String teamId,
            @PathVariable String userId) {
        teamService.addUserToTeam(teamId, userId);
        return ResponseEntity.ok(JsonApiResponse.success("User added to team successfully"));
    }

    @DeleteMapping("/{teamId}/users/{userId}")
    @PreAuthorize("hasAuthority('super_admin')")
    @Operation(
            summary = "Remove user from team",
            description = "Removes a user from a team"
    )
    public ResponseEntity<JsonApiResponse<Void>> removeUserFromTeam(
            @PathVariable String teamId,
            @PathVariable String userId) {
        teamService.removeUserFromTeam(teamId, userId);
        return ResponseEntity.ok(JsonApiResponse.success("User removed from team successfully"));
    }

    @GetMapping("/{teamId}/users")
    @PreAuthorize("hasAnyAuthority('super_admin', 'users:read:all', 'users:read:team')")
    @Operation(
            summary = "Get users in team",
            description = "Retrieves all users that are members of a specific team"
    )
    public ResponseEntity<JsonApiResponse<List<UserDto>>> getUsersInTeam(@PathVariable String teamId) {
        List<UserDto> users = teamService.getUsersInTeam(teamId);
        return ResponseEntity.ok(JsonApiResponse.success(users));
    }
}
