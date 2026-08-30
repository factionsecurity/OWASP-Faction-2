package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.ExtensionDto;
import com.faction.clientportal.dto.ExtensionLogDto;
import com.faction.clientportal.dto.UpdateExtensionConfigRequest;
import com.faction.clientportal.dto.UpdateExtensionRequest;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.service.extension.ExtensionService;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * App Store administration.
 *
 * <p>An extension JAR runs arbitrary code inside Faction's own JVM, so every
 * mutating endpoint here requires {@code extensions:write} — a permission that
 * should be treated as equivalent to server access, not as ordinary admin.
 */
@RestController
@RequestMapping("/api/v1/admin/extensions")
@RequiredArgsConstructor
@Tag(name = "App Store", description = "Install and configure Faction extensions")
@SecurityRequirement(name = "bearerAuth")
public class ExtensionController {

    /** Refuse anything that is obviously not a JAR before reading it into memory. */
    private static final long MAX_JAR_BYTES = 100L * 1024 * 1024;

    private final ExtensionService extensionService;

    @GetMapping
    @RequiresPermission({Permission.EXTENSIONS_READ, Permission.EXTENSIONS_WRITE})
    @Operation(summary = "List installed extensions")
    public ResponseEntity<JsonApiResponse<List<ExtensionDto>>> list() {
        return ResponseUtil.success("Extensions retrieved successfully", extensionService.list());
    }

    @GetMapping("/{id}")
    @RequiresPermission({Permission.EXTENSIONS_READ, Permission.EXTENSIONS_WRITE})
    @Operation(summary = "Get an installed extension")
    public ResponseEntity<JsonApiResponse<ExtensionDto>> get(@PathVariable String id) {
        return ResponseUtil.success("Extension retrieved successfully", extensionService.get(id));
    }

    @GetMapping("/{id}/logs")
    @RequiresPermission({Permission.EXTENSIONS_READ, Permission.EXTENSIONS_WRITE})
    @Operation(summary = "Recent log output from an extension")
    public ResponseEntity<JsonApiResponse<List<ExtensionLogDto>>> logs(@PathVariable String id) {
        return ResponseUtil.success("Extension logs retrieved successfully", extensionService.logs(id));
    }

    @PostMapping("/upload")
    @RequiresPermission(Permission.EXTENSIONS_WRITE)
    @Operation(
            summary = "Install an extension from a JAR",
            description = "Parses the JAR's manifest, description, logo, config.json and "
                    + "META-INF/services entries. The extension is installed disabled.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Extension installed"),
                    @ApiResponse(responseCode = "400", description = "Not a valid Faction extension JAR")
            }
    )
    public ResponseEntity<JsonApiResponse<ExtensionDto>> install(
            @RequestParam("file") MultipartFile file,
            Authentication authentication) throws IOException {
        return ResponseUtil.success("Extension installed successfully",
                extensionService.install(readJar(file), authentication.getName()));
    }

    @PostMapping("/{id}/upgrade")
    @RequiresPermission(Permission.EXTENSIONS_WRITE)
    @Operation(
            summary = "Replace an extension's JAR",
            description = "Re-reads metadata from the new JAR while preserving configured values.")
    public ResponseEntity<JsonApiResponse<ExtensionDto>> upgrade(
            @PathVariable String id,
            @RequestParam("file") MultipartFile file,
            Authentication authentication) throws IOException {
        return ResponseUtil.success("Extension upgraded successfully",
                extensionService.upgrade(id, readJar(file), authentication.getName()));
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.EXTENSIONS_WRITE)
    @Operation(summary = "Enable, disable or reorder an extension and its hooks")
    public ResponseEntity<JsonApiResponse<ExtensionDto>> update(
            @PathVariable String id,
            @RequestBody UpdateExtensionRequest request,
            Authentication authentication) {
        return ResponseUtil.success("Extension updated successfully",
                extensionService.update(id, request, authentication.getName()));
    }

    @PutMapping("/{id}/config")
    @RequiresPermission(Permission.EXTENSIONS_WRITE)
    @Operation(
            summary = "Update an extension's configuration values",
            description = "Only keys declared in the extension's config.json are accepted. "
                    + "A password field echoed back as its mask is left unchanged.")
    public ResponseEntity<JsonApiResponse<ExtensionDto>> updateConfig(
            @PathVariable String id,
            @RequestBody UpdateExtensionConfigRequest request,
            Authentication authentication) {
        return ResponseUtil.success("Extension configuration updated successfully",
                extensionService.updateConfig(id, request, authentication.getName()));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permission.EXTENSIONS_WRITE)
    @Operation(summary = "Uninstall an extension")
    public ResponseEntity<JsonApiResponse<Void>> uninstall(
            @PathVariable String id, Authentication authentication) {
        extensionService.uninstall(id, authentication.getName());
        return ResponseUtil.success("Extension uninstalled successfully", null);
    }

    private byte[] readJar(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No extension file was uploaded");
        }
        if (file.getSize() > MAX_JAR_BYTES) {
            throw new IllegalArgumentException("Extension JAR exceeds the 100MB limit");
        }
        String filename = file.getOriginalFilename();
        if (filename != null && !filename.toLowerCase().endsWith(".jar")) {
            throw new IllegalArgumentException("Extension file must be a .jar");
        }
        return file.getBytes();
    }
}
