package com.faction.clientportal.controller.v1;

import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.dto.*;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.service.NotebookService;
import com.faction.clientportal.service.StorageService;
import com.faction.clientportal.util.FileStreamResponse;
import com.faction.clientportal.util.ResponseUtil;
import com.faction.clientportal.util.UploadRequests;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Notebook", description = "Notebook management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class NotebookController {

    private final NotebookService notebookService;
    private final UploadRequests uploadRequests;

    // -------------------------------------------------------------------------
    // Tree
    // -------------------------------------------------------------------------

    @GetMapping("/applications/{appId}/notebook")
    @RequiresPermission({Permission.ASSESSMENTS_READ_ALL, Permission.ASSESSMENTS_READ_TEAM, Permission.ASSESSMENTS_READ_ASSIGNED})
    @Operation(summary = "Get notebook tree",
               description = "Returns the full notebook node tree for an application.")
    public ResponseEntity<JsonApiResponse<List<NotebookNodeDto>>> getTreeForApplication(
            @PathVariable String appId,
            Authentication authentication) {
        List<NotebookNodeDto> tree = notebookService.getTreeForApplication(appId, authentication);
        return ResponseUtil.success(tree);
    }

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    @PostMapping("/applications/{appId}/notebook/nodes")
    @RequiresPermission({Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_ASSIGNED})
    @Operation(summary = "Create notebook node",
               description = "Creates a new notebook node (folder or note) under an application, optionally nested beneath a parent node.")
    public ResponseEntity<JsonApiResponse<NotebookNodeDto>> createNode(
            @PathVariable String appId,
            @Valid @RequestBody CreateNotebookNodeRequest request,
            Authentication authentication) {
        String userId = authentication.getName();
        NotebookNodeDto node = notebookService.createNode(appId, request, userId, authentication);
        return ResponseUtil.created("Notebook node created successfully", node);
    }

    // -------------------------------------------------------------------------
    // Single node
    // -------------------------------------------------------------------------

    @GetMapping("/notebook/nodes/{nodeId}")
    @RequiresPermission({Permission.ASSESSMENTS_READ_ALL, Permission.ASSESSMENTS_READ_TEAM, Permission.ASSESSMENTS_READ_ASSIGNED})
    @Operation(summary = "Get notebook node",
               description = "Returns a single notebook node including its content and attachments.")
    public ResponseEntity<JsonApiResponse<NotebookNodeDto>> getNode(
            @PathVariable String nodeId,
            Authentication authentication) {
        return ResponseUtil.success(notebookService.getNode(nodeId, authentication));
    }

    // -------------------------------------------------------------------------
    // Update
    // -------------------------------------------------------------------------

    @PutMapping("/notebook/nodes/{nodeId}")
    @RequiresPermission({Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_ASSIGNED})
    @Operation(summary = "Update notebook node",
               description = "Updates a notebook node's title and/or content.")
    public ResponseEntity<JsonApiResponse<NotebookNodeDto>> updateNode(
            @PathVariable String nodeId,
            @RequestBody UpdateNotebookNodeRequest request,
            Authentication authentication) {
        String userId = authentication.getName();
        return ResponseUtil.success(notebookService.updateNode(nodeId, request, userId, authentication));
    }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    @DeleteMapping("/notebook/nodes/{nodeId}")
    @RequiresPermission(Permission.ASSESSMENTS_DELETE_ALL)
    @Operation(summary = "Delete notebook node",
               description = "Deletes a notebook node and all of its descendants and attachments.")
    public ResponseEntity<JsonApiResponse<Void>> deleteNode(
            @PathVariable String nodeId,
            Authentication authentication) {
        String userId = authentication.getName();
        notebookService.deleteNode(nodeId, userId, authentication);
        return ResponseUtil.success("Notebook node deleted successfully");
    }

    // -------------------------------------------------------------------------
    // Move
    // -------------------------------------------------------------------------

    @PutMapping("/notebook/nodes/{nodeId}/move")
    @RequiresPermission({Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_ASSIGNED})
    @Operation(summary = "Move notebook node",
               description = "Moves a notebook node to a new parent and/or position within the tree.")
    public ResponseEntity<JsonApiResponse<NotebookNodeDto>> moveNode(
            @PathVariable String nodeId,
            @RequestBody MoveNotebookNodeRequest request,
            Authentication authentication) {
        String userId = authentication.getName();
        return ResponseUtil.success(notebookService.moveNode(nodeId, request, userId, authentication));
    }

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    @GetMapping("/applications/{appId}/notebook/search")
    @RequiresPermission({Permission.ASSESSMENTS_READ_ALL, Permission.ASSESSMENTS_READ_TEAM, Permission.ASSESSMENTS_READ_ASSIGNED})
    @Operation(summary = "Search notebook nodes",
               description = "Searches an application's notebook by text query, author, and/or creation date range.")
    public ResponseEntity<JsonApiResponse<List<NotebookSearchResultDto>>> searchNodes(
            @PathVariable String appId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String createdById,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            Authentication authentication) {
        List<NotebookSearchResultDto> results =
                notebookService.searchNodes(appId, q, createdById, from, to, authentication);
        return ResponseUtil.success(results);
    }

    // -------------------------------------------------------------------------
    // File upload — allocate target
    // -------------------------------------------------------------------------

    @PostMapping("/notebook/nodes/{nodeId}/files/prepare")
    @RequiresPermission({Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_ASSIGNED})
    @Operation(summary = "Allocate an upload target for a notebook file",
               description = "Returns a file id and the backend URL to PUT the file body to. " +
                             "After the upload completes, call the confirm endpoint.")
    public ResponseEntity<JsonApiResponse<UploadTargetResponse>> prepareUpload(
            @PathVariable String nodeId,
            @Valid @RequestBody PrepareUploadRequest request,
            Authentication authentication) {
        String userId = authentication.getName();
        UploadTargetResponse response = notebookService.prepareUpload(
                nodeId, request.getFileName(), userId, authentication);
        return ResponseUtil.success(response);
    }

    /** Receive a notebook attachment's bytes and stream them into storage. */
    @PutMapping("/notebook/nodes/{nodeId}/files/{fileId}/content")
    @RequiresPermission({Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_ASSIGNED})
    @Operation(summary = "Upload a notebook attachment's bytes",
               description = "Streams the request body into storage under the prepared file id.")
    public ResponseEntity<JsonApiResponse<Void>> uploadContent(
            @PathVariable String nodeId,
            @PathVariable String fileId,
            @RequestParam String fileName,
            HttpServletRequest request,
            Authentication authentication) throws IOException {
        notebookService.storeUpload(nodeId, fileId, fileName,
                uploadRequests.contentType(request), uploadRequests.contentLength(request),
                request.getInputStream(), authentication);
        return ResponseUtil.success("File uploaded", null);
    }

    // -------------------------------------------------------------------------
    // File upload — confirm
    // -------------------------------------------------------------------------

    @PostMapping("/notebook/nodes/{nodeId}/files/confirm")
    @RequiresPermission({Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_ASSIGNED})
    @Operation(summary = "Confirm notebook file upload",
               description = "Persists attachment metadata after a successful direct upload to storage.")
    public ResponseEntity<JsonApiResponse<NotebookAttachmentDto>> confirmUpload(
            @PathVariable String nodeId,
            @Valid @RequestBody ConfirmUploadRequest request,
            Authentication authentication) {
        String userId = authentication.getName();
        NotebookAttachmentDto dto = notebookService.confirmFileUpload(
                nodeId, request.getFileId(), request.getFileName(),
                request.getContentType(), request.getFileSize(), userId, authentication);
        return ResponseUtil.created("File upload confirmed", dto);
    }

    // -------------------------------------------------------------------------
    // File download URL
    // -------------------------------------------------------------------------

    @GetMapping("/notebook/nodes/{nodeId}/files/{fileId}/content")
    @RequiresPermission({Permission.ASSESSMENTS_READ_ALL, Permission.ASSESSMENTS_READ_TEAM, Permission.ASSESSMENTS_READ_ASSIGNED})
    @Operation(summary = "Download a notebook attachment",
               description = "Streams the attachment's bytes as an attachment download.")
    public ResponseEntity<Resource> downloadContent(
            @PathVariable String nodeId,
            @PathVariable String fileId,
            Authentication authentication) {
        StorageService.StoredFile file = notebookService.openFile(nodeId, fileId, authentication);
        return FileStreamResponse.attachment(file.stream(), file.fileName());
    }

    // -------------------------------------------------------------------------
    // File delete
    // -------------------------------------------------------------------------

    @DeleteMapping("/notebook/nodes/{nodeId}/files/{fileId}")
    @RequiresPermission({Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_ASSIGNED})
    @Operation(summary = "Delete notebook file",
               description = "Deletes the attachment from storage and removes its metadata from the node.")
    public ResponseEntity<JsonApiResponse<Void>> deleteFile(
            @PathVariable String nodeId,
            @PathVariable String fileId,
            Authentication authentication) {
        String userId = authentication.getName();
        notebookService.deleteFile(nodeId, fileId, userId, authentication);
        return ResponseUtil.success("File deleted successfully");
    }
}
