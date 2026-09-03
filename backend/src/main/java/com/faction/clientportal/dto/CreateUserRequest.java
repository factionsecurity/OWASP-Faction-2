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
public class CreateUserRequest {

    @NotBlank(message = "Username is required")
    private String username;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    private String email;

    @NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    @NotBlank(message = "Password is required")
    private String password;

    @NotNull(message = "Login option is required")
    private LoginOption loginOption;

    @NotNull(message = "Role IDs are required")
    private List<String> roleIds;

    private List<String> teamIds;

    @NotNull(message = "isInternal flag is required")
    private Boolean isInternal;

    private String organizationId;

    /**
     * Create the account already disabled. A disabled user cannot log in and their API keys
     * are inert, but they stay linkable — which is what an import needs when it has to stand
     * up an owner or assessor that no live person is behind yet.
     */
    private Boolean disabled;
}
