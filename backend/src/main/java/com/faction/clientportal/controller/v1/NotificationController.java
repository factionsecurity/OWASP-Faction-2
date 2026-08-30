package com.faction.clientportal.controller.v1;

import com.faction.clientportal.security.AuthenticatedOnly;
import com.faction.clientportal.dto.NotificationDto;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.service.MentionSectionAccess;
import com.faction.clientportal.service.MentionTargetFilter;
import com.faction.clientportal.service.NotificationService;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "In-app notification endpoints")
@SecurityRequirement(name = "bearerAuth")
@AuthenticatedOnly
public class NotificationController {

    private final NotificationService service;

    @GetMapping(value = "/api/v1/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Subscribe to real-time notification events via SSE")
    public SseEmitter stream(Authentication authentication) {
        return service.subscribe(authentication.getName(), sectionAccess(authentication));
    }

    @GetMapping("/api/v1/notifications")
    @Operation(summary = "List all notifications for the current user")
    public ResponseEntity<JsonApiResponse<List<NotificationDto>>> getAll(Authentication authentication) {
        return ResponseUtil.success("Notifications retrieved", service.getForUser(authentication.getName()));
    }

    @GetMapping("/api/v1/notifications/mentions")
    @Operation(summary = "List mentions and thread replies for the current user")
    public ResponseEntity<JsonApiResponse<List<NotificationDto>>> getMentions(Authentication authentication) {
        return ResponseUtil.success("Mentions retrieved",
                service.getMentions(authentication.getName(), sectionAccess(authentication)));
    }

    @GetMapping("/api/v1/notifications/mentions/unread-count")
    @Operation(summary = "Get the count of unread mentions and thread replies")
    public ResponseEntity<JsonApiResponse<Long>> getMentionsUnreadCount(Authentication authentication) {
        return ResponseUtil.success("Unread mention count",
                service.getMentionsUnreadCount(authentication.getName(), sectionAccess(authentication)));
    }

    @GetMapping("/api/v1/notifications/unread-count")
    @Operation(summary = "Get the count of unread notifications")
    public ResponseEntity<JsonApiResponse<Long>> getUnreadCount(Authentication authentication) {
        return ResponseUtil.success("Unread count", service.getUnreadCount(authentication.getName()));
    }

    @PatchMapping("/api/v1/notifications/{id}/read")
    @Operation(summary = "Mark a single notification as read")
    public ResponseEntity<JsonApiResponse<NotificationDto>> markRead(
            @PathVariable String id,
            Authentication authentication) {
        return ResponseUtil.success("Marked as read", service.markRead(id, authentication.getName()));
    }

    @PatchMapping("/api/v1/notifications/read-all")
    @Operation(summary = "Mark all notifications as read")
    public ResponseEntity<JsonApiResponse<Void>> markAllRead(Authentication authentication) {
        service.markAllRead(authentication.getName());
        return ResponseUtil.success("All notifications marked as read", null);
    }

    @DeleteMapping("/api/v1/notifications/mentions")
    @Operation(summary = "Delete mentions and thread replies for the current user",
            description = "Clears the whole feed, or one section of it: targetType may be "
                    + "APPLICATION, VULNERABILITY, NOTEBOOK, or NONE for rows with no target.")
    public ResponseEntity<JsonApiResponse<Void>> deleteAllMentions(
            @RequestParam(required = false) String targetType,
            Authentication authentication) {
        MentionTargetFilter filter;
        try {
            filter = MentionTargetFilter.parse(targetType);
        } catch (IllegalArgumentException e) {
            return ResponseUtil.badRequest("Unknown targetType: " + targetType);
        }
        service.deleteAllMentions(authentication.getName(), filter, sectionAccess(authentication));
        return ResponseUtil.success("Mentions deleted", null);
    }

    @DeleteMapping("/api/v1/notifications")
    @Operation(summary = "Delete all notifications for the current user")
    public ResponseEntity<JsonApiResponse<Void>> deleteAll(Authentication authentication) {
        service.deleteAll(authentication.getName());
        return ResponseUtil.success("All notifications deleted", null);
    }

    @DeleteMapping("/api/v1/notifications/{id}")
    @Operation(summary = "Delete a notification")
    public ResponseEntity<JsonApiResponse<Void>> delete(
            @PathVariable String id,
            Authentication authentication) {
        service.delete(id, authentication.getName());
        return ResponseUtil.success("Notification deleted", null);
    }

    /**
     * Which mention sections this caller may see. A mention reaches whoever was named,
     * which is not the same as being allowed to open the thing it points at.
     */
    private static MentionSectionAccess sectionAccess(Authentication authentication) {
        return MentionSectionAccess.of(authentication.getAuthorities());
    }
}
