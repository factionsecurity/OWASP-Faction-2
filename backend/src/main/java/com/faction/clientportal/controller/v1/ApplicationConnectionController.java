package com.faction.clientportal.controller.v1;

import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.dto.ApplicationConnectionDto;
import com.faction.clientportal.dto.CreateApplicationConnectionRequest;
import com.faction.clientportal.dto.UpdateApplicationConnectionRequest;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.service.ApplicationConnectionService;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/application-connections")
@RequiredArgsConstructor
@Tag(name = "Application Connections", description = "Application connection management endpoints for dependency mapping and threat modeling")
@SecurityRequirement(name = "bearerAuth")
public class ApplicationConnectionController {

    private final ApplicationConnectionService connectionService;

    @GetMapping
    @RequiresPermission(Permission.APPLICATIONS_READ_ALL)
    @Operation(
            summary = "Get all application connections",
            description = "Retrieve all application connections for dependency mapping and threat modeling.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Successfully retrieved all connections"),
                    @ApiResponse(responseCode = "403", description = "Forbidden"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized")
            }
    )
    public ResponseEntity<JsonApiResponse<List<ApplicationConnectionDto>>> getAllConnections() {
        List<ApplicationConnectionDto> connections = connectionService.findAll();
        return ResponseUtil.success("Connections retrieved successfully", connections);
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permission.APPLICATIONS_READ_ALL)
    @Operation(
            summary = "Get connection by ID",
            description = "Retrieve a specific application connection by its ID.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Successfully retrieved connection"),
                    @ApiResponse(responseCode = "404", description = "Connection not found"),
                    @ApiResponse(responseCode = "403", description = "Forbidden"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized")
            }
    )
    public ResponseEntity<JsonApiResponse<ApplicationConnectionDto>> getConnectionById(@PathVariable String id) {
        ApplicationConnectionDto connection = connectionService.findById(id);
        return ResponseUtil.success("Connection retrieved successfully", connection);
    }

    @GetMapping("/application/{applicationId}/outgoing")
    @RequiresPermission(Permission.APPLICATIONS_READ_ALL)
    @Operation(
            summary = "Get outgoing connections",
            description = "Retrieve all outgoing connections (dependencies) for a specific application.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Successfully retrieved outgoing connections"),
                    @ApiResponse(responseCode = "403", description = "Forbidden"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized")
            }
    )
    public ResponseEntity<JsonApiResponse<List<ApplicationConnectionDto>>> getOutgoingConnections(
            @PathVariable String applicationId) {
        List<ApplicationConnectionDto> connections = connectionService.findOutgoingConnections(applicationId);
        return ResponseUtil.success("Outgoing connections retrieved successfully", connections);
    }

    @GetMapping("/application/{applicationId}/incoming")
    @RequiresPermission(Permission.APPLICATIONS_READ_ALL)
    @Operation(
            summary = "Get incoming connections",
            description = "Retrieve all incoming connections (dependents) for a specific application.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Successfully retrieved incoming connections"),
                    @ApiResponse(responseCode = "403", description = "Forbidden"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized")
            }
    )
    public ResponseEntity<JsonApiResponse<List<ApplicationConnectionDto>>> getIncomingConnections(
            @PathVariable String applicationId) {
        List<ApplicationConnectionDto> connections = connectionService.findIncomingConnections(applicationId);
        return ResponseUtil.success("Incoming connections retrieved successfully", connections);
    }

    @GetMapping("/application/{applicationId}/all")
    @RequiresPermission(Permission.APPLICATIONS_READ_ALL)
    @Operation(
            summary = "Get all connections for application",
            description = "Retrieve all connections (both incoming and outgoing) for a specific application.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Successfully retrieved all connections"),
                    @ApiResponse(responseCode = "403", description = "Forbidden"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized")
            }
    )
    public ResponseEntity<JsonApiResponse<List<ApplicationConnectionDto>>> getAllConnectionsForApplication(
            @PathVariable String applicationId) {
        List<ApplicationConnectionDto> connections = connectionService.findAllConnectionsForApplication(applicationId);
        return ResponseUtil.success("All connections retrieved successfully", connections);
    }

    @PostMapping
    @RequiresPermission(Permission.APPLICATIONS_CREATE_ALL)
    @Operation(
            summary = "Create a new application connection",
            description = "Create a new connection between two applications.",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Connection created successfully"),
                    @ApiResponse(responseCode = "400", description = "Bad Request - Invalid input"),
                    @ApiResponse(responseCode = "403", description = "Forbidden"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized")
            }
    )
    public ResponseEntity<JsonApiResponse<ApplicationConnectionDto>> createConnection(
            @Valid @RequestBody CreateApplicationConnectionRequest request,
            Authentication authentication) {
        String userId = authentication.getName();
        ApplicationConnectionDto createdConnection = connectionService.createConnection(request, userId);
        return ResponseUtil.created("Connection created successfully", createdConnection);
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.APPLICATIONS_EDIT_ALL)
    @Operation(
            summary = "Update an existing connection",
            description = "Update an existing application connection.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Connection updated successfully"),
                    @ApiResponse(responseCode = "400", description = "Bad Request - Invalid input"),
                    @ApiResponse(responseCode = "404", description = "Connection not found"),
                    @ApiResponse(responseCode = "403", description = "Forbidden"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized")
            }
    )
    public ResponseEntity<JsonApiResponse<ApplicationConnectionDto>> updateConnection(
            @PathVariable String id,
            @Valid @RequestBody UpdateApplicationConnectionRequest request,
            Authentication authentication) {
        String userId = authentication.getName();
        ApplicationConnectionDto updatedConnection = connectionService.updateConnection(id, request, userId);
        return ResponseUtil.success("Connection updated successfully", updatedConnection);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permission.APPLICATIONS_DELETE_ALL)
    @Operation(
            summary = "Delete a connection",
            description = "Delete an application connection.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Connection deleted successfully"),
                    @ApiResponse(responseCode = "404", description = "Connection not found"),
                    @ApiResponse(responseCode = "403", description = "Forbidden"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized")
            }
    )
    public ResponseEntity<JsonApiResponse<Void>> deleteConnection(@PathVariable String id) {
        connectionService.deleteConnection(id);
        return ResponseUtil.success("Connection deleted successfully");
    }
}
