package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.service.email.EmailUnsubscribeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Unsubscribe from a thread using the token in an email.
 *
 * <p>Unauthenticated on purpose: someone who wants to stop receiving mail should not have
 * to log in to say so, and the token is proof they received the email.
 *
 * <p><b>POST, not GET.</b> Mail clients and security scanners prefetch links in messages,
 * so a mutating GET would silently unsubscribe people who merely received the email. The
 * link in the email points at a frontend page which posts here on a click.
 */
@RestController
@RequestMapping("/api/v1/email/unsubscribe")
@RequiredArgsConstructor
@Tag(name = "Email Unsubscribe")
public class EmailUnsubscribeController {

    private final EmailUnsubscribeService unsubscribeService;

    @Data
    public static class UnsubscribeRequest {
        private String token;
    }

    @PostMapping
    @Operation(summary = "Remove the holder of this token from the conversation")
    public ResponseEntity<JsonApiResponse<EmailUnsubscribeService.Result>> unsubscribe(
            @RequestBody UnsubscribeRequest request) {
        return ResponseEntity.ok(JsonApiResponse.success(
                unsubscribeService.unsubscribe(request.getToken())));
    }
}
