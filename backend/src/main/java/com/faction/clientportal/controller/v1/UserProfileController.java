package com.faction.clientportal.controller.v1;

import com.faction.clientportal.security.AuthenticatedOnly;
import com.faction.clientportal.dto.UserDto;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.service.StorageService;
import com.faction.clientportal.service.UserProfileService;
import com.faction.clientportal.service.UserService;
import com.faction.clientportal.util.FileStreamResponse;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * Self-service profile endpoints for the authenticated user. No permission
 * gates beyond authentication — every user can manage their own profile.
 */
@RestController
@AuthenticatedOnly
@RequiredArgsConstructor
@Tag(name = "User Profile", description = "Self-service profile: password changes and profile images")
public class UserProfileController {

    private final UserProfileService userProfileService;
    private final UserService userService;

    @GetMapping("/api/v1/users/me")
    @Operation(summary = "Get current user profile",
               description = "Returns the profile of the currently authenticated user.")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<JsonApiResponse<UserDto>> me(Authentication authentication) {
        UserDto dto = userService.findDtoByUsername(authentication.getName());
        return ResponseUtil.success("Profile retrieved successfully", dto);
    }

    @PostMapping("/api/v1/users/me/change-password")
    @Operation(summary = "Change own password",
               description = "Changes the current user's password after verifying the current one. "
                       + "Rejected for SSO-managed accounts.")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<JsonApiResponse<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            Authentication authentication) {
        userProfileService.changePassword(
                authentication.getName(), request.getCurrentPassword(), request.getNewPassword());
        return ResponseUtil.success("Password changed successfully", null);
    }

    @PostMapping("/api/v1/users/me/profile-image")
    @Operation(summary = "Upload own profile image",
               description = "Uploads a profile image (PNG/JPEG/GIF/WebP, max 2 MB) for the current "
                       + "user, replacing any existing one. Returns the new profile image id.")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<JsonApiResponse<Map<String, String>>> uploadProfileImage(
            @RequestParam("file") MultipartFile file,
            Authentication authentication) throws IOException {
        String imageId = userProfileService.updateProfileImage(
                authentication.getName(), file.getContentType(), file.getBytes());
        return ResponseUtil.success("Profile image updated successfully",
                Map.of("profileImageId", imageId));
    }

    @DeleteMapping("/api/v1/users/me/profile-image")
    @Operation(summary = "Remove own profile image",
               description = "Removes the current user's uploaded profile image, reverting to the default avatar.")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<JsonApiResponse<Void>> removeProfileImage(Authentication authentication) {
        userProfileService.removeProfileImage(authentication.getName());
        return ResponseUtil.success("Profile image removed successfully", null);
    }

    @GetMapping("/api/v1/users/avatars")
    @Operation(summary = "Get avatar map",
               description = "Returns avatar info (default-avatar seed and uploaded profile image id) for "
                       + "every active user, keyed by both user id and username. Used to resolve avatars "
                       + "consistently across discussion areas and the top bar.")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<JsonApiResponse<Map<String, UserProfileService.AvatarInfo>>> avatarMap() {
        return ResponseUtil.success("Avatars retrieved successfully",
                userProfileService.getAvatarMap());
    }

    /**
     * Stream a profile image to the browser.
     *
     * <p>Any authenticated user may fetch any profile image — avatars are shown
     * next to comments and assignments throughout the app, so this is
     * deliberately not scoped further. It is no longer anonymous, though: the
     * browser presents the media cookie from its {@code <img>} tag, which this
     * path is on the allowlist for.
     */
    @GetMapping("/api/v1/profile-images/{imageId}")
    @Operation(summary = "Serve a profile image",
               description = "Streams the profile image bytes to any authenticated caller. "
                       + "404 if not found.")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Resource> serve(@PathVariable String imageId) {
        StorageService.StoredFile file = userProfileService.openProfileImage(imageId);
        return FileStreamResponse.inlineImage(file.stream(), file.fileName());
    }

    @Data
    public static class ChangePasswordRequest {
        @NotBlank(message = "Current password is required")
        private String currentPassword;
        @NotBlank(message = "New password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        private String newPassword;
    }
}
