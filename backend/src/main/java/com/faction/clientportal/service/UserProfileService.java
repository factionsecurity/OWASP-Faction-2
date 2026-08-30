package com.faction.clientportal.service;

import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Self-service profile operations for the currently authenticated user:
 * password changes and profile image management.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private static final Set<String> ALLOWED_IMAGE_TYPES =
            Set.of("image/png", "image/jpeg", "image/gif", "image/webp");
    private static final long MAX_IMAGE_BYTES = 2 * 1024 * 1024; // 2 MB

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final StorageService storageService;

    public User getByUsernameOrThrow(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
    }

    public void changePassword(String username, String currentPassword, String newPassword) {
        User user = getByUsernameOrThrow(username);
        if (user.getLoginOption() != LoginOption.NATIVE) {
            throw new IllegalArgumentException(
                    "Password is managed by your identity provider and cannot be changed here.");
        }
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new IllegalArgumentException("Current password is incorrect.");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.info("Password changed for user {}", username);
    }

    /** Stores a new profile image, replacing (and deleting) any previous one. */
    public String updateProfileImage(String username, String contentType, byte[] bytes) {
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType)) {
            throw new IllegalArgumentException(
                    "Unsupported image type. Allowed: PNG, JPEG, GIF, WebP.");
        }
        if (bytes.length == 0) {
            throw new IllegalArgumentException("Image file is empty.");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Image must be smaller than 2 MB.");
        }
        User user = getByUsernameOrThrow(username);
        String oldKey = user.getProfileImageKey();

        String imageId = UUID.randomUUID().toString();
        String key = "profile-images/" + imageId;
        storageService.uploadBytes(key, bytes, contentType);

        user.setProfileImageId(imageId);
        user.setProfileImageKey(key);
        userRepository.save(user);

        deleteQuietly(oldKey);
        return imageId;
    }

    public void removeProfileImage(String username) {
        User user = getByUsernameOrThrow(username);
        String oldKey = user.getProfileImageKey();
        user.setProfileImageId(null);
        user.setProfileImageKey(null);
        userRepository.save(user);
        deleteQuietly(oldKey);
    }

    /**
     * Open a profile image's bytes for streaming. The caller owns the returned
     * stream and must close it.
     */
    public StorageService.StoredFile openProfileImage(String imageId) {
        User user = userRepository.findByProfileImageId(imageId)
                .orElseThrow(() -> new ResourceNotFoundException("Profile image not found"));
        return new StorageService.StoredFile(
                storageService.openStream(user.getProfileImageKey()), imageId);
    }

    /** Default-avatar seed (email) and uploaded image id for one user. */
    public record AvatarInfo(String seed, String profileImageId) {}

    /**
     * Avatar info for every active user, keyed by BOTH user id and username —
     * discussion comments store the author's username while other consumers
     * hold the user id. The seed (the user's email, falling back to username)
     * drives the default identicon so it is identical everywhere.
     */
    public Map<String, AvatarInfo> getAvatarMap() {
        Map<String, AvatarInfo> map = new HashMap<>();
        for (User u : userRepository.findAll()) {
            if (u.getDeletedAt() != null) continue;
            String seed = (u.getEmail() != null && !u.getEmail().isBlank())
                    ? u.getEmail() : u.getUsername();
            AvatarInfo info = new AvatarInfo(seed, u.getProfileImageId());
            map.put(u.getId(), info);
            map.put(u.getUsername(), info);
        }
        return map;
    }

    private void deleteQuietly(String key) {
        if (key == null) return;
        try {
            storageService.deleteObject(key);
        } catch (Exception e) {
            log.warn("Could not delete old profile image {}: {}", key, e.getMessage());
        }
    }
}
