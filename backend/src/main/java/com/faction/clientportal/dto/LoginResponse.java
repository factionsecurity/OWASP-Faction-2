package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private String token;

    @Builder.Default
    private String tokenType = "Bearer";

    private Long expiresIn;

    private String userId;

    private String username;

    private List<String> authorities;

    private List<String> roles;

    /**
     * Whether this is a staff account rather than a customer-side one. Sent so the UI can
     * hide controls reserved for internal users (assigning a finding's remediation owner);
     * the API enforces the same rule regardless of what the client believes.
     */
    private Boolean isInternal;
}
