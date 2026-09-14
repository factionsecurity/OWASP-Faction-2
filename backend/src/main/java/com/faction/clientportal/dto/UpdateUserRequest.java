package com.faction.clientportal.dto;

import com.faction.clientportal.model.LoginOption;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateUserRequest {

    @NotBlank(message = "Username is required")
    private String username;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    private String email;

    @NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    // Password is optional for updates - only update if provided
    private String password;

    @NotNull(message = "Login option is required")
    private LoginOption loginOption;

    @NotNull(message = "Role IDs are required")
    private List<String> roleIds;

    private List<String> teamIds;

    @NotNull(message = "isInternal flag is required")
    private Boolean isInternal;

    private List<String> organizationIds;

    private List<String> subOrganizationIds;

    /** Deprecated single-organization form; folded into {@link #organizationIds}. */
    @Deprecated
    private String organizationId;

    public List<String> effectiveOrganizationIds() {
        List<String> ids = new java.util.ArrayList<>();
        if (organizationIds != null) {
            organizationIds.stream().filter(s -> s != null && !s.isBlank()).distinct().forEach(ids::add);
        }
        if (organizationId != null && !organizationId.isBlank() && !ids.contains(organizationId)) {
            ids.add(organizationId);
        }
        return ids;
    }

    public List<String> effectiveSubOrganizationIds() {
        return subOrganizationIds == null ? new java.util.ArrayList<>()
                : subOrganizationIds.stream().filter(s -> s != null && !s.isBlank()).distinct()
                        .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    }

    /**
     * Disable or re-enable the account. A disabled user cannot log in and their API keys are
     * inert, but they stay linkable and keep their history. Null leaves the current state
     * alone, so a caller that only means to edit a name cannot silently re-enable someone.
     */
    private Boolean disabled;
}
