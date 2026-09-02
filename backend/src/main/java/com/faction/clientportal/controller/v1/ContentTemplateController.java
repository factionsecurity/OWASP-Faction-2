package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.ContentTemplateDto;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.model.ContentTemplateScope;
import com.faction.clientportal.security.AuthenticatedOnly;
import com.faction.clientportal.service.ContentTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The content templates a rich text editor can insert. Open to any authenticated user:
 * templates are reusable boilerplate written for exactly this purpose and carry no
 * assessment-specific data.
 */
@RestController
@RequestMapping("/api/v1/content-templates")
@RequiredArgsConstructor
@Tag(name = "Content Templates", description = "Reusable rich text boilerplate for editors")
public class ContentTemplateController {

    private final ContentTemplateService contentTemplateService;

    @GetMapping
    @AuthenticatedOnly
    @Operation(summary = "List available content templates",
            description = "The enabled templates for the given editor scope, name-ordered, with their "
                    + "bodies so the picker can preview and insert without a second request. Disabled "
                    + "templates are never returned.")
    public ResponseEntity<JsonApiResponse<List<ContentTemplateDto>>> getTemplates(
            @RequestParam ContentTemplateScope scope) {
        return ResponseEntity.ok(JsonApiResponse.success(contentTemplateService.getEnabledTemplates(scope)));
    }
}
