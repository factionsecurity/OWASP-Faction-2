package com.faction.clientportal.dto;

import com.faction.clientportal.model.LoginOption;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {

    private String id;
    private String username;
    private String email;
    private String firstName;
    private String lastName;
    private LoginOption loginOption;
    private List<String> roleIds;
    private List<String> teamIds;
    private Boolean isInternal;
    @Builder.Default private List<String> organizationIds = new ArrayList<>();
    @Builder.Default private List<String> subOrganizationIds = new ArrayList<>();
    /** Display only: names for the ids above, same order. Sub-org names are "Org / Sub-org". */
    @Builder.Default private List<String> organizationNames = new ArrayList<>();
    @Builder.Default private List<String> subOrganizationNames = new ArrayList<>();
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
    private LocalDateTime disabledAt;
    private Integer failedLoginAttempts;
    private LocalDateTime lastLogin;
    private String profileImageId;
}
